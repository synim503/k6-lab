package ru.k6lab.server.api;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.k6lab.core.runstate.RunStatus;
import ru.k6lab.core.util.ZipUtil;
import ru.k6lab.server.git.GitClient;
import ru.k6lab.server.git.GitRepo;
import ru.k6lab.server.git.GitRepoService;
import ru.k6lab.server.load.LoadProfileStore;
import ru.k6lab.server.load.LoadService;
import ru.k6lab.server.run.Run;
import ru.k6lab.server.run.RunService;
import ru.k6lab.server.script.Script;
import ru.k6lab.server.script.ScriptService;
import ru.k6lab.server.script.ScriptVersion;
import ru.k6lab.server.ssh.SshExceptions;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** REST API для скриптов, их версий и редактора с автосохранением. */
@RestController
@RequestMapping("/api/v1/scripts")
public class ScriptApiController {

    private static final com.fasterxml.jackson.databind.ObjectMapper PRETTY_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    private final ScriptService scripts;
    private final GitRepoService gitRepos;
    private final LoadService load;
    private final LoadProfileStore profiles;
    private final RunService runs;
    private final ru.k6lab.server.run.RunOrchestrator orchestrator;

    public ScriptApiController(ScriptService scripts, GitRepoService gitRepos,
                               LoadService load, LoadProfileStore profiles, RunService runs,
                               ru.k6lab.server.run.RunOrchestrator orchestrator) {
        this.scripts = scripts;
        this.gitRepos = gitRepos;
        this.load = load;
        this.profiles = profiles;
        this.runs = runs;
        this.orchestrator = orchestrator;
    }

    public record GitCreateRequest(String name, Long repoId, String ref, String entrypoint, String comment) {
    }

    public record GitImportRequest(Long repoId, String ref, String entrypoint, String comment) {
    }

    public record OpRequest(String name) {
    }

    public record AutosaveRequest(String file, String content, Boolean inPlace) {
    }

    @GetMapping
    public Map<String, Object> list() {
        List<Script> all = scripts.list();
        Map<Long, Integer> profCounts = profiles.countsByScript();
        Map<Long, Run> activeByScript = new HashMap<>();
        for (Run r : runs.activeRuns()) {
            if (r.getScriptId() != null) {
                activeByScript.putIfAbsent(r.getScriptId(), r);
            }
        }
        Map<Long, GitRepo> repoMap = new HashMap<>();
        for (GitRepo r : gitRepos.list()) {
            repoMap.put(r.getId(), r);
        }

        List<Map<String, Object>> items = new ArrayList<>(all.size());
        for (Script s : all) {
            Optional<ScriptVersion> latest = scripts.latest(s.getId());
            Map<String, Object> dto = toScriptDto(s, repoMap.get(s.getGitRepoId()));
            dto.put("latestVersion", latest.map(v -> toVersionSummaryDto(v, scripts.hasFiles(v))).orElse(null));
            dto.put("loadDefined", load.isDefined(s.getId()));
            dto.put("profilesCount", profCounts.getOrDefault(s.getId(), 0));
            Run active = activeByScript.get(s.getId());
            dto.put("activeRun", active != null ? DashboardApiController.toRunSummaryDto(active) : null);
            List<Run> recent = runs.search(s.getId(), null, 1);
            dto.put("lastRun", recent.isEmpty() ? null : DashboardApiController.toRunSummaryDto(recent.get(0)));
            items.add(dto);
        }
        return Map.of("items", items, "gitRepos", gitRepos.list().stream().map(GitRepoApiController::toRepoDto).toList());
    }

