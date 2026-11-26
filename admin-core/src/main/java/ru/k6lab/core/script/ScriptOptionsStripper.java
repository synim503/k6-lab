package ru.k6lab.core.script;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Вырезает из {@code export const options = {...}} скрипта поля, которые админка задаёт сама (сценарии, пороги, теги и т.д.).
 * Остальные опции (insecureSkipTLSVerify, userAgent, batch и т.п.) остаются как есть. Если объект опций после этого пуст,
 * выражение заменяется комментарием. Разбор статический: если options заданы не литералом объекта
 * ({@code export const options = getOptions()}), исходник не меняется.
 */
public final class ScriptOptionsStripper {

    /** Опции, которые админка подставляет при запуске через --config (опции скрипта имели бы приоритет над ними). */
    public static final Set<String> MANAGED_KEYS = Set.of("scenarios", "stages", "vus", "duration", "iterations",
            "thresholds", "tags", "systemTags", "summaryTrendStats");

    private static final Pattern OPTIONS_DECL = Pattern.compile(
            "\\bexport\\s+(?:const|let|var)\\s+options\\s*(?::[^=;{]+)?=\\s*\\{");
    private static final Pattern KEY = Pattern.compile("^\\s*(?:(['\"])(.*?)\\1|([A-Za-z_$][\\w$]*))\\s*(?::|,|$)", Pattern.DOTALL);

    private ScriptOptionsStripper() {
    }

    /** @param source новый исходник; @param removed имена вырезанных опций (пусто - исходник не менялся) */
    public record Result(String source, List<String> removed) {
        public boolean changed() {
            return !removed.isEmpty();
        }
    }

    public static Result strip(String src) {
        String masked = mask(src);
        Matcher m = OPTIONS_DECL.matcher(masked);
        if (!m.find()) {
            return new Result(src, List.of());
        }
        int open = m.end() - 1;
        int close = matching(masked, open);
        if (close < 0) {
            return new Result(src, List.of());
        }
        // свойства верхнего уровня: границы по запятым вне вложенных скобок
        List<int[]> parts = new ArrayList<>();
        int depth = 0;
        int from = open + 1;
        for (int i = open + 1; i < close; i++) {
            char c = masked.charAt(i);
            if (c == '{' || c == '[' || c == '(') {
                depth++;
            } else if (c == '}' || c == ']' || c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(new int[]{from, i});
                from = i + 1;
            }
        }
        parts.add(new int[]{from, close});

        List<String> removed = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        for (int[] p : parts) {
            String seg = src.substring(p[0], p[1]);
            if (masked.substring(p[0], p[1]).isBlank()) {
                continue; // хвостовая запятая или пустота
            }
            // ключ ищем после возможных комментариев перед свойством
            Matcher k = KEY.matcher(stripLeadingNoise(src, masked, p));
            String key = null;
            if (k.find()) {
                key = k.group(2) != null ? k.group(2) : k.group(3);
            }
            if (key != null && MANAGED_KEYS.contains(key)) {
                removed.add(key);
            } else {
                kept.add(seg);
            }
        }
        if (removed.isEmpty()) {
            return new Result(src, List.of());
        }
        int stmtEnd = close + 1;
        while (stmtEnd < src.length() && (src.charAt(stmtEnd) == ' ' || src.charAt(stmtEnd) == '\t')) {
            stmtEnd++;
        }
        if (stmtEnd < src.length() && src.charAt(stmtEnd) == ';') {
            stmtEnd++;
        }
        String replacement;
        if (kept.isEmpty()) {
            // сохраняем биндинг options и используем блочный комментарий, чтобы не закомментировать код на той же строке
            replacement = "export const options = {}; /* options (сценарии, пороги, теги) заданы в админке и подставляются при запуске */";
        } else {
            replacement = src.substring(m.start(), open + 1) + String.join(",", kept) + (kept.get(kept.size() - 1).endsWith("\n") ? "" : "\n") + "}"
                    + src.substring(close + 1, stmtEnd);
        }
        return new Result(src.substring(0, m.start()) + replacement + src.substring(stmtEnd), List.copyOf(removed));
    }

    /** Текст сегмента без ведущих комментариев (они в masked уже пробелы) - по исходнику, но с ключом на том же месте. */
    private static String stripLeadingNoise(String src, String masked, int[] p) {
        int i = p[0];
        while (i < p[1] && Character.isWhitespace(masked.charAt(i))) {
            i++;
        }
        return src.substring(i, p[1]);
    }

