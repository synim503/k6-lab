package ru.k6lab.core.script;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Статический разбор k6-скрипта: список операций (эвристика), использование admin.js, предупреждения,
 * запасная проверка опций (когда k6 недоступен на сервере админки).
 */
public final class ScriptAnalyzer {

    /** Экспорты, которые не являются операциями. */
    public static final Set<String> NOT_OPERATIONS = Set.of("default", "setup", "teardown", "handleSummary", "options");

    /** Опции, которые перекрыли бы модель из UI: опции скрипта приоритетнее --config. */
    public static final List<String> BLOCKING_OPTIONS = List.of("scenarios", "stages", "vus", "duration", "iterations");

    private static final Pattern EXPORT_FUNCTION = Pattern.compile("\\bexport\\s+(?:async\\s+)?function\\s*\\*?\\s*([A-Za-z_$][\\w$]*)");
    private static final Pattern EXPORT_CONST_FN = Pattern.compile(
            "\\bexport\\s+(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(?:async\\s+)?(?:function\\b|\\([^)]*\\)\\s*=>|[A-Za-z_$][\\w$]*\\s*=>)");
    private static final Pattern EXPORT_LIST = Pattern.compile("\\bexport\\s*\\{([^}]*)\\}");
    private static final Pattern HELPER_IMPORT = Pattern.compile(
            "(?:from\\s*|require\\s*\\(\\s*|import\\s*\\(?\\s*)['\"]\\.{1,2}/(?:[^'\"]*/)?__admin/admin\\.js['\"]");
    private static final Pattern SLEEP_CALL = Pattern.compile("\\bsleep\\s*\\(");
    private static final Pattern K6_CHECK_IMPORT = Pattern.compile("import\\s*\\{[^}]*\\bcheck\\b[^}]*}\\s*from\\s*['\"]k6['\"]");
    private static final Pattern DYNAMIC_URL = Pattern.compile(
            "\\bhttp\\.(?:get|post|put|patch|del|delete|head|options|request)\\s*\\(\\s*(?:[^,)]*,\\s*)?`[^`]*\\$\\{");

    private ScriptAnalyzer() {
    }

    public record Operation(String name, String origin) {
        public static final String HEURISTIC = "HEURISTIC";
        public static final String MANUAL = "MANUAL";
    }

    public record Analysis(List<Operation> operations, boolean usesHelper, List<String> warnings) {
    }

    public static Analysis analyze(String source) {
        String code = stripComments(source);
        Map<String, Operation> ops = new LinkedHashMap<>();
        collect(EXPORT_FUNCTION, code, 1, ops);
        collect(EXPORT_CONST_FN, code, 1, ops);
        Matcher list = EXPORT_LIST.matcher(code);
        while (list.find()) {
            for (String part : list.group(1).split(",")) {
                String p = part.trim();
                if (p.isEmpty()) {
                    continue;
                }
                String exported = p.contains(" as ") ? p.substring(p.indexOf(" as ") + 4).trim() : p;
                if (exported.matches("[A-Za-z_$][\\w$]*") && !NOT_OPERATIONS.contains(exported)) {
                    ops.putIfAbsent(exported, new Operation(exported, Operation.HEURISTIC));
                }
            }
        }
        boolean helper = HELPER_IMPORT.matcher(code).find();
        List<String> warnings = new ArrayList<>();
        if (SLEEP_CALL.matcher(code).find()) {
            warnings.add("В скрипте есть sleep(): в открытой модели не регулируйте нагрузку sleep, а в закрытой используйте pacing "
                    + "(op() из admin.js делает его сам).");
        }
        if (helper && K6_CHECK_IMPORT.matcher(code).find()) {
            warnings.add("check импортирован из 'k6': op() не увидит его провалы. Импортируйте check из './__admin/admin.js'.");
        }
        if (DYNAMIC_URL.matcher(code).find() && !code.contains("name:") && !code.contains("name :")) {
            warnings.add("Запросы с динамическими URL без тега name: вырастет кардинальность метрик. Добавьте tags: { name: '...' }.");
        }
        return new Analysis(new ArrayList<>(ops.values()), helper, warnings);
    }

    private static void collect(Pattern p, String code, int group, Map<String, Operation> ops) {
        Matcher m = p.matcher(code);
        while (m.find()) {
            String name = m.group(group);
            if (!NOT_OPERATIONS.contains(name)) {
                ops.putIfAbsent(name, new Operation(name, Operation.HEURISTIC));
            }
        }
    }

    /**
     * Запасная проверка (если k6 недоступен): ищет блокирующие ключи внутри {@code export const options = {...}}.
     * Консервативно: ключ на любой глубине объекта options считается нарушением.
     */
    public static List<String> blockedOptionKeys(String source) {
        String code = stripComments(source);
        Matcher m = Pattern.compile("\\bexport\\s+(?:const|let|var)\\s+options\\s*=\\s*\\{").matcher(code);
        Set<String> found = new LinkedHashSet<>();
        while (m.find()) {
            int start = m.end() - 1;
            int depth = 0;
            int end = start;
            for (int i = start; i < code.length(); i++) {
                char c = code.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        end = i;
                        break;
                    }
                }
            }
            String body = code.substring(start, Math.max(start, end));
            for (String key : BLOCKING_OPTIONS) {
                if (Pattern.compile("(?:^|[{,\\s])['\"]?" + key + "['\"]?\\s*:").matcher(body).find()) {
                    found.add(key);
                }
            }
        }
        return new ArrayList<>(found);
    }

    /** Удаляет однострочные и блочные комментарии, не трогая строки и шаблонные литералы. Длина сохраняется пробелами. */
    static String stripComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                out.append("  ");
                i += 2;
                while (i < n && !(s.charAt(i) == '*' && i + 1 < n && s.charAt(i + 1) == '/')) {
                    out.append(s.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n) {
                    out.append("  ");
                    i += 2;
                }
            } else if (c == '\'' || c == '"' || c == '`') {
                char q = c;
                out.append(c);
                i++;
                while (i < n) {
                    char d = s.charAt(i);
                    out.append(d);
                    i++;
                    if (d == '\\' && i < n) {
                        out.append(s.charAt(i));
                        i++;
                    } else if (d == q) {
                        break;
                    }
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