    @PostMapping
    public Map<String, Object> createFromUpload(@RequestParam String name,
                                                @RequestParam(defaultValue = "") String description,
                                                @RequestParam(defaultValue = "") String entrypoint,
                                                @RequestParam(defaultValue = "") String comment,
                                                @RequestParam("file") MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            ScriptVersion v = scripts.createFromUpload(name, description, file.getOriginalFilename(), in, entrypoint, comment);
            return Map.of("ok", true, "scriptId", v.getScriptId(), "versionId", v.getId(), "versionNumber", v.getNumber());
        }
    }

    @PostMapping("/git")
    public ResponseEntity<Map<String, Object>> createFromGit(@RequestBody GitCreateRequest req) throws IOException {
        try {
            ScriptVersion v = scripts.importFromGit(null, req.name(), req.repoId(), req.ref(), req.entrypoint(), req.comment());
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "scriptId", v.getScriptId(),
                    "versionId", v.getId(),
                    "versionNumber", v.getNumber()
            ));
        } catch (GitClient.GitHostKeyException e) {
            return GitRepoApiController.hostKeyResponse(e, req.repoId());
        }
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size,
                                      @RequestParam(required = false) RunStatus status) {
        Script s = scripts.get(id);
        GitRepo repo = s.getGitRepoId() != null ? safeRepo(s.getGitRepoId()) : null;
        List<ScriptVersion> vers = scripts.versionsOf(id);
        ScriptVersion latest = vers.isEmpty() ? null : vers.get(0);

        Page<Run> runPage = runs.searchPage(id, status, Math.max(0, page), Math.min(100, Math.max(1, size)));
        List<Run> activeForScript = runs.activeRuns().stream()
                .filter(r -> id.equals(r.getScriptId()))
                .toList();

        var profList = profiles.list(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("script", toScriptDto(s, repo));
        out.put("latestVersion", latest != null ? toVersionDto(latest, scripts.hasFiles(latest)) : null);
        out.put("versions", vers.stream().map(v -> toVersionSummaryDto(v, scripts.hasFiles(v))).toList());
        out.put("missingFiles", vers.stream().filter(v -> !scripts.hasFiles(v)).map(ScriptVersion::getId).toList());
        out.put("loadDefined", load.isDefined(id));
        out.put("profilesCount", profList.size());
        out.put("profiles", profList.stream().map(p -> Map.of(
                "id", p.id(),
                "name", p.name(),
                "updatedAt", p.updatedAt() != null ? p.updatedAt().toString() : "",
                "updatedBy", p.updatedBy() != null ? p.updatedBy() : ""
        )).toList());
        out.put("activeOperations", load.activeOperations(id).stream().map(op -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", op.getName());
            m.put("execFn", op.getExecFn());
            m.put("executor", op.getExecutor());
            m.put("ratePerSec", op.getRatePerSec());
            m.put("expectedDurationSec", op.getExpectedDurationSec());
            m.put("slaPercentile", op.getSlaPercentile());
            m.put("slaThresholdMs", op.getSlaThresholdMs());
            m.put("maxErrorPct", op.getMaxErrorPct());
            m.put("failureSource", op.getFailureSource() != null ? op.getFailureSource().name() : "HTTP");
            m.put("enabled", op.isEnabled());
            return m;
        }).toList());
        out.put("activeRuns", activeForScript.stream().map(DashboardApiController::toRunSummaryDto).toList());
        out.put("runs", Map.of(
                "items", runPage.getContent().stream().map(DashboardApiController::toRunSummaryDto).toList(),
                "page", runPage.getNumber(),
                "size", runPage.getSize(),
                "totalElements", runPage.getTotalElements(),
                "totalPages", runPage.getTotalPages()
        ));
        out.put("gitRepos", gitRepos.list().stream().map(GitRepoApiController::toRepoDto).toList());
        return out;
    }

    @PostMapping("/{id}/delete")
    public Map<String, Object> delete(@PathVariable Long id) {
        scripts.delete(id);
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/stop")
    public Map<String, Object> stopActiveRuns(@PathVariable Long id) {
        int stopped = 0;
        for (Run r : runs.activeRuns()) {
            if (id.equals(r.getScriptId())) {
                orchestrator.stop(r.getId(), "остановлено пользователем");
                stopped++;
            }
        }
        return Map.of("ok", true, "stopped", stopped);
    }

    @PostMapping("/{id}/versions")
    public Map<String, Object> uploadVersion(@PathVariable Long id,
                                             @RequestParam("file") MultipartFile file,
                                             @RequestParam(defaultValue = "") String entrypoint,
                                             @RequestParam(defaultValue = "") String comment) throws IOException {
        try (InputStream in = file.getInputStream()) {
            ScriptVersion v = scripts.addVersionFromUpload(id, file.getOriginalFilename(), in, entrypoint, comment);
            return Map.of("ok", true, "version", toVersionDto(v, true));
        }
    }

    @PostMapping("/{id}/git-import")
    public ResponseEntity<Map<String, Object>> gitImport(@PathVariable Long id, @RequestBody GitImportRequest req) throws IOException {
        try {
            ScriptVersion v = scripts.importFromGit(id, null, req.repoId(), req.ref(), req.entrypoint(), req.comment());
            return ResponseEntity.ok(Map.of("ok", true, "version", toVersionDto(v, true)));
        } catch (GitClient.GitHostKeyException e) {
            return GitRepoApiController.hostKeyResponse(e, req.repoId());
        }
    }

    @GetMapping("/{id}/git-check")
    public ResponseEntity<Map<String, Object>> gitCheck(@PathVariable Long id) throws IOException {
        try {
            List<GitClient.CommitInfo> commits = scripts.checkGitUpdates(id);
            List<Map<String, Object>> items = commits.stream().map(c -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("hash", c.hash());
                m.put("author", c.author());
                m.put("time", c.time());
                m.put("message", c.message());
                return m;
            }).toList();
            return ResponseEntity.ok(Map.of("ok", true, "commits", items));
        } catch (GitClient.GitHostKeyException e) {
            Script s = scripts.get(id);
            return GitRepoApiController.hostKeyResponse(e, s.getGitRepoId());
        }
    }

    @PostMapping("/{id}/git-latest")
    public ResponseEntity<Map<String, Object>> gitLatest(@PathVariable Long id) throws IOException {
        try {
            ScriptVersion v = scripts.importLatestOfBranch(id);
            return ResponseEntity.ok(Map.of("ok", true, "version", toVersionDto(v, true)));
        } catch (GitClient.GitHostKeyException e) {
            Script s = scripts.get(id);
            return GitRepoApiController.hostKeyResponse(e, s.getGitRepoId());
        }
    }

    @GetMapping("/{id}/versions/{vid}")
    public Map<String, Object> versionDetail(@PathVariable Long id, @PathVariable Long vid) {
        Script s = scripts.get(id);
        ScriptVersion v = scripts.version(id, vid);
        boolean hasFiles = scripts.hasFiles(v);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("script", toScriptDto(s, s.getGitRepoId() != null ? safeRepo(s.getGitRepoId()) : null));
        out.put("version", toVersionDto(v, hasFiles));
        return out;
    }

    @PostMapping("/{id}/versions/{vid}/operations")
    public Map<String, Object> addOperation(@PathVariable Long id, @PathVariable Long vid, @RequestBody OpRequest req) {
        ScriptVersion v = scripts.addOperation(id, vid, req.name());
        return Map.of("ok", true, "version", toVersionDto(v, scripts.hasFiles(v)));
    }

    @PostMapping("/{id}/versions/{vid}/operations/delete")
    public Map<String, Object> removeOperation(@PathVariable Long id, @PathVariable Long vid, @RequestBody OpRequest req) {
        ScriptVersion v = scripts.removeOperation(id, vid, req.name());
        return Map.of("ok", true, "version", toVersionDto(v, scripts.hasFiles(v)));
    }

    @PostMapping("/{id}/versions/{vid}/rollback")
    public Map<String, Object> rollback(@PathVariable Long id, @PathVariable Long vid) throws IOException {
        ScriptVersion v = scripts.rollback(id, vid);
        return Map.of("ok", true, "version", toVersionDto(v, true));
    }

    @GetMapping("/{id}/versions/{vid}/file")
    public Map<String, Object> readFile(@PathVariable Long id, @PathVariable Long vid, @RequestParam String path) throws IOException {
        ScriptVersion v = scripts.version(id, vid);
        byte[] data = ZipUtil.read(new ByteArrayInputStream(scripts.zipBytes(v)), path);
        if (data == null) {
            throw new IllegalArgumentException("Файл не найден: " + path);
        }
        return Map.of("path", path, "content", new String(data, StandardCharsets.UTF_8));
    }

    @PostMapping("/{id}/versions/{vid}/autosave")
    public Map<String, Object> autosave(@PathVariable Long id, @PathVariable Long vid, @RequestBody AutosaveRequest req) throws IOException {
        boolean inPlace = req.inPlace() != null && req.inPlace();
        ScriptVersion v = scripts.autosaveEdit(id, vid, req.file(), req.content(), inPlace);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("versionId", v.getId());
        out.put("versionNumber", v.getNumber());
        out.put("valid", v.isValidForModel());
        out.put("invalidReason", v.getInvalidReason());
        out.put("warnings", v.getWarnings() == null ? List.of() : v.getWarnings());
        out.put("version", toVersionDto(v, true));
        return out;
    }

    private GitRepo safeRepo(Long repoId) {
        try {
            return gitRepos.get(repoId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static Map<String, Object> toScriptDto(Script s, GitRepo repo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("description", s.getDescription());
        m.put("gitRepoId", s.getGitRepoId());
        m.put("gitRepoName", repo != null ? repo.getName() : null);
        m.put("gitBranch", s.getGitBranch());
        m.put("gitEntrypoint", s.getGitEntrypoint());
        m.put("generatorCount", s.getGeneratorCount());
        m.put("createdAt", s.getCreatedAt());
        m.put("updatedAt", s.getUpdatedAt());
        return m;
    }

    public static Map<String, Object> toVersionSummaryDto(ScriptVersion v, boolean hasFiles) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.getId());
        m.put("scriptId", v.getScriptId());
        m.put("number", v.getNumber());
        m.put("source", v.getSource().name());
        m.put("sourceLabel", v.sourceLabel());
        m.put("comment", v.getComment());
        m.put("entrypoint", v.getEntrypoint());
        m.put("gitBranch", v.getGitBranch());
        m.put("gitRef", v.getGitRef());
        m.put("commitHash", v.getCommitHash());
        m.put("sizeBytes", v.getSizeBytes());
        m.put("validForModel", v.isValidForModel());
        m.put("invalidReason", v.getInvalidReason());
        m.put("operationsCount", v.getOperations() != null ? v.getOperations().size() : 0);
        m.put("filesCount", v.getFiles() != null ? v.getFiles().size() : 0);
        m.put("hasFiles", hasFiles);
        m.put("createdBy", v.getCreatedBy());
        m.put("createdAt", v.getCreatedAt());
        return m;
    }

    public static Map<String, Object> toVersionDto(ScriptVersion v, boolean hasFiles) {
        Map<String, Object> m = toVersionSummaryDto(v, hasFiles);
        m.put("sha256", v.getSha256());
        m.put("inspectStatus", v.getInspectStatus() != null ? v.getInspectStatus().name() : null);
        m.put("inspectError", v.getInspectError());
        String inspectJson = null;
        if (v.getInspectResult() != null) {
            try {
                inspectJson = PRETTY_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(v.getInspectResult());
            } catch (IOException ignored) {
            }
        }
        m.put("inspectJson", inspectJson);
        m.put("usesHelper", v.isUsesHelper());
        m.put("warnings", v.getWarnings() != null ? v.getWarnings() : List.of());
        m.put("operations", v.getOperations() != null ? v.getOperations() : List.of());
        m.put("files", v.getFiles() != null ? v.getFiles() : List.of());
        return m;
    }
}
