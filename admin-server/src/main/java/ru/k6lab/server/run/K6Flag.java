package ru.k6lab.server.run;

import java.util.ArrayList;
import java.util.List;

/**
 * Флаги командной строки {@code k6 run}, которые можно включить для конкретного запуска (отладка, упрощение, отключение
 * проверок). Только из этого списка: произвольные аргументы на агент не передаются.
 */
public enum K6Flag {

    HTTP_DEBUG("--http-debug", "HTTP-отладка: заголовки",
            "Печатает в лог каждый HTTP-запрос и ответ (метод, URL, заголовки, статус). Строк очень много: включайте на малой нагрузке, "
                    + "иначе лог упрётся в лимит строк в секунду и часть строк будет отброшена.", true, false),
    HTTP_DEBUG_FULL("--http-debug=full", "HTTP-отладка: заголовки и тела",
            "То же, но с телами запросов и ответов. Удобно для разбора ошибок авторизации и формата данных; "
                    + "тела могут содержать секреты, они попадут в лог запуска.", true, false),
    VERBOSE("--verbose", "Подробный лог k6",
            "Включает уровень debug у самого k6: выбор executor-ов, запуск VU, внутренние предупреждения.", false, false),
    THROW("--throw", "Ошибки HTTP как исключения",
            "Неудачный HTTP-запрос (например, таймаут) выбрасывает исключение и прерывает итерацию, а не возвращает ответ с кодом 0. "
                    + "Помогает быстро увидеть, где именно скрипт ломается.", false, false),
    INSECURE_TLS("--insecure-skip-tls-verify", "Не проверять TLS-сертификаты",
            "Принимает любые сертификаты: самоподписанные, просроченные, с неверным именем. Нужно для стендов без нормального сертификата.",
            false, false),
    NO_CONN_REUSE("--no-connection-reuse", "Новое соединение на каждый запрос",
            "Отключает keep-alive: каждый запрос открывает TCP/TLS-соединение заново. Нагрузка на сервер и генератор заметно выше.",
            false, true),
    NO_VU_CONN_REUSE("--no-vu-connection-reuse", "Не держать соединения между итерациями",
            "Соединения живут только внутри одной итерации и закрываются в её конце. Мягче, чем «новое соединение на каждый запрос».",
            false, true),
    DISCARD_BODIES("--discard-response-bodies", "Не читать тела ответов",
            "k6 не сохраняет тела ответов: меньше памяти и CPU на генераторе. Проверки по телу (check, JSON.parse(res.body)) работать не будут.",
            false, true),
    NO_SETUP("--no-setup", "Пропустить setup()",
            "Не выполнять функцию setup(). Данные из setup() в скрипт не придут.", false, true),
    NO_TEARDOWN("--no-teardown", "Пропустить teardown()",
            "Не выполнять функцию teardown() после теста.", false, true),
    NO_THRESHOLDS("--no-thresholds", "Не проверять thresholds k6",
            "Пороги из options.thresholds скрипта не проверяются и не дают код выхода 99. SLA, заданные в админке, считаются отдельно и не отключаются.",
            false, true);

    private final String arg;
    private final String title;
    private final String description;
    private final boolean httpDebug;
    private final boolean changesLoad;

    K6Flag(String arg, String title, String description, boolean httpDebug, boolean changesLoad) {
        this.arg = arg;
        this.title = title;
        this.description = description;
        this.httpDebug = httpDebug;
        this.changesLoad = changesLoad;
    }

    public String getArg() {
        return arg;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    /** Один из режимов --http-debug: выбираются через список, а не флажками. */
    public boolean isHttpDebug() {
        return httpDebug;
    }

    /** Флаг меняет поведение скрипта или нагрузку: результаты нельзя сравнивать с обычным запуском. */
    public boolean isChangesLoad() {
        return changesLoad;
    }

    /** Идентификаторы из формы -> список флагов; неизвестное значение - ошибка, «full» побеждает обычный http-debug. */
    public static List<K6Flag> parse(List<String> ids) {
        List<K6Flag> out = new ArrayList<>();
        if (ids == null) {
            return out;
        }
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            try {
                K6Flag f = valueOf(id.trim());
                if (!out.contains(f)) {
                    out.add(f);
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Неизвестный флаг k6: " + id);
            }
        }
        if (out.contains(HTTP_DEBUG_FULL)) {
            out.remove(HTTP_DEBUG);
        }
        return out;
    }

    /** Аргументы командной строки для сохранённого в запуске списка идентификаторов (неизвестные пропускаются). */
    public static List<String> args(List<?> ids) {
        List<String> out = new ArrayList<>();
        if (ids == null) {
            return out;
        }
        List<String> names = ids.stream().map(String::valueOf).toList();
        for (K6Flag f : parse(names.stream().filter(n -> {
            try {
                valueOf(n);
                return true;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }).toList())) {
            out.add(f.arg);
        }
        return out;
    }
}
