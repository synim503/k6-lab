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
    public ResponseEntity<byte[]> html(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean download,
                                       @RequestParam(required = false) String time,
                                       @RequestParam(required = false) String sort,
                                       @RequestParam(required = false) String dir,
                                       @RequestParam(required = false) String warmup) {
        byte[] body = reports.renderHtml(id, absTime(time), parseSortBy(sort), parseSortDir(dir), parseSkipWarmup(warmup))
                .getBytes(StandardCharsets.UTF_8);
        ResponseEntity.BodyBuilder b = ResponseEntity.ok().contentType(new MediaType("text", "html", StandardCharsets.UTF_8));
        if (download) {
            b.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("run-" + id + "-report.html").build().toString());
        }
        return b.body(body);
    }

    /** Ось времени графиков: abs - часы, rel - минуты от начала теста, не задано - как в настройках. */
    private static Boolean absTime(String time) {
        return time == null || time.isBlank() ? null : "abs".equalsIgnoreCase(time.trim());
    }

    /** Прогрев на графиках: skip/after/true - от конца прогрева, include/all/false - с начала теста, пусто - из настроек. */
    private static Boolean parseSkipWarmup(String warmup) {
        if (warmup == null || warmup.isBlank()) {
            return null;
        }
        String v = warmup.trim().toLowerCase(java.util.Locale.ROOT);
        if ("skip".equals(v) || "after".equals(v) || "true".equals(v) || "1".equals(v)) {
            return Boolean.TRUE;
        }
        if ("include".equals(v) || "all".equals(v) || "false".equals(v) || "0".equals(v)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static ChartStyle.SortBy parseSortBy(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return ChartStyle.SortBy.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ChartStyle.SortDir parseSortDir(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return ChartStyle.SortDir.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    /** Отчёт в Word: имя файла «сценарий - профиль». */
    @GetMapping(value = "/report.docx", produces = DOCX)
    public ResponseEntity<byte[]> docx(@PathVariable Long id, @RequestParam(required = false) String time,
                                       @RequestParam(required = false) String sort,
                                       @RequestParam(required = false) String dir,
                                       @RequestParam(required = false) String warmup) {
        String name = ReportService.title(runService.get(id)).replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(DOCX))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name + " (запуск " + id + ").docx", StandardCharsets.UTF_8).build().toString())
                .body(reports.renderDocx(id, absTime(time), parseSortBy(sort), parseSortDir(dir), parseSkipWarmup(warmup)));
    }

    @GetMapping(value = "/report.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable Long id, @RequestParam(required = false) String time,
                                      @RequestParam(required = false) String sort,
                                      @RequestParam(required = false) String dir,
                                      @RequestParam(required = false) String warmup) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("run-" + id + "-report.pdf").build().toString())
                .body(reports.renderPdf(id, absTime(time), parseSortBy(sort), parseSortDir(dir), parseSkipWarmup(warmup)));
    }
}
