package ru.k6lab.server.script;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.k6lab.core.util.ZipUtil;
import ru.k6lab.server.git.GitClient;
import ru.k6lab.server.git.GitRepoService;
import ru.k6lab.server.load.LoadOperation;
import ru.k6lab.server.load.LoadService;
import ru.k6lab.server.run.Run;
import ru.k6lab.server.run.RunService;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Controller
@RequestMapping("/scripts")
public class ScriptController {

    private final ScriptService service;
    private final GitRepoService gitRepos;
    private final AdminHelper helper;
    private final K6Tool k6;
    private final LoadService load;
    private final RunService runs;

    public ScriptController(ScriptService service, GitRepoService gitRepos,
                            AdminHelper helper, K6Tool k6, LoadService load, RunService runs) {
        this.load = load;
        this.runs = runs;
        this.service = service;
        this.gitRepos = gitRepos;
        this.helper = helper;
        this.k6 = k6;
    }

    // ------------------------------------------------------------------ список и создание

    @GetMapping
    public String list(Model model) {
        List<Script> all = service.list();
        model.addAttribute("scripts", all);
        model.addAttribute("latest", all.stream().collect(java.util.stream.Collectors.toMap(Script::getId,
                s -> service.latest(s.getId()).map(v -> (Object) v).orElse(""))));
        model.addAttribute("k6Version", k6.version().orElse(null));
        model.addAttribute("loadDefined", all.stream().collect(java.util.stream.Collectors.toMap(Script::getId, x -> load.isDefined(x.getId()))));
        model.addAttribute("runningIds", runningScriptIds());
        return "scripts/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("repos", gitRepos.list());
        return "scripts/new";
    }

    @PostMapping
    public String create(@RequestParam String name, @RequestParam(defaultValue = "") String description,
                         @RequestParam(defaultValue = "") String entrypoint, @RequestParam(defaultValue = "") String comment,
                         @RequestParam("file") MultipartFile file, RedirectAttributes ra) {
        try {
            if (file.isEmpty()) {
                throw new IllegalArgumentException("Выберите файл");
            }
            try (InputStream in = file.getInputStream()) {
                ScriptVersion v = service.createFromUpload(name, description, file.getOriginalFilename(), in, entrypoint, comment);
                ra.addFlashAttribute("info", "Скрипт создан, версия 1");
                return "redirect:/scripts/" + v.getScriptId();
            }
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/scripts/new";
        }
    }

    @PostMapping("/git")
    public String createFromGit(@RequestParam String name, @RequestParam Long repoId, @RequestParam String ref,
                                @RequestParam String entrypoint, RedirectAttributes ra) {
        try {
            ScriptVersion v = service.importFromGit(null, name, repoId, ref, entrypoint, null);
            ra.addFlashAttribute("info", "Скрипт создан из Git, версия 1");
            return "redirect:/scripts/" + v.getScriptId();
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            ra.addFlashAttribute("error", gitMessage(e));
            return "redirect:/scripts/new";
        } catch (GitClient.GitHostKeyException e) {
            ra.addFlashAttribute("error", e.getMessage() + " Подтвердите ключ в разделе «Git-репозитории» (кнопка «Проверить доступ»).");
            return "redirect:/scripts/new";
        }
    }

