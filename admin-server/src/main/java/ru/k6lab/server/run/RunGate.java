package ru.k6lab.server.run;

import java.util.Optional;

/** Предусловие запуска тестов (например, успешная диагностика Prometheus). Любой gate может заблокировать запуск. */
public interface RunGate {

    /** Причина блокировки либо empty, если запуск разрешён. */
    Optional<String> blockReason();
}
