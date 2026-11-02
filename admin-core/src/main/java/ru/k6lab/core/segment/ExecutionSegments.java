package ru.k6lab.core.segment;

import java.util.ArrayList;
import java.util.List;

/**
 * Деление нагрузки между генераторами через {@code --execution-segment} / {@code --execution-segment-sequence}.
 * Границы - точные дроби: равные доли 1/n либо доли по весам.
 */
public final class ExecutionSegments {

    private ExecutionSegments() {
    }

    /** Разбиение отрезка [0,1] на сегменты. */
    public record Plan(List<Fraction> boundaries) {

        public int size() {
            return boundaries.size() - 1;
        }

        public Fraction start(int i) {
            return boundaries.get(i);
        }

        public Fraction end(int i) {
            return boundaries.get(i + 1);
        }

        /** Значение для --execution-segment: «0:1/3». */
        public String segment(int i) {
            return start(i) + ":" + end(i);
        }

        /** Значение для --execution-segment-sequence: «0,1/3,2/3,1». */
        public String sequence() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < boundaries.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(boundaries.get(i));
            }
            return sb.toString();
        }

        /** Доля сегмента i от всей нагрузки. */
        public Fraction share(int i) {
            return end(i).minus(start(i));
        }
    }

    public static Plan equal(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("Нужен хотя бы один генератор");
        }
        List<Fraction> b = new ArrayList<>();
        for (int i = 0; i <= n; i++) {
            b.add(Fraction.of(i, n));
        }
        return new Plan(b);
    }

    public static Plan weighted(List<Integer> weights) {
        if (weights.isEmpty()) {
            throw new IllegalArgumentException("Нужен хотя бы один генератор");
        }
        long total = 0;
        for (int w : weights) {
            if (w < 1) {
                throw new IllegalArgumentException("Вес генератора должен быть положительным целым");
            }
            total += w;
        }
        List<Fraction> b = new ArrayList<>();
        long acc = 0;
        b.add(Fraction.ZERO);
        for (int w : weights) {
            acc += w;
            b.add(Fraction.of(acc, total));
        }
        return new Plan(b);
    }

    /** Разбор sequence «0,1/3,1» обратно в план (для восстановления из snapshot). */
    public static Plan parseSequence(String sequence) {
        List<Fraction> b = new ArrayList<>();
        for (String p : sequence.split(",")) {
            b.add(Fraction.parse(p));
        }
        for (int i = 1; i < b.size(); i++) {
            if (b.get(i).compareTo(b.get(i - 1)) <= 0) {
                throw new IllegalArgumentException("Границы сегментов должны строго возрастать");
            }
        }
        if (!b.get(0).isZero() || !b.get(b.size() - 1).equals(Fraction.ONE)) {
            throw new IllegalArgumentException("Последовательность должна начинаться с 0 и заканчиваться 1");
        }
        return new Plan(b);
    }

    /** Разбор «a:b» в пару дробей. */
    public static Fraction[] parseSegment(String segment) {
        String[] p = segment.split(":");
        if (p.length != 2) {
            throw new IllegalArgumentException("Сегмент должен иметь вид a:b");
        }
        return new Fraction[]{Fraction.parse(p[0]), Fraction.parse(p[1])};
    }
}
