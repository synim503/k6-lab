package ru.k6lab.core.util;

import java.math.BigInteger;
import java.util.Comparator;

/**
 * Естественная (числобуквенная) сортировка строк с поддержкой кириллицы и латиницы без учёта регистра:
 * «20%» < «100%», «Ступень 2» < «Ступень 10», «v1.2» < «v1.10».
 */
public final class NaturalSort {

    public static final Comparator<String> COMPARATOR = NaturalSort::compare;

    private NaturalSort() {
    }

    public static int compare(String a, String b) {
        if (a == b) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        int la = a.length();
        int lb = b.length();
        int ia = 0;
        int ib = 0;

        while (ia < la && ib < lb) {
            char ca = a.charAt(ia);
            char cb = b.charAt(ib);

            if (isAsciiDigit(ca) && isAsciiDigit(cb)) {
                int sa = ia;
                while (ia < la && isAsciiDigit(a.charAt(ia))) {
                    ia++;
                }
                int sb = ib;
                while (ib < lb && isAsciiDigit(b.charAt(ib))) {
                    ib++;
                }
                String na = a.substring(sa, ia);
                String nb = b.substring(sb, ib);
                int cmp = compareNumericChunks(na, nb);
                if (cmp != 0) {
                    return cmp;
                }
                // При равном числовом значении ("02" vs "2") более короткая запись идёт раньше
                if (na.length() != nb.length()) {
                    return Integer.compare(na.length(), nb.length());
                }
            } else {
                char ua = foldCase(ca);
                char ub = foldCase(cb);
                if (ua != ub) {
                    return Character.compare(ua, ub);
                }
                ia++;
                ib++;
            }
        }
        if (ia != la || ib != lb) {
            return Integer.compare(la - ia, lb - ib);
        }
        return a.compareTo(b);
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static char foldCase(char c) {
        char lo = Character.toLowerCase(Character.toUpperCase(c));
        return lo == 'ё' ? 'е' : lo;
    }

    private static int compareNumericChunks(String a, String b) {
        String ta = stripLeadingZeros(a);
        String tb = stripLeadingZeros(b);
        if (ta.length() != tb.length()) {
            return Integer.compare(ta.length(), tb.length());
        }
        if (ta.length() <= 18) {
            return Long.compare(Long.parseLong(ta), Long.parseLong(tb));
        }
        return new BigInteger(ta).compareTo(new BigInteger(tb));
    }

    private static String stripLeadingZeros(String s) {
        int i = 0;
        while (i < s.length() - 1 && s.charAt(i) == '0') {
            i++;
        }
        return i == 0 ? s : s.substring(i);
    }
}
