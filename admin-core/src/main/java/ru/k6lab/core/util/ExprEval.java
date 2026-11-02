package ru.k6lab.core.util;

import java.util.Map;

/**
 * Безопасный вычислитель арифметических выражений для параметризованных шаблонов профилей (без исполнения кода).
 * Поддерживает + - * / ^, скобки, унарный минус, переменные, функции min, max, round, ceil, floor, abs.
 */
public final class ExprEval {

    private final String src;
    private final Map<String, Double> vars;
    private int pos;

    private ExprEval(String src, Map<String, Double> vars) {
        this.src = src;
        this.vars = vars;
    }

    public static double eval(String expression, Map<String, Double> variables) {
        ExprEval e = new ExprEval(expression, variables);
        double v = e.parseExpr();
        e.skipWs();
        if (e.pos != e.src.length()) {
            throw e.error("лишние символы");
        }
        return v;
    }

    private IllegalArgumentException error(String msg) {
        return new IllegalArgumentException("Ошибка в выражении «" + src + "» (позиция " + pos + "): " + msg);
    }

    private void skipWs() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private boolean eat(char c) {
        skipWs();
        if (pos < src.length() && src.charAt(pos) == c) {
            pos++;
            return true;
        }
        return false;
    }

    private double parseExpr() {
        double v = parseTerm();
        while (true) {
            if (eat('+')) {
                v += parseTerm();
            } else if (eat('-')) {
                v -= parseTerm();
            } else {
                return v;
            }
        }
    }

    private double parseTerm() {
        double v = parsePower();
        while (true) {
            if (eat('*')) {
                v *= parsePower();
            } else if (eat('/')) {
                double d = parsePower();
                if (d == 0) {
                    throw error("деление на ноль");
                }
                v /= d;
            } else {
                return v;
            }
        }
    }

    private double parsePower() {
        double base = parseUnary();
        if (eat('^')) {
            return Math.pow(base, parsePower());
        }
        return base;
    }

    private double parseUnary() {
        if (eat('-')) {
            return -parseUnary();
        }
        if (eat('+')) {
            return parseUnary();
        }
        return parsePrimary();
    }

    private double parsePrimary() {
        skipWs();
        if (eat('(')) {
            double v = parseExpr();
            if (!eat(')')) {
                throw error("ожидалась )");
            }
            return v;
        }
        if (pos >= src.length()) {
            throw error("неожиданный конец выражения");
        }
        char c = src.charAt(pos);
        if (Character.isDigit(c) || c == '.') {
            int start = pos;
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
                pos++;
            }
            return Double.parseDouble(src.substring(start, pos));
        }
        if (Character.isLetter(c) || c == '_') {
            int start = pos;
            while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
                pos++;
            }
            String name = src.substring(start, pos);
            if (eat('(')) {
                double a = parseExpr();
                double b = Double.NaN;
                if (eat(',')) {
                    b = parseExpr();
                }
                if (!eat(')')) {
                    throw error("ожидалась )");
                }
                return switch (name) {
                    case "min" -> Math.min(a, req(b));
                    case "max" -> Math.max(a, req(b));
                    case "round" -> Math.round(a);
                    case "ceil" -> Math.ceil(a);
                    case "floor" -> Math.floor(a);
                    case "abs" -> Math.abs(a);
                    default -> throw error("неизвестная функция " + name);
                };
            }
            Double v = vars.get(name);
            if (v == null) {
                throw error("неизвестная переменная " + name);
            }
            return v;
        }
        throw error("неожиданный символ «" + c + "»");
    }

    private double req(double b) {
        if (Double.isNaN(b)) {
            throw error("функции нужно два аргумента");
        }
        return b;
    }
}
