package ru.k6lab.server.result;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Форматирование значений в шаблонах отчёта и результатов (null -> «—»). */
public class ReportFormat {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter TM = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    public String n(Double v, int digits) {
        if (v == null || v.isNaN() || v.isInfinite()) {
            return "—";
        }
        String s = String.format(Locale.ROOT, "%." + digits + "f", v);
        return s.startsWith("-") && s.matches("-0(\\.0+)?") ? s.substring(1) : s; // «-0.0» не показываем
    }

    public String n(Double v) {
        return n(v, 1);
    }

    public String dt(Instant t) {
        return t == null ? "—" : DT.format(t);
    }

    public String time(Instant t) {
        return t == null ? "—" : TM.format(t);
    }

    public String status(String s) {
        return switch (s == null ? "" : s) {
            case "SUCCESS" -> "успешна";
            case "FAILED" -> "неуспешна";
            case "UNRELIABLE" -> "недостоверна";
            case "NOT_RUN" -> "не выполнялась";
            default -> s;
        };
    }

    public String css(String s) {
        return switch (s == null ? "" : s) {
            case "SUCCESS" -> "ok";
            case "FAILED" -> "bad";
            case "UNRELIABLE" -> "warn";
            default -> "na";
        };
    }

    public String maxKind(String k) {
        return switch (k == null ? "" : k) {
            case "FOUND" -> "Кандидат максимума найден";
            case "NOT_REACHED" -> "Максимум не достигнут";
            case "NONE" -> "Уже первая ступень неуспешна";
            case "INDETERMINATE" -> "Определить максимум нельзя";
            default -> k;
        };
    }

    public String runStatus(String s) {
        return switch (s == null ? "" : s) {
            case "FINISHED" -> "Завершён";
            case "FAILED" -> "Ошибка";
            case "ABORTED" -> "Прерван";
            case "RUNNING" -> "Выполняется";
            case "STOPPING" -> "Останавливается";
            default -> s;
        };
    }
}
