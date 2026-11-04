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
    public String page(Model model) {
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

    @PostMapping("/{sid}/stop")
    @ResponseBody
    public Map<String, Object> stop(@PathVariable String sid) {
        sandbox.stop(sid);
        return Map.of("ok", true);
    }

    public record SaveRequest(String name, String description, String entrypoint, List<SandboxService.FileIn> files) {
    }

    /** Сохранение набора файлов как обычного скрипта (версия 1). */
    @PostMapping("/save")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> save(@RequestBody SaveRequest req) {
        try {
            byte[] zip = SandboxService.zip(SandboxService.decode(req.files()));
            ScriptVersion v = scripts.createFromUpload(req.name() == null ? "" : req.name(),
                    req.description() == null ? "" : req.description(), "sandbox.zip", new ByteArrayInputStream(zip),
                    req.entrypoint() == null ? "" : req.entrypoint(), "создан в режиме отладки");
            return ResponseEntity.ok(Map.of("redirect", "/scripts/" + v.getScriptId()));
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }
}
