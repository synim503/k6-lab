package ru.k6lab.server.metrics;

import org.springframework.stereotype.Component;
import ru.k6lab.server.config.AppProperties;
import ru.k6lab.server.run.RunGate;

import java.util.Optional;

/** Запуск тестов запрещён, пока не пройдена диагностика Prometheus для текущих параметров (можно отключить в конфигурации). */
@Component
public class DiagnosticsGate implements RunGate {

    private final PrometheusDiagnostics diagnostics;
    private final AppProperties props;

    public DiagnosticsGate(PrometheusDiagnostics diagnostics, AppProperties props) {
        this.diagnostics = diagnostics;
        this.props = props;
    }

    @Override
    public Optional<String> blockReason() {
        if (!props.isRequireDiagnostics() || diagnostics.passed()) {
            return Optional.empty();
        }
        return Optional.of("Не пройдена диагностика Prometheus. Откройте «Настройки → Prometheus» и нажмите «Проверить»: "
                + "без работающих метрик результаты нагрузочного теста не имеют смысла.");
    }
}
