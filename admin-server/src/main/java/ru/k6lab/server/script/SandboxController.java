package ru.k6lab.server.script;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import ru.k6lab.core.util.ZipUtil;
import ru.k6lab.server.run.K6Flag;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Режим отладки: собрать скрипт из файлов в браузере, выполнить на сервере, посмотреть лог и сохранить. */
@Controller
@RequestMapping("/scripts/sandbox")
@PreAuthorize("hasRole('ADMIN')")
public class SandboxController {

    private final SandboxService sandbox;
    private final ScriptService scripts;
    private final K6Tool k6;

    public SandboxController(SandboxService sandbox, ScriptService scripts, K6Tool k6) {
        this.sandbox = sandbox;
        this.scripts = scripts;
        this.k6 = k6;
    }

    @GetMapping
    public String page(@RequestParam(required = false) Long script, Model model) {
        model.addAttribute("scriptList", scripts.list());
        model.addAttribute("preselect", script);
        model.addAttribute("k6Flags", Arrays.asList(K6Flag.values()));
        model.addAttribute("k6Version", k6.version().orElse(null));
        return "scripts/sandbox";
    }

    @PostMapping("/run")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> run(@RequestBody SandboxService.RunRequest req) {
        try {
            return ResponseEntity.ok(Map.of("id", sandbox.start(req)));
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @GetMapping("/{sid}/log")
    @ResponseBody
    public ResponseEntity<?> log(@PathVariable String sid, @RequestParam(defaultValue = "0") int from) {
        try {
            return ResponseEntity.ok(sandbox.read(sid, from));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    /** Функции нагрузки в исходнике основного файла: тот же анализатор, что определяет операции при загрузке скрипта. */
    @PostMapping("/functions")
    @ResponseBody
    public Map<String, Object> functions(@RequestBody Map<String, String> body) {
        String source = body.getOrDefault("source", "");
        if (source.length() > 2_000_000) {
            return Map.of("functions", List.of());
        }
        return Map.of("functions", ru.k6lab.core.script.ScriptAnalyzer.analyze(source).operations().stream()
                .map(ru.k6lab.core.script.ScriptAnalyzer.Operation::name).toList());
    }

    @PostMapping("/{sid}/stop")
    @ResponseBody
    public Map<String, Object> stop(@PathVariable String sid) {
        sandbox.stop(sid);
        return Map.of("ok", true);
    }

    /** Лимит на загрузку существующего скрипта в отладчик: файлы целиком уходят в браузер. */
    private static final long MAX_LOAD_BYTES = 20L * 1024 * 1024;

    /** Файлы последней версии существующего скрипта для отладчика (текст как есть, остальное base64). */
    @GetMapping("/script/{id}")
    @ResponseBody
    public ResponseEntity<?> loadScript(@PathVariable Long id) {
        try {
            Script s = scripts.get(id);
            ScriptVersion v = scripts.latest(id).orElseThrow(() -> new IllegalArgumentException("У скрипта нет версий"));
            Map<String, byte[]> files = ZipUtil.readAll(new ByteArrayInputStream(scripts.zipBytes(v)));
            long total = files.values().stream().mapToLong(b -> b.length).sum();
            if (total > MAX_LOAD_BYTES) {
                throw new IllegalArgumentException("Скрипт слишком большой для отладки в браузере: " + total / 1024 / 1024 + " МБ, максимум "
                        + MAX_LOAD_BYTES / 1024 / 1024 + " МБ");
            }
            List<SandboxService.FileIn> out = new java.util.ArrayList<>();
            for (var e : files.entrySet()) {
                String text = utf8(e.getValue());
                out.add(text != null ? new SandboxService.FileIn(e.getKey(), text, null)
                        : new SandboxService.FileIn(e.getKey(), null, java.util.Base64.getEncoder().encodeToString(e.getValue())));
            }
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("id", s.getId());
            body.put("name", s.getName());
            body.put("version", v.getNumber());
            body.put("entrypoint", v.getEntrypoint());
            body.put("env", s.getEnv());
            body.put("files", out);
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    /** Строгое декодирование UTF-8: null для бинарных данных (в т.ч. с нулевыми байтами). */
    private static String utf8(byte[] data) {
        for (byte b : data) {
            if (b == 0) {
                return null;
            }
        }
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(data)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return null;
        }
    }

    /** scriptId задан - сохраняем как новую версию существующего скрипта, иначе создаём новый скрипт. */
    public record SaveRequest(String name, String description, String entrypoint, List<SandboxService.FileIn> files,
                              Long scriptId, String comment) {
    }

    /** Сохранение набора файлов как обычного скрипта (версия 1). */
    @PostMapping("/save")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> save(@RequestBody SaveRequest req) {
        try {
            byte[] zip = SandboxService.zip(SandboxService.decode(req.files()));
            if (req.scriptId() != null) {
                ScriptVersion nv = scripts.addVersionFromUpload(req.scriptId(), "sandbox.zip", new ByteArrayInputStream(zip),
                        req.entrypoint() == null ? "" : req.entrypoint(),
                        req.comment() == null || req.comment().isBlank() ? "правка в режиме отладки" : req.comment());
                return ResponseEntity.ok(Map.of("redirect", "/scripts/" + req.scriptId() + "/versions/" + nv.getId()));
            }
            ScriptVersion v = scripts.createFromUpload(req.name() == null ? "" : req.name(),
                    req.description() == null ? "" : req.description(), "sandbox.zip", new ByteArrayInputStream(zip),
                    req.entrypoint() == null ? "" : req.entrypoint(), "создан в режиме отладки");
            return ResponseEntity.ok(Map.of("redirect", "/scripts/" + v.getScriptId()));
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }
}
