package ru.k6lab.core.segment;

import java.math.BigInteger;

/** Рациональное число (несократимая дробь) для границ execution segment: без ошибок округления double. */
public record Fraction(long num, long den) implements Comparable<Fraction> {

    public static final Fraction ZERO = new Fraction(0, 1);
    public static final Fraction ONE = new Fraction(1, 1);

    public Fraction {
        if (den == 0) {
            throw new IllegalArgumentException("Знаменатель равен нулю");
        }
        if (den < 0) {
            num = -num;
            den = -den;
        }
        long g = gcd(Math.abs(num), den);
        if (g > 1) {
            num /= g;
            den /= g;
        }
        if (num == 0) {
            den = 1;
        }
    }

    public static Fraction of(long num, long den) {
        return new Fraction(num, den);
    }

    /** Разбор «0», «1», «1/3». */
    public static Fraction parse(String s) {
        String t = s.trim();
        int slash = t.indexOf('/');
        if (slash < 0) {
            return new Fraction(Long.parseLong(t), 1);
        }
        return new Fraction(Long.parseLong(t.substring(0, slash).trim()), Long.parseLong(t.substring(slash + 1).trim()));
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long t = a % b;
            a = b;
            b = t;
        }
        return a == 0 ? 1 : a;
    }

    public Fraction plus(Fraction o) {
        BigInteger n = BigInteger.valueOf(num).multiply(BigInteger.valueOf(o.den))
                .add(BigInteger.valueOf(o.num).multiply(BigInteger.valueOf(den)));
        BigInteger d = BigInteger.valueOf(den).multiply(BigInteger.valueOf(o.den));
        return reduce(n, d);
    }

    public Fraction minus(Fraction o) {
        return plus(new Fraction(-o.num, o.den));
    }

    private static Fraction reduce(BigInteger n, BigInteger d) {
        BigInteger g = n.gcd(d);
        if (g.signum() != 0) {
            n = n.divide(g);
            d = d.divide(g);
        }
        return new Fraction(n.longValueExact(), d.longValueExact());
    }

    /** floor(this * n) без переполнения. */
    public long floorTimes(long n) {
        return BigInteger.valueOf(num).multiply(BigInteger.valueOf(n)).divide(BigInteger.valueOf(den)).longValueExact();
    }

    public double toDouble() {
        return (double) num / den;
    }

    public boolean isZero() {
        return num == 0;
    }

    @Override
    public int compareTo(Fraction o) {
        return BigInteger.valueOf(num).multiply(BigInteger.valueOf(o.den))
                .compareTo(BigInteger.valueOf(o.num).multiply(BigInteger.valueOf(den)));
    }

    /** Формат k6: 0, 1, 1/3. */
    @Override
    public String toString() {
        return den == 1 ? Long.toString(num) : num + "/" + den;
    }
}
