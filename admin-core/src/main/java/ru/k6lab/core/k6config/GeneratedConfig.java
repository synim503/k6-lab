package ru.k6lab.core.k6config;

import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.k6lab.core.model.OperationSpec;
import ru.k6lab.core.profile.StepWindow;

import java.util.List;
import java.util.Map;

/** Результат генерации: конфиг k6, предупреждения и данные для превью. */
public record GeneratedConfig(ObjectNode config, List<Warning> warnings, List<OpPlan> operations,
                              List<StepWindow> windows, int totalSec, List<PreviewRow> preview) {

    public record Warning(String code, String operation, String message) {
    }

    /**
     * План операции. targets - цели rate в единицах timeUnit по ступеням (открытая модель);
     * vusPerStep - VU по ступеням (закрытая) либо оценка VU по закону Литтла (открытая);
     * ratesPerSec - фактическая интенсивность ступени после округления.
     */
    public record OpPlan(String name, OperationSpec.Mode mode, int timeUnitSec, long[] targets, double[] ratesPerSec,
                         long[] vusPerStep, long preAllocatedVus, long maxVus, double peakRatePerSec) {
    }

    /** Строка таблицы ступеней в превью. */
    public record PreviewRow(int stepNo, String label, double levelPct, int rampStartSec, int holdStartSec,
                             int holdEndSec, Map<String, Double> ratePerSec, Map<String, Long> vus,
                             double totalRatePerSec, long totalVus) {
    }

    public double totalPeakRatePerSec() {
        return preview.stream().mapToDouble(PreviewRow::totalRatePerSec).max().orElse(0);
    }

    public long totalPeakVus() {
        return preview.stream().mapToLong(PreviewRow::totalVus).max().orElse(0);
    }
}
