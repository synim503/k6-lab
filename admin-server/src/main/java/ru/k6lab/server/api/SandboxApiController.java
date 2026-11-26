package ru.k6lab.server.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.k6lab.server.run.K6Flag;
import ru.k6lab.server.script.K6Tool;
import ru.k6lab.server.script.ScriptService;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Инициализация отладчика скриптов для React SPA (сами run/log/stop/save обслуживаются JSON-методами /scripts/sandbox/*). */
@RestController
@RequestMapping("/api/v1/sandbox")
@PreAuthorize("hasRole('ADMIN')")
public class SandboxApiController {

    private final ScriptService scripts;
    private final K6Tool k6;

    public SandboxApiController(ScriptService scripts, K6Tool k6) {
        this.scripts = scripts;
        this.k6 = k6;
    }

    @GetMapping("/init")
    public Map<String, Object> init() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scripts", scripts.list().stream().map(s -> Map.of(
                "id", s.getId(),
                "name", s.getName(),
                "description", s.getDescription() != null ? s.getDescription() : ""
        )).toList());
        out.put("k6Flags", Arrays.stream(K6Flag.values()).filter(f -> !f.isHttpDebug()).map(f -> Map.of(
                "name", f.name(),
                "arg", f.getArg(),
                "title", f.getTitle(),
                "description", f.getDescription(),
                "changesLoad", f.isChangesLoad()
        )).toList());
        out.put("k6Version", k6.version().orElse(null));
        return out;
    }
}
