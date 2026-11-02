package ru.k6lab.server.result;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import ru.k6lab.server.run.Run;
import ru.k6lab.server.run.RunService;
import ru.k6lab.server.run.RunSnapshot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** HTML (самодостаточный) и PDF отчёт из одного Thymeleaf-шаблона reports/report. */
@Service
public class ReportService {

    private final SpringTemplateEngine engine;
    private final RunService runService;
    private final StepResultService results;

    public ReportService(SpringTemplateEngine engine, RunService runService, StepResultService results) {
        this.engine = engine;
        this.runService = runService;
        this.results = results;
    }

    public String renderHtml(long runId) {
        Run run = runService.get(runId);
        RunSnapshot snap = runService.snapshot(run);
        ResultData data = results.load(runId).orElseGet(() -> results.compute(runId));
        List<ChartRenderer.Img> charts = List.of();
        try {
            charts = ChartRenderer.charts(data);
        } catch (RuntimeException | LinkageError e) {
            // графики - украшение: без них отчёт всё равно полезен (например, нет AWT на сервере)
        }
        Context ctx = new Context(new Locale("ru"));
        ctx.setVariable("run", run);
        ctx.setVariable("snap", snap);
        ctx.setVariable("data", data);
        ctx.setVariable("charts", charts);
        ctx.setVariable("fmt", new ReportFormat());
        ctx.setVariable("generatedAt", Instant.now());
        return engine.process("reports/report", ctx);
    }

    public byte[] renderPdf(long runId) {
        String html = renderHtml(runId);
        var jsoup = Jsoup.parse(html);
        org.w3c.dom.Document doc = new W3CDom().fromJsoup(jsoup);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder b = new PdfRendererBuilder();
            b.useFastMode();
            b.useFont(() -> font("DejaVuSans.ttf"), "DejaVu Sans", 400, PdfRendererBuilder.FontStyle.NORMAL, true);
            b.useFont(() -> font("DejaVuSans-Bold.ttf"), "DejaVu Sans", 700, PdfRendererBuilder.FontStyle.NORMAL, true);
            b.useFont(() -> font("DejaVuSans-Oblique.ttf"), "DejaVu Sans", 400, PdfRendererBuilder.FontStyle.ITALIC, true);
            b.withW3cDocument(doc, "http://localhost/");
            b.toStream(out);
            b.run();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось сформировать PDF: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сформировать PDF: " + e.getMessage(), e);
        }
    }

    private static InputStream font(String name) {
        return ReportService.class.getResourceAsStream("/fonts/" + name);
    }
}