    // ------------------------------------------------------------------ скрипт и версии

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Script s = service.get(id);
        model.addAttribute("script", s);
        model.addAttribute("versions", service.versionsOf(id));
        model.addAttribute("repos", gitRepos.list());
        model.addAttribute("gitRepo", s.getGitRepoId() == null ? null : gitRepos.get(s.getGitRepoId()));
        model.addAttribute("refs", s.getGitRepoId() == null ? List.of() : gitRepos.cachedRefs(s.getGitRepoId()));
        List<LoadOperation> ops = load.operations(id);
        model.addAttribute("ops", ops);
        model.addAttribute("loadDefined", ops.stream().anyMatch(LoadOperation::isEnabled));
        model.addAttribute("running", runningScriptIds().contains(id));
        model.addAttribute("history", runs.search(id, null, 20));
        model.addAttribute("fmt", new ru.k6lab.server.result.ReportFormat());
        return "scripts/detail";
    }

    /** Скрипты, у которых сейчас есть выполняющийся или ожидающий запуск. */
    private Set<Long> runningScriptIds() {
        Set<Long> ids = new HashSet<>();
        for (Run r : runs.activeRuns()) {
            if (r.getScriptId() != null) {
                ids.add(r.getScriptId());
            }
        }
        for (Run r : runs.search(null, ru.k6lab.core.runstate.RunStatus.QUEUED, 50)) {
            if (r.getScriptId() != null) {
                ids.add(r.getScriptId());
            }
        }
        return ids;
    }

    @PostMapping("/{id}/versions")
    public String uploadVersion(@PathVariable Long id, @RequestParam("file") MultipartFile file,
                                @RequestParam(defaultValue = "") String entrypoint,
                                @RequestParam(defaultValue = "") String comment, RedirectAttributes ra) {
        try {
            if (file.isEmpty()) {
                throw new IllegalArgumentException("Выберите файл");
            }
            try (InputStream in = file.getInputStream()) {
                ScriptVersion v = service.addVersionFromUpload(id, file.getOriginalFilename(), in, entrypoint, comment);
                ra.addFlashAttribute("info", "Создана версия " + v.getNumber());
                return "redirect:/scripts/" + id + "/versions/" + v.getId();
            }
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/scripts/" + id;
        }
    }

    @GetMapping("/{id}/versions/{vid}")
    public String version(@PathVariable Long id, @PathVariable Long vid, Model model) throws IOException {
        ScriptVersion v = service.version(id, vid);
        model.addAttribute("script", service.get(id));
        model.addAttribute("v", v);
        model.addAttribute("inspectJson", v.getInspectResult() == null ? null
                : new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(v.getInspectResult()));
        return "scripts/version";
    }

    @PostMapping("/{id}/versions/{vid}/operations")
    public String addOperation(@PathVariable Long id, @PathVariable Long vid, @RequestParam String name, RedirectAttributes ra) {
        try {
            service.addOperation(id, vid, name);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/scripts/" + id + "/versions/" + vid;
    }

    @PostMapping("/{id}/versions/{vid}/operations/delete")
    public String removeOperation(@PathVariable Long id, @PathVariable Long vid, @RequestParam String name) {
        service.removeOperation(id, vid, name);
        return "redirect:/scripts/" + id + "/versions/" + vid;
    }

    @PostMapping("/{id}/versions/{vid}/rollback")
    public String rollback(@PathVariable Long id, @PathVariable Long vid, RedirectAttributes ra) {
        try {
            ScriptVersion v = service.rollback(id, vid);
            ra.addFlashAttribute("info", "Откат: создана версия " + v.getNumber());
            return "redirect:/scripts/" + id + "/versions/" + v.getId();
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/scripts/" + id;
        }
    }

    @GetMapping("/{id}/versions/{vid}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id, @PathVariable Long vid) throws IOException {
        ScriptVersion v = service.version(id, vid);
        Script s = service.get(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(s.getName().replaceAll("[^A-Za-z0-9._-]", "_") + "-v" + v.getNumber() + ".zip").build().toString())
                .body(service.zipBytes(v));
    }

    /** Содержимое файла версии для редактора (JSON). */
    @GetMapping(value = "/{id}/versions/{vid}/file", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> file(@PathVariable Long id, @PathVariable Long vid, @RequestParam String path) throws IOException {
        ScriptVersion v = service.version(id, vid);
        byte[] data = ZipUtil.read(new ByteArrayInputStream(service.zipBytes(v)), path);
        if (data == null) {
            throw new IllegalArgumentException("Файл не найден: " + path);
        }
        return Map.of("path", path, "content", new String(data, StandardCharsets.UTF_8));
    }

    @GetMapping("/{id}/versions/{vid}/edit")
    public String editor(@PathVariable Long id, @PathVariable Long vid, @RequestParam(required = false) String file, Model model) {
        ScriptVersion v = service.version(id, vid);
        model.addAttribute("script", service.get(id));
        model.addAttribute("v", v);
        String path = file != null ? file : v.getEntrypoint();
        model.addAttribute("path", path);
        model.addAttribute("scriptFiles", v.getFiles().stream().filter(f -> f.kind().equals("SCRIPT")).toList());
        return "scripts/editor";
    }

    @PostMapping("/{id}/versions/{vid}/edit")
    public String saveEdit(@PathVariable Long id, @PathVariable Long vid, @RequestParam String file,
                           @RequestParam String content, @RequestParam(defaultValue = "") String comment, RedirectAttributes ra) {
        try {
            ScriptVersion v = service.saveEdit(id, vid, file, content, comment);
            ra.addFlashAttribute("info", "Сохранено как версия " + v.getNumber());
            return "redirect:/scripts/" + id + "/versions/" + v.getId();
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/scripts/" + id + "/versions/" + vid + "/edit?file=" + file;
        }
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            service.delete(id);
            return "redirect:/scripts";
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/scripts/" + id;
        }
    }

    @GetMapping("/admin-helper")
    public ResponseEntity<byte[]> adminHelper() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/javascript;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("admin.js").build().toString())
                .body(helper.content());
    }

    // ------------------------------------------------------------------ Git

    @PostMapping("/{id}/git-import")
    public String gitImport(@PathVariable Long id, @RequestParam Long repoId, @RequestParam String ref,
                            @RequestParam String entrypoint, @RequestParam(defaultValue = "") String comment, RedirectAttributes ra) {
        try {
            ScriptVersion v = service.importFromGit(id, null, repoId, ref, entrypoint, comment);
            ra.addFlashAttribute("info", "Версия " + v.getNumber() + " из Git (" + v.sourceLabel() + ")");
            return "redirect:/scripts/" + id + "/versions/" + v.getId();
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            ra.addFlashAttribute("error", gitMessage(e));
        } catch (GitClient.GitHostKeyException e) {
            ra.addFlashAttribute("error", e.getMessage() + " Подтвердите ключ в разделе «Git-репозитории» (кнопка «Проверить доступ»).");
        }
        return "redirect:/scripts/" + id;
    }

    /** «Проверить обновления»: новые коммиты в ветке с последнего импорта (htmx-фрагмент). */
    @GetMapping("/{id}/git-check")
    public String gitCheck(@PathVariable Long id, Model model) {
        try {
            model.addAttribute("commits", service.checkGitUpdates(id));
            model.addAttribute("script", service.get(id));
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            model.addAttribute("error", gitMessage(e));
        } catch (GitClient.GitHostKeyException e) {
            model.addAttribute("error", e.getMessage() + " Подтвердите ключ в «Git-репозитории».");
        }
        return "scripts/detail :: gitCheck";
    }

    @PostMapping("/{id}/git-latest")
    public String gitLatest(@PathVariable Long id, RedirectAttributes ra) {
        try {
            ScriptVersion v = service.importLatestOfBranch(id);
            ra.addFlashAttribute("info", "Версия " + v.getNumber() + " (" + v.sourceLabel() + ")");
            return "redirect:/scripts/" + id + "/versions/" + v.getId();
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            ra.addFlashAttribute("error", gitMessage(e));
        } catch (GitClient.GitHostKeyException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/scripts/" + id;
    }

    private static String gitMessage(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
