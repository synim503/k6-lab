package ru.k6lab.server.web;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import ru.k6lab.server.result.ReportFormat;

/** Общие для всех страниц значения модели: помощник подписей и форматов (статусы, операции, бейджи). */
@ControllerAdvice
public class GlobalModel {

    private static final ReportFormat FORMAT = new ReportFormat();

    @ModelAttribute("fmt")
    public ReportFormat fmt() {
        return FORMAT;
    }
}
