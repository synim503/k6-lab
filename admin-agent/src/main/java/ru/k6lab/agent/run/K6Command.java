package ru.k6lab.agent.run;

import ru.k6lab.core.protocol.Messages;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Построение командной строки и окружения k6 из PREPARE (без запуска - чтобы тестировать отдельно). */
public final class K6Command {

    private static final Pattern SECRET_REF = Pattern.compile("\\$\\{secret:([A-Za-z0-9_.-]+)}");

    private K6Command() {
    }

    public static List<String> args(Path k6, Messages.Prepare p, Path config, Path summary, int apiPort) {
        List<String> a = new ArrayList<>();
        a.add(k6.toString());
        a.add("run");
        a.add("--quiet");
        a.add("--no-color");
        a.add("--config");
        a.add(config.toString());
        if (p.executionSegment() != null && !p.executionSegment().isBlank() && !p.executionSegment().equals("0:1")) {
            a.add("--execution-segment");
            a.add(p.executionSegment());
            if (p.executionSegmentSequence() != null && !p.executionSegmentSequence().isBlank()) {
                a.add("--execution-segment-sequence");
                a.add(p.executionSegmentSequence());
            }
        }
        if (p.prometheus() != null && p.prometheus().url() != null && !p.prometheus().url().isBlank()) {
            a.add("--out");
            a.add("experimental-prometheus-rw");
        }
        if (p.tags() != null) {
            for (Map.Entry<String, String> t : new java.util.TreeMap<>(p.tags()).entrySet()) {
                // системный тег scenario (= операция) не переопределяем
                if (t.getKey().equals("scenario") || t.getKey().isBlank()) {
                    continue;
                }
                a.add("--tag");
                a.add(t.getKey() + "=" + t.getValue());
            }
        }
        if (p.k6Flags() != null) {
            for (String f : p.k6Flags()) {
                if (f != null && f.startsWith("--") && f.chars().noneMatch(Character::isWhitespace)) {
                    a.add(f);
                }
            }
        }
        a.add("--address");
        a.add("127.0.0.1:" + apiPort);
        a.add("--summary-export");
        a.add(summary.toString());
        a.add(p.entrypoint());
        return a;
    }

    /** Окружение процесса: env сценария (секреты подставлены) + параметры remote write в Prometheus. */
    public static Map<String, String> environment(Messages.Prepare p, Map<String, String> resolvedEnv) {
        Map<String, String> env = new LinkedHashMap<>(resolvedEnv);
        env.put("K6_NO_USAGE_REPORT", "true");
        env.put("K6_NO_VERSION_CHECK", "true");
        // у генераторов нет интернета: k6 не должен пытаться докачивать расширения (зависание на старте)
        env.put("K6_AUTO_EXTENSION_RESOLUTION", "false");
        Messages.PrometheusParams pr = p.prometheus();
        if (pr != null && pr.url() != null && !pr.url().isBlank()) {
            env.put("K6_PROMETHEUS_RW_SERVER_URL", pr.url());
            env.put("K6_PROMETHEUS_RW_TREND_AS_NATIVE_HISTOGRAM", String.valueOf(pr.trendAsNativeHistogram()));
            env.put("K6_PROMETHEUS_RW_PUSH_INTERVAL", pr.pushInterval() == null ? "5s" : pr.pushInterval());
            if (pr.headers() != null) {
                pr.headers().forEach((k, v) -> env.put("K6_PROMETHEUS_RW_HEADERS_" + k, v));
            }
        }
        return env;
    }

    /** Подстановка ${secret:NAME}; неизвестный секрет - ошибка (PREPARE_FAILED). */
    public static Map<String, String> resolveSecrets(Map<String, String> env, Map<String, String> secrets) {
        Map<String, String> out = new LinkedHashMap<>();
        if (env == null) {
            return out;
        }
        for (Map.Entry<String, String> e : env.entrySet()) {
            Matcher m = SECRET_REF.matcher(e.getValue() == null ? "" : e.getValue());
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String v = secrets == null ? null : secrets.get(m.group(1));
                if (v == null) {
                    throw new IllegalArgumentException("Секрет " + m.group(1) + " не передан агенту (env " + e.getKey() + ")");
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(v));
            }
            m.appendTail(sb);
            out.put(e.getKey(), sb.toString());
        }
        return out;
    }
}
