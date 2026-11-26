package ru.k6lab.server.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Раздача React SPA (/app/**): любые маршруты без расширения файла перенаправляются на /app/index.html,
 * а статика (/app/assets/**) отдаётся стандартным ResourceHttpRequestHandler.
 */
@Controller
public class SpaController {

    @GetMapping("/app")
    public String appRootNoSlash() {
        return "redirect:/app/";
    }

    @GetMapping({
            "/app/",
            "/app/{p1:[^\\.]*}",
            "/app/{p1:[^\\.]*}/{p2:[^\\.]*}",
            "/app/{p1:[^\\.]*}/{p2:[^\\.]*}/{p3:[^\\.]*}",
            "/app/{p1:[^\\.]*}/{p2:[^\\.]*}/{p3:[^\\.]*}/{p4:[^\\.]*}",
            "/app/{p1:[^\\.]*}/{p2:[^\\.]*}/{p3:[^\\.]*}/{p4:[^\\.]*}/{p5:[^\\.]*}"
    })
    public String forwardSpa() {
        return "forward:/app/index.html";
    }
}
