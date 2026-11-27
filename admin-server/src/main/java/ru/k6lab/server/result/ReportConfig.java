package ru.k6lab.server.result;

import ru.k6lab.server.run.Run;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Персональные настройки отчёта конкретного прогона (хранятся в run.request.reportConfig):
 * кастомный заголовок, видимость разделов, исключённые графики и параметры отображения по умолчанию.
 */
public record ReportConfig(
        String customTitle,
        Map<String, Boolean> sections,
        List<String> excludedCharts,
        String timeAxis,
        String warmup,
        String sortBy,
        String sortDir
) {
    public static final List<String> SECTION_KEYS = List.of(
            "summary", "config", "env", "steps", "charts", "generators"
    );

    public static final ReportConfig DEFAULT = new ReportConfig(
            "",
            defaultSections(),
            List.of(),
            "",
            "",
            "",
            ""
    );

    private static Map<String, Boolean> defaultSections() {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (String k : SECTION_KEYS) {
            m.put(k, Boolean.TRUE);
        }
        return Map.copyOf(m);
    }

    public boolean show(String sectionKey) {
        if (sections == null || !sections.containsKey(sectionKey)) {
            return true;
        }
        return !Boolean.FALSE.equals(sections.get(sectionKey));
    }

    public boolean isChartIncluded(String chartTitle) {
        if (chartTitle == null || excludedCharts == null || excludedCharts.isEmpty()) {
            return true;
        }
        return !excludedCharts.contains(chartTitle);
    }

    public Boolean defaultAbsTime() {
        if (timeAxis == null || timeAxis.isBlank()) {
            return null;
        }
        String v = timeAxis.trim().toLowerCase(Locale.ROOT);
        if ("abs".equals(v)) return Boolean.TRUE;
        if ("rel".equals(v)) return Boolean.FALSE;
        return null;
    }

    public Boolean defaultSkipWarmup() {
        if (warmup == null || warmup.isBlank()) {
            return null;
        }
        String v = warmup.trim().toLowerCase(Locale.ROOT);
        if ("skip".equals(v) || "after".equals(v) || "true".equals(v)) return Boolean.TRUE;
        if ("include".equals(v) || "all".equals(v) || "false".equals(v)) return Boolean.FALSE;
        return null;
    }

    public ChartStyle.SortBy defaultSortBy() {
        if (sortBy == null || sortBy.isBlank()) {
            return null;
        }
        try {
            return ChartStyle.SortBy.valueOf(sortBy.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public ChartStyle.SortDir defaultSortDir() {
        if (sortDir == null || sortDir.isBlank()) {
            return null;
        }
        try {
            return ChartStyle.SortDir.valueOf(sortDir.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static ReportConfig fromRun(Run run) {
        if (run == null || run.getRequest() == null) {
            return DEFAULT;
        }
        Object raw = run.getRequest().get("reportConfig");
        if (raw instanceof Map<?, ?> map) {
            return fromMap((Map<String, Object>) map);
        }
        return DEFAULT;
    }

    public static ReportConfig fromMap(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return DEFAULT;
        }
        String title = str(raw.get("customTitle"), 240);
        Map<String, Boolean> secs = new LinkedHashMap<>();
        for (String k : SECTION_KEYS) {
            secs.put(k, Boolean.TRUE);
        }
        Object rawSecs = raw.get("sections");
        if (rawSecs instanceof Map<?, ?> sm) {
            for (String k : SECTION_KEYS) {
                Object val = sm.get(k);
                if (val instanceof Boolean b) {
                    secs.put(k, b);
                } else if (val != null) {
                    secs.put(k, Boolean.parseBoolean(val.toString()));
                }
            }
        }
        Set<String> excl = new LinkedHashSet<>();
        Object rawExcl = raw.get("excludedCharts");
        if (rawExcl instanceof Iterable<?> it) {
            for (Object item : it) {
                if (item != null) {
                    String s = item.toString().trim();
                    if (!s.isEmpty() && excl.size() < 200) {
                        excl.add(s);
                    }
                }
            }
        }
        String timeAxis = cleanEnum(raw.get("timeAxis"), Set.of("rel", "abs"), true);
        String warmup = cleanEnum(raw.get("warmup"), Set.of("include", "skip"), true);
        String sortBy = cleanEnum(raw.get("sortBy"), Set.of("NONE", "MAX", "MEAN", "LAST"), false);
        String sortDir = cleanEnum(raw.get("sortDir"), Set.of("DESC", "ASC"), false);
        return new ReportConfig(title, Map.copyOf(secs), List.copyOf(excl), timeAxis, warmup, sortBy, sortDir);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("customTitle", customTitle != null ? customTitle : "");
        Map<String, Boolean> secs = new LinkedHashMap<>();
        for (String k : SECTION_KEYS) {
            secs.put(k, show(k));
        }
        m.put("sections", secs);
        m.put("excludedCharts", excludedCharts != null ? new ArrayList<>(excludedCharts) : List.of());
        m.put("timeAxis", timeAxis != null ? timeAxis : "");
        m.put("warmup", warmup != null ? warmup : "");
        m.put("sortBy", sortBy != null ? sortBy : "");
        m.put("sortDir", sortDir != null ? sortDir : "");
        return m;
    }

    private static String str(Object o, int maxLen) {
        if (o == null) return "";
        String s = o.toString().strip();
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    private static String cleanEnum(Object o, Set<String> allowed, boolean lower) {
        if (o == null) return "";
        String s = o.toString().trim();
        if (s.isEmpty()) return "";
        String norm = lower ? s.toLowerCase(Locale.ROOT) : s.toUpperCase(Locale.ROOT);
        return allowed.contains(norm) ? norm : "";
    }
}
