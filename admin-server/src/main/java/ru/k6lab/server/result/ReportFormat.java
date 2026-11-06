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

    public String runStatus(String s) {
        return switch (s == null ? "" : s) {
            case "FINISHED" -> "Завершён";
            case "FAILED" -> "Ошибка";
            case "ABORTED" -> "Прерван";
            case "RUNNING" -> "Выполняется";
            case "STOPPING" -> "Останавливается";
            case "QUEUED" -> "В очереди";
            case "PREPARING" -> "Подготовка";
            default -> s;
        };
    }

    public String deployOperation(String s) {
        return switch (s == null ? "" : s) {
            case "PREFLIGHT" -> "Проверка хоста";
            case "INSTALL" -> "Установка агента";
            case "UPDATE" -> "Обновление агента";
            case "RESTART" -> "Перезапуск агента";
            case "UNINSTALL" -> "Удаление агента";
            case "REDEPLOY" -> "Жёсткий редеплой";
            case "PROFILE" -> "Профилирование хоста";
            case "TUNE_APPLY" -> "Тюнинг ОС: применение";
            case "TUNE_ROLLBACK" -> "Тюнинг ОС: возврат в исходное";
            default -> s;
        };
    }

    public String deployStatus(String s) {
        return switch (s == null ? "" : s) {
            case "PENDING" -> "В очереди";
            case "RUNNING" -> "Выполняется";
            case "WAITING_HOST_KEY" -> "Ждёт подтверждения ключа";
            case "SUCCEEDED" -> "Успешно";
            case "FAILED" -> "Ошибка";
            case "ROLLED_BACK" -> "Откат";
            default -> s;
        };
    }

    /** Класс бейджа для статуса операции над генератором. */
    public String deployBadge(String s) {
        return switch (s == null ? "" : s) {
            case "SUCCEEDED" -> "text-bg-success";
            case "FAILED", "ROLLED_BACK" -> "text-bg-danger";
            case "WAITING_HOST_KEY" -> "text-bg-warning";
            case "RUNNING" -> "text-bg-primary";
            default -> "text-bg-secondary";
        };
    }

    /** Состояние генератора внутри запуска. */
    public String genRunStatus(String s) {
        return switch (s == null ? "" : s) {
            case "RESERVED" -> "Зарезервирован";
            case "PREPARING" -> "Подготовка";
            case "PREPARED" -> "Готов к старту";
            case "RUNNING" -> "Выполняется";
            case "STOPPING" -> "Останавливается";
            case "EXITED" -> "Завершён";
            case "FAILED" -> "Ошибка";
            case "LOST" -> "Потерян";
            default -> s;
        };
    }

    public String genRunBadge(String s) {
        return switch (s == null ? "" : s) {
            case "RUNNING" -> "text-bg-primary";
            case "EXITED" -> "text-bg-success";
            case "FAILED", "LOST" -> "text-bg-danger";
            case "STOPPING" -> "text-bg-warning";
            default -> "text-bg-secondary";
        };
    }

    /** Класс бейджа статуса запуска: пороги k6 не выполнены - предупреждение, а не авария. */
    public String runBadge(String status, boolean thresholdsFailed) {
        return switch (status == null ? "" : status) {
            case "FINISHED" -> thresholdsFailed ? "text-bg-warning" : "text-bg-success";
            case "FAILED" -> "text-bg-danger";
            case "ABORTED" -> "text-bg-secondary";
            case "STOPPING" -> "text-bg-warning";
            default -> "text-bg-primary";
        };
    }
}
