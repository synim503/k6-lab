package ru.k6lab.server.web;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import ru.k6lab.server.result.ReportFormat;

/** Общие для всех страниц значения модели: помощник подписей и форматов (статусы, операции, бейджи). */
@ControllerAdvice
public class GlobalModel {

    private static final ReportFormat FORMAT = new ReportFormat();

    private final String buildLabel;

    public GlobalModel(ObjectProvider<BuildProperties> build) {
        BuildProperties bp = build.getIfAvailable();
        String label = bp == null ? null : bp.get("label");
        this.buildLabel = label == null || label.isBlank() || label.contains("${") ? null : label;
    }

    /** Метка сборки запущенной админки (тег релиза): по ней видно, какая версия работает; показывается в подвале страниц. */
    @ModelAttribute("buildLabel")
    public String buildLabel() {
        return buildLabel;
    }

    @ModelAttribute("fmt")
    public ReportFormat fmt() {
        return FORMAT;
    }
}
