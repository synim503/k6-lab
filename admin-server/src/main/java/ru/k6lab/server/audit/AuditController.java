package ru.k6lab.server.audit;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Журнал аудита (только ADMIN, см. SecurityConfig). */
@Controller
@RequestMapping("/audit")
public class AuditController {

    private static final int PAGE_SIZE = 100;

    private final AuditLogRepository repository;

    public AuditController(AuditLogRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "0") int page, @RequestParam(required = false) String q, Model model) {
        var pageable = PageRequest.of(Math.max(0, page), PAGE_SIZE, Sort.by(Sort.Direction.DESC, "ts", "id"));
        String query = q == null ? "" : q.trim();
        var p = query.isEmpty() ? repository.findAll(pageable) : repository.search(query, pageable);
        model.addAttribute("items", p.getContent());
        model.addAttribute("page", p.getNumber());
        model.addAttribute("hasNext", p.hasNext());
        model.addAttribute("q", q);
        return "audit/list";
    }
}
