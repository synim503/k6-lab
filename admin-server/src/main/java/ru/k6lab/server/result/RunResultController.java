package ru.k6lab.server.result;

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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.k6lab.server.run.Run;
import ru.k6lab.server.run.RunService;

import java.nio.charset.StandardCharsets;

/** Результаты запуска: итоги по ступеням, вывод инженера, отчёты HTML/PDF. */
@Controller
@RequestMapping("/runs/{id}")
public class RunResultController {

    static final java.util.List<String> CONCLUSIONS = java.util.List.of("Нагрузка выдерживается", "Есть нарушения SLA",
            "Деградация", "Результат недостоверен");

    private final RunService runService;
    private final StepResultService results;
    private final ReportService reports;

    public RunResultController(RunService runService, StepResultService results, ReportService reports) {
        this.runService = runService;
        this.results = results;
        this.reports = reports;
    }

    /** htmx-фрагмент вкладки «Результаты». */
    @GetMapping("/results")
    public String panel(@PathVariable Long id, Model model) {
        Run run = runService.get(id);
        model.addAttribute("run", run);
        model.addAttribute("fmt", new ReportFormat());
        model.addAttribute("conclusions", CONCLUSIONS);
        model.addAttribute("data", results.load(id).orElse(null));
        model.addAttribute("terminal", run.getStatus().isTerminal());
        return "runs/_results :: results";
    }

    @PostMapping("/results/recompute")
    public String recompute(@PathVariable Long id, RedirectAttributes ra) {
        try {
            results.compute(id);
            ra.addFlashAttribute("info", "Итоги пересчитаны");
        } catch (RuntimeException e) {
            ra.addFlashAttribute("error", "Не удалось пересчитать итоги: " + e.getMessage());
        }
        return "redirect:/runs/" + id + "#results";
    }

    @PostMapping("/conclusion")
    public String conclusion(@PathVariable Long id, @RequestParam(required = false) String conclusion,
                             @RequestParam(required = false) String comment, RedirectAttributes ra) {
        runService.setConclusion(id, conclusion, comment);
        ra.addFlashAttribute("info", "Вывод сохранён");
        return "redirect:/runs/" + id + "#results";
    }

    @GetMapping(value = "/report.html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<byte[]> html(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean download) {
        byte[] body = reports.renderHtml(id).getBytes(StandardCharsets.UTF_8);
        ResponseEntity.BodyBuilder b = ResponseEntity.ok().contentType(new MediaType("text", "html", StandardCharsets.UTF_8));
        if (download) {
            b.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("run-" + id + "-report.html").build().toString());
        }
        return b.body(body);
    }

    @GetMapping(value = "/report.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("run-" + id + "-report.pdf").build().toString())
                .body(reports.renderPdf(id));
    }
}
