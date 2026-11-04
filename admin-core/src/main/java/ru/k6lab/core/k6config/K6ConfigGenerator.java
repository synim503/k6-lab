package ru.k6lab.core.k6config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.k6lab.core.k6config.GeneratedConfig.OpPlan;
import ru.k6lab.core.k6config.GeneratedConfig.PreviewRow;
import ru.k6lab.core.k6config.GeneratedConfig.Warning;
import ru.k6lab.core.model.OperationSpec;
import ru.k6lab.core.model.OperationSpec.Mode;
import ru.k6lab.core.profile.StepPlan;
import ru.k6lab.core.profile.StepWindow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Детерминированная генерация конфига k6 (--config) из модели нагрузки и профиля: один scenario на операцию,
 * {@code exec}, stages (разгон и удержание каждой ступени), VU, gracefulStop, env, thresholds из SLA.
 * Одинаковый вход всегда даёт побайтово одинаковый JSON (операции сортируются по имени).
 * <p>
 * Не используется abortOnFail (в распределённом режиме он срабатывал бы на каждом генераторе отдельно);
 * системный тег scenario не переопределяется.
 */
public final class K6ConfigGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern SAFE_FN = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*$");

    private K6ConfigGenerator() {
    }

    public static GeneratedConfig generate(ConfigInput in) {
        List<Warning> warnings = new ArrayList<>();
        List<OperationSpec> ops = in.operations().stream().filter(OperationSpec::enabled)
                .sorted(Comparator.comparing(OperationSpec::name)).toList();
        if (ops.isEmpty()) {
            throw new IllegalArgumentException("В модели нет включённых операций");
        }
        if (in.steps().isEmpty()) {
            throw new IllegalArgumentException("В профиле нет ступеней");
        }
        validate(ops, in);

        List<StepPlan> steps = in.steps();
        List<StepWindow> windows = StepWindow.of(steps);
        int totalSec = StepWindow.totalSec(steps);

        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode sysTags = root.putArray("systemTags");
        ConfigInput.SYSTEM_TAGS.forEach(sysTags::add);
        ArrayNode stats = root.putArray("summaryTrendStats");
        ConfigInput.TREND_STATS.forEach(stats::add);
        ObjectNode scenarios = root.putObject("scenarios");
        ObjectNode thresholds = MAPPER.createObjectNode();

        List<OpPlan> plans = new ArrayList<>();
        for (OperationSpec op : ops) {
            OpPlan plan = op.mode() == Mode.OPEN ? openScenario(op, in, scenarios, warnings)
                    : closedScenario(op, in, scenarios, warnings);
            if (plan != null) {
                plans.add(plan);
                addThresholds(op, in.usesHelper(), thresholds);
            }
        }
        if (plans.isEmpty()) {
            throw new IllegalArgumentException("Все операции имеют нулевую интенсивность - нагрузки не будет");
        }
        root.set("thresholds", thresholds);

        return new GeneratedConfig(root, List.copyOf(warnings), List.copyOf(plans), windows, totalSec,
                preview(plans, steps, windows));
    }

    private static void validate(List<OperationSpec> ops, ConfigInput in) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (OperationSpec op : ops) {
            if (!SAFE_NAME.matcher(op.name()).matches()) {
                throw new IllegalArgumentException("Имя операции «" + op.name()
                        + "»: допустимы латиница, цифры, _ и - (до 64 символов), т.к. имя становится scenario и тегом метрик");
            }
            if (!seen.add(op.name())) {
                throw new IllegalArgumentException("Имя операции «" + op.name() + "» повторяется");
            }
            if (op.execFn() == null || !SAFE_FN.matcher(op.execFn()).matches()) {
                throw new IllegalArgumentException("Операция «" + op.name() + "»: некорректное имя exec-функции");
            }
            if (op.mode() == Mode.CLOSED) {
                if (!in.usesHelper()) {
                    throw new IllegalArgumentException("Операция «" + op.name() + "»: закрытая модель запрещена без admin.js "
                            + "(pacing попал бы во время операции). Используйте op() из __admin/admin.js или откройте модель");
                }
                if (op.pacingMs() == null || op.pacingMs() <= 0) {
                    throw new IllegalArgumentException("Операция «" + op.name() + "»: для закрытой модели нужен pacing > 0");
                }
            }
            if (op.ratePerSec() < 0) {
                throw new IllegalArgumentException("Операция «" + op.name() + "»: интенсивность не может быть отрицательной");
            }
            if (op.mode() == Mode.OPEN && op.expectedDurationSec() <= 0) {
                throw new IllegalArgumentException("Операция «" + op.name() + "»: укажите ожидаемую длительность (нужна для расчёта VU)");
            }
            if (op.slaPercentile() != 90 && op.slaPercentile() != 95 && op.slaPercentile() != 99) {
                throw new IllegalArgumentException("Операция «" + op.name() + "»: перцентиль SLA - 90, 95 или 99");
            }
        }
    }

    // ------------------------------------------------------------------ открытая модель

    private static OpPlan openScenario(OperationSpec op, ConfigInput in, ObjectNode scenarios, List<Warning> warnings) {
        List<StepPlan> steps = in.steps();
        double[] rates = new double[steps.size()];
        for (int i = 0; i < rates.length; i++) {
            rates[i] = op.ratePerSec() * op.levelFor(steps.get(i).levelPct()) / 100.0;
        }
        double peak = 0;
        for (double r : rates) {
            peak = Math.max(peak, r);
        }
        if (peak <= 0) {
            warnings.add(new Warning("ZERO_RATE", op.name(), "Интенсивность операции равна нулю на всех ступенях - scenario не создаётся"));
            return null;
        }
        TimeUnitSelector.Selection sel = TimeUnitSelector.select(rates);
        if (!sel.withinTolerance()) {
            double[] actual = new double[rates.length];
            for (int i = 0; i < rates.length; i++) {
                actual[i] = sel.actualPerSec(i);
            }
            warnings.add(new Warning("ROUNDING", op.name(), String.format(java.util.Locale.ROOT,
                    "Интенсивность нельзя выразить целым rate с ошибкой не более 1%% (максимальная ошибка %.2f%%). "
                            + "Заданная на пике %.4f оп/сек, фактическая %.4f оп/сек", sel.maxErrorPct(), peak,
                    maxOf(actual))));
        }

        long pre = op.preAllocatedOverride() != null ? op.preAllocatedOverride()
                : Math.max(1, ceil(peak * op.expectedDurationSec() * in.preAllocFactor()));
        // k6 делит VU между execution segments целыми числами: если VU меньше числа генераторов, часть сегментов остаётся без VU,
        // их генераторы ничего не выполняют и сразу завершаются (работают только первые). Поэтому минимум - по VU на генератор.
        if (op.preAllocatedOverride() == null) {
            pre = Math.max(pre, Math.max(1, in.generatorCount()));
        }
        long max = op.maxVusOverride() != null ? op.maxVusOverride() : Math.max(pre, pre * Math.round(in.maxVusFactor()));
        if (max < pre) {
            max = pre;
        }

        ObjectNode sc = scenarios.putObject(op.name());
        sc.put("executor", "ramping-arrival-rate");
        sc.put("startRate", steps.get(0).rampSec() == 0 ? sel.targets()[0] : 0);
        sc.put("timeUnit", sel.unitSec() + "s");
        sc.put("preAllocatedVUs", pre);
        sc.put("maxVUs", max);
        ArrayNode stages = sc.putArray("stages");
        for (int i = 0; i < steps.size(); i++) {
            StepPlan s = steps.get(i);
            stages.addObject().put("duration", s.rampSec() + "s").put("target", sel.targets()[i]);
            stages.addObject().put("duration", s.holdSec() + "s").put("target", sel.targets()[i]);
        }
        sc.put("gracefulStop", in.gracefulStopSec() + "s");
        putExec(sc, op);
        putEnvAndTags(sc, op, null);

        // предупреждение: меньше одной итерации за timeUnit на генератор
        if (in.generatorCount() > 1) {
            for (int i = 0; i < sel.targets().length; i++) {
                if (sel.targets()[i] > 0 && sel.targets()[i] < in.generatorCount()) {
                    warnings.add(new Warning("LOW_RATE_PER_GENERATOR", op.name(), "На ступени " + steps.get(i).no()
                            + " на генератор приходится меньше 1 итерации за " + sel.unitSec() + " с ("
                            + sel.targets()[i] + " на " + in.generatorCount() + " генераторов): нагрузка будет неравномерной"));
                    break;
                }
            }
        }
        long[] vus = new long[rates.length];
        double[] actual = new double[rates.length];
        for (int i = 0; i < rates.length; i++) {
            actual[i] = sel.actualPerSec(i);
            vus[i] = ceil(actual[i] * op.expectedDurationSec());
        }
        return new OpPlan(op.name(), Mode.OPEN, sel.unitSec(), sel.targets(), actual, vus, pre, max, peak);
    }

    // ------------------------------------------------------------------ закрытая модель

    private static OpPlan closedScenario(OperationSpec op, ConfigInput in, ObjectNode scenarios, List<Warning> warnings) {
        List<StepPlan> steps = in.steps();
        double pacingSec = op.pacingMs() / 1000.0;
        // VU = интенсивность_в_сек * pacing_сек; либо задано явно (VU на уровне 100%)
        double baseVus = op.vusOverride() != null ? op.vusOverride() : op.ratePerSec() * pacingSec;
        if (baseVus <= 0) {
            warnings.add(new Warning("ZERO_RATE", op.name(), "Нулевая интенсивность/VU - scenario не создаётся"));
            return null;
        }
        if (op.expectedDurationSec() >= pacingSec && op.expectedDurationSec() > 0) {
            warnings.add(new Warning("PACING_TOO_SHORT", op.name(), String.format(java.util.Locale.ROOT,
                    "Ожидаемая длительность операции (%.2f с) не меньше pacing (%.2f с): заданная интенсивность достигнута не будет",
                    op.expectedDurationSec(), pacingSec)));
        }
        long[] vus = new long[steps.size()];
        double[] rates = new double[steps.size()];
        long peakVus = 0;
        for (int i = 0; i < vus.length; i++) {
            double lvl = op.levelFor(steps.get(i).levelPct());
            vus[i] = ceil(baseVus * lvl / 100.0);
            rates[i] = vus[i] / pacingSec;
            peakVus = Math.max(peakVus, vus[i]);
        }
        ObjectNode sc = scenarios.putObject(op.name());
        sc.put("executor", "ramping-vus");
        sc.put("startVUs", steps.get(0).rampSec() == 0 ? vus[0] : 0);
        ArrayNode stages = sc.putArray("stages");
        for (int i = 0; i < steps.size(); i++) {
            StepPlan s = steps.get(i);
            stages.addObject().put("duration", s.rampSec() + "s").put("target", vus[i]);
            stages.addObject().put("duration", s.holdSec() + "s").put("target", vus[i]);
        }
        sc.put("gracefulRampDown", in.gracefulRampDownSec() + "s");
        sc.put("gracefulStop", in.gracefulStopSec() + "s");
        putExec(sc, op);
        putEnvAndTags(sc, op, String.valueOf(op.pacingMs()));
        double peakRate = peakVus / pacingSec;
        return new OpPlan(op.name(), Mode.CLOSED, 1, new long[0], rates, vus, peakVus, peakVus, peakRate);
    }

    /** Для export default function поле exec не задаётся: k6 сам запускает основную функцию. */
    private static void putExec(ObjectNode sc, OperationSpec op) {
        if (!"default".equals(op.execFn())) {
            sc.put("exec", op.execFn());
        }
    }

    private static void putEnvAndTags(ObjectNode sc, OperationSpec op, String pacingMs) {
        Map<String, String> env = new java.util.TreeMap<>(op.env());
        if (pacingMs != null) {
            env.put("PACING_MS", pacingMs);
        }
        if (!env.isEmpty()) {
            ObjectNode e = sc.putObject("env");
            env.forEach(e::put);
        }
        Map<String, String> tags = new java.util.TreeMap<>(op.tags());
        tags.remove("scenario"); // системный тег scenario = операция, не переопределяем
        if (!tags.isEmpty()) {
            ObjectNode t = sc.putObject("tags");
            tags.forEach(t::put);
        }
    }

    // ------------------------------------------------------------------ thresholds

    private static void addThresholds(OperationSpec op, boolean helper, ObjectNode thresholds) {
        String sel = "{scenario:" + op.name() + "}";
        String pct = "p(" + op.slaPercentile() + ")<" + fmt(op.slaThresholdMs());
        double errFraction = op.maxErrorPct() / 100.0;
        if (helper) {
            thresholds.putArray("op_duration" + sel).add(pct);
            thresholds.putArray("op_failed" + sel).add("rate<" + fmt(errFraction));
        } else {
            thresholds.putArray("iteration_duration" + sel).add(pct);
            if (op.failureSource() == OperationSpec.FailureSource.CHECKS) {
                thresholds.putArray("checks" + sel).add("rate>" + fmt(1 - errFraction));
            } else {
                thresholds.putArray("http_req_failed" + sel).add("rate<" + fmt(errFraction));
            }
        }
    }

    // ------------------------------------------------------------------ превью

    private static List<PreviewRow> preview(List<OpPlan> plans, List<StepPlan> steps, List<StepWindow> windows) {
        List<PreviewRow> rows = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Double> rate = new LinkedHashMap<>();
            Map<String, Long> vus = new LinkedHashMap<>();
            double total = 0;
            long totalVus = 0;
            for (OpPlan p : plans) {
                rate.put(p.name(), p.ratesPerSec()[i]);
                long v = p.mode() == Mode.OPEN ? p.vusPerStep()[i] : p.vusPerStep()[i];
                vus.put(p.name(), v);
                total += p.ratesPerSec()[i];
                totalVus += v;
            }
            StepWindow w = windows.get(i);
            rows.add(new PreviewRow(steps.get(i).no(), steps.get(i).label(), steps.get(i).levelPct(), w.rampStartSec(),
                    w.holdStartSec(), w.holdEndSec(), rate, vus, total, totalVus));
        }
        return rows;
    }

    // ------------------------------------------------------------------ утилиты

    static long ceil(double v) {
        return (long) Math.ceil(v - 1e-9);
    }

    private static double maxOf(double[] a) {
        double m = 0;
        for (double v : a) {
            m = Math.max(m, v);
        }
        return m;
    }

    /** Компактное число без научной нотации и лишних нулей. */
    static String fmt(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return new java.math.BigDecimal(v).round(new java.math.MathContext(10)).stripTrailingZeros().toPlainString();
    }

    /** Сериализация для хранения/сравнения. */
    public static String toJson(GeneratedConfig cfg) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(cfg.config());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
