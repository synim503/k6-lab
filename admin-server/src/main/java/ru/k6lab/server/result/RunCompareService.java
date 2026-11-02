package ru.k6lab.server.result;

import org.springframework.stereotype.Service;
import ru.k6lab.server.run.Run;
import ru.k6lab.server.run.RunRepository;
import ru.k6lab.server.run.RunService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Сравнение двух run по сохранённым итогам (Prometheus не нужен): ступени сопоставляются по уровню нагрузки, затем
 * по операциям считается разница значений. Ухудшение больше {@link #WORSE_PCT} % подсвечивается.
 */
@Service
public class RunCompareService {

    public static final double WORSE_PCT = 10.0;

    /** Разница метрики: delta - в процентах от значения A; worse - ухудшилась более чем на порог. */
    public record Delta(Double a, Double b, Double deltaPct, boolean worse) {
    }

    public record OpCmp(String operation, int slaPercentile, Delta latency, Delta rate, Delta errors, boolean slaOkA, boolean slaOkB) {
    }

    public record StepCmp(double levelPct, String statusA, String statusB, List<OpCmp> ops) {
    }

    public record Comparison(Run a, Run b, List<StepCmp> steps, ResultData.MaxRow maxA, ResultData.MaxRow maxB,
                             boolean sameScenario, List<String> notes) {
        public long worseCount() {
            return steps.stream().flatMap(s -> s.ops().stream())
                    .filter(o -> o.latency().worse() || o.rate().worse() || o.errors().worse()).count();
        }
    }

    private final RunService runService;
    private final RunRepository runs;
    private final StepResultService results;

    public RunCompareService(RunService runService, RunRepository runs, StepResultService results) {
        this.runService = runService;
        this.runs = runs;
        this.results = results;
    }

    public Comparison compare(long aId, long bId) {
        Run a = runService.get(aId);
        Run b = runService.get(bId);
        List<String> notes = new ArrayList<>();
        ResultData da = results.load(aId).orElseThrow(() -> new IllegalStateException("Для run #" + aId + " нет рассчитанных итогов"));
        ResultData db = results.load(bId).orElseThrow(() -> new IllegalStateException("Для run #" + bId + " нет рассчитанных итогов"));
        List<StepCmp> steps = new ArrayList<>();
        for (ResultData.StepRow sa : da.steps()) {
            if ("NOT_RUN".equals(sa.status())) {
                continue;
            }
            ResultData.StepRow sb = db.steps().stream().filter(x -> !"NOT_RUN".equals(x.status()) && Math.abs(x.levelPct() - sa.levelPct()) < 0.05)
                    .findFirst().orElse(null);
            if (sb == null) {
                notes.add("Уровень " + fmt(sa.levelPct()) + "% не выполнялся в run #" + bId + " (нет такой ступени или тест остановлен раньше) — не сравнивается");
                continue;
            }
            List<OpCmp> ops = new ArrayList<>();
            for (ResultData.OpRow oa : sa.ops()) {
                ResultData.OpRow ob = sb.ops().stream().filter(x -> x.operation().equals(oa.operation())).findFirst().orElse(null);
                if (ob == null) {
                    notes.add("Операции «" + oa.operation() + "» нет в run #" + bId);
                    continue;
                }
                Double la = oa.slaValue();
                Double lb = ob.slaValue();
                // время и ошибки: рост = ухудшение; интенсивность: падение = ухудшение
                ops.add(new OpCmp(oa.operation(), oa.slaPercentile(), delta(la, lb, true), delta(oa.actualRate(), ob.actualRate(), false),
                        delta(oa.errorPct(), ob.errorPct(), true), oa.ok(), ob.ok()));
            }
            steps.add(new StepCmp(sa.levelPct(), sa.status(), sb.status(), ops));
        }
        boolean same = a.getScenarioId() != null && a.getScenarioId().equals(b.getScenarioId());
        if (!same) {
            notes.add("Run относятся к разным сценариям: сравнение корректно, только если модели нагрузки сопоставимы");
        }
        return new Comparison(a, b, steps, da.max(), db.max(), same, notes);
    }

    /** Сравнение с эталоном сценария (для автоматического блока на странице нового run). */
    public Optional<Comparison> compareWithBaseline(Run run) {
        if (run.getScenarioId() == null || !run.getStatus().isTerminal()) {
            return Optional.empty();
        }
        Optional<Run> base = runs.findFirstByScenarioIdAndBaselineTrue(run.getScenarioId());
        if (base.isEmpty() || base.get().getId().equals(run.getId()) || results.load(run.getId()).isEmpty()
                || results.load(base.get().getId()).isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(compare(base.get().getId(), run.getId()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public static Delta delta(Double a, Double b, boolean higherIsWorse) {
        if (a == null || b == null) {
            return new Delta(a, b, null, false);
        }
        Double pct;
        if (Math.abs(a) < 1e-9) {
            pct = Math.abs(b) < 1e-9 ? Double.valueOf(0.0) : null; // от нуля процент не определён
        } else {
            pct = (b - a) / Math.abs(a) * 100.0;
        }
        boolean worse;
        if (pct == null) {
            // было ноль, стало больше: для времени и ошибок это ухудшение
            worse = higherIsWorse && b > 0;
        } else {
            worse = higherIsWorse ? pct > WORSE_PCT : pct < -WORSE_PCT;
        }
        return new Delta(a, b, pct, worse);
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