    /** Индекс парной закрывающей скобки или -1. */
    private static int matching(String masked, int open) {
        int depth = 0;
        for (int i = open; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Копия исходника той же длины: содержимое комментариев, строк, шаблонных литералов и RegExp-литералов
     * заменено пробелами (кавычки и вложенные {@code ${...}} выражения в шаблонах корректно отслеживаются).
     */
    static String mask(String s) {
        return scanJs(s, true);
    }

    /**
     * Общий JS-лексер: всегда заменяет комментарии пробелами; при {@code maskLiterals=true} также маскирует
     * содержимое строковых, шаблонных и RegExp-литералов, сохраняя длину строки и переводы строк.
     */
    static String scanJs(String s, boolean maskLiterals) {
        StringBuilder out = new StringBuilder(s.length());
        int n = s.length();
        int i = 0;
        // стек глубин фигурных скобок внутри вложенных выражений ${ ... } шаблонных литералов
        java.util.ArrayDeque<Integer> tplBraces = new java.util.ArrayDeque<>();
        int lastSigIdx = -1;
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
            } else if (c == '\'' || c == '"') {
                out.append(c);
                i++;
                while (i < n && s.charAt(i) != c) {
                    if (s.charAt(i) == '\\' && i + 1 < n) {
                        if (maskLiterals) {
                            out.append("  ");
                        } else {
                            out.append(s.charAt(i)).append(s.charAt(i + 1));
                        }
                        i += 2;
                        continue;
                    }
                    char ch = s.charAt(i);
                    out.append(maskLiterals ? (ch == '\n' ? '\n' : ' ') : ch);
                    i++;
                }
                if (i < n) {
                    out.append(c);
                    i++;
                }
                lastSigIdx = out.length() - 1;
            } else if (c == '`') {
                out.append('`');
                i = scanTemplatePart(s, i + 1, out, tplBraces, maskLiterals);
                lastSigIdx = out.length() - 1;
            } else if (c == '/' && canStartRegex(s, out, lastSigIdx)) {
                out.append('/');
                i++;
                boolean inClass = false;
                while (i < n) {
                    char ch = s.charAt(i);
                    if (ch == '\\' && i + 1 < n) {
                        if (maskLiterals) {
                            out.append("  ");
                        } else {
                            out.append(ch).append(s.charAt(i + 1));
                        }
                        i += 2;
                        continue;
                    }
                    if (ch == '\n') {
                        out.append('\n');
                        i++;
                        break;
                    }
                    if (ch == '[') {
                        inClass = true;
                    } else if (ch == ']') {
                        inClass = false;
                    } else if (ch == '/' && !inClass) {
                        out.append('/');
                        i++;
                        while (i < n && Character.isLetter(s.charAt(i))) {
                            out.append(s.charAt(i));
                            i++;
                        }
                        break;
                    }
                    out.append(maskLiterals ? ' ' : ch);
                    i++;
                }
                lastSigIdx = out.length() - 1;
            } else {
                if (c == '{' && !tplBraces.isEmpty()) {
                    tplBraces.push(tplBraces.pop() + 1);
                } else if (c == '}' && !tplBraces.isEmpty()) {
                    int depth = tplBraces.pop();
                    if (depth == 0) {
                        // конец выражения ${ ... } — возвращаемся в тело шаблонного литерала
                        out.append(maskLiterals ? ' ' : '}');
                        i = scanTemplatePart(s, i + 1, out, tplBraces, maskLiterals);
                        lastSigIdx = out.length() - 1;
                        continue;
                    }
                    tplBraces.push(depth - 1);
                }
                out.append(c);
                if (!Character.isWhitespace(c)) {
                    lastSigIdx = out.length() - 1;
                }
                i++;
            }
        }
        return out.toString();
    }

    private static int scanTemplatePart(String s, int i, StringBuilder out, java.util.ArrayDeque<Integer> tplBraces, boolean maskLiterals) {
        int n = s.length();
        while (i < n) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < n) {
                if (maskLiterals) {
                    out.append("  ");
                } else {
                    out.append(ch).append(s.charAt(i + 1));
                }
                i += 2;
                continue;
            }
            if (ch == '$' && i + 1 < n && s.charAt(i + 1) == '{') {
                out.append(maskLiterals ? "  " : "${");
                tplBraces.push(0);
                return i + 2;
            }
            if (ch == '`') {
                out.append('`');
                return i + 1;
            }
            out.append(maskLiterals ? (ch == '\n' ? '\n' : ' ') : ch);
            i++;
        }
        return i;
    }

    private static boolean canStartRegex(String s, StringBuilder out, int lastSigIdx) {
        if (lastSigIdx < 0) {
            return true;
        }
        char prev = out.charAt(lastSigIdx);
        if ("([{:;,=!?&|+-*%^~<>".indexOf(prev) >= 0) {
            return true;
        }
        // ключевые слова, после которых может начинаться RegExp-литерал (return /x/, case /x/, typeof /x/ и т.д.)
        if (Character.isJavaIdentifierPart(prev)) {
            int start = lastSigIdx;
            while (start > 0 && Character.isJavaIdentifierPart(out.charAt(start - 1))) {
                start--;
            }
            String word = out.substring(start, lastSigIdx + 1);
            return word.equals("return") || word.equals("case") || word.equals("throw")
                    || word.equals("in") || word.equals("of") || word.equals("typeof")
                    || word.equals("void") || word.equals("delete") || word.equals("yield")
                    || word.equals("await") || word.equals("else");
        }
        return false;
    }
}
