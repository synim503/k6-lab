package ru.k6lab.core.k6config;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * План интенсивности сценария k6 в оп/с по его конфигу: то, что k6 должен выдавать на самом деле. У ramping-arrival-rate интенсивность
 * растёт линейно от начального значения к цели каждой стадии, поэтому план - ломаная по стадиям, а не плоская линия.
 * Для сценариев не по интенсивности (VU, итерации) плана в оп/с нет.
 */
public final class ScenarioPlan {

    private ScenarioPlan() {
    }

    /** Точка плана: секунда от начала теста и оп/с. */
    public record Pt(double sec, double rate) {
    }

    /** Ломаная плана сценария (пусто - плана по оп/с нет). До startTime и после конца интенсивность 0. */
    public static List<Pt> polyline(JsonNode sc) {
        List<Pt> out = new ArrayList<>();
        if (sc == null || sc.isMissingNode()) {
            return out;
        }
        String ex = sc.path("executor").asText("");
        double start = ConfigDuration.parse(sc.path("startTime").asText("0s"), 0);
        double unit = ConfigDuration.parse(sc.path("timeUnit").asText("1s"), 1);
        if (unit <= 0) {
            unit = 1;
        }
        if ("constant-arrival-rate".equals(ex) && sc.has("rate")) {
            double dur = ConfigDuration.parse(sc.path("duration").asText("0s"), 0);
            double r = sc.path("rate").asDouble() / unit;
            out.add(new Pt(start, 0));
            out.add(new Pt(start, r));
            out.add(new Pt(start + dur, r));
            out.add(new Pt(start + dur, 0));
        } else if ("ramping-arrival-rate".equals(ex)) {
            double t = start;
            out.add(new Pt(t, 0));
            double r = sc.path("startRate").asDouble(0) / unit;
            out.add(new Pt(t, r));
            for (JsonNode st : sc.path("stages")) {
                t += ConfigDuration.parse(st.path("duration").asText("0s"), 0);
                r = st.path("target").asDouble(0) / unit;
                out.add(new Pt(t, r));
            }
            out.add(new Pt(t, 0));
        }
        return out;
    }

    /** Плановая интенсивность в оп/с на секунде sec от начала теста; null - плана по оп/с нет. */
    public static Double ratePerSec(JsonNode sc, double sec) {
        List<Pt> poly = polyline(sc);
        if (poly.isEmpty()) {
            return null;
        }
        // последняя точка ломаной, которая не позже sec (на вертикальном скачке - значение после него); между точками - линейно
        int i = -1;
        for (int k = 0; k < poly.size(); k++) {
            if (poly.get(k).sec() <= sec) {
                i = k;
            }
        }
        if (i < 0) {
            return 0.0;
        }
        Pt a = poly.get(i);
        if (i + 1 < poly.size() && poly.get(i + 1).sec() > a.sec()) {
            Pt b = poly.get(i + 1);
            return a.rate() + (b.rate() - a.rate()) * (sec - a.sec()) / (b.sec() - a.sec());
        }
        return a.rate();
    }

    /**
     * Средняя плановая интенсивность в оп/с на отрезке [fromSec, toSec] от начала теста (площадь под ломаной, делённая на длину);
     * null - плана по оп/с нет. С ней сверяется средняя фактическая интенсивность за то же окно - в том числе на разгоне по стадиям.
     */
    public static Double averageRate(JsonNode sc, double fromSec, double toSec) {
        List<Pt> poly = polyline(sc);
        if (poly.isEmpty()) {
            return null;
        }
        if (toSec <= fromSec) {
            return ratePerSec(sc, fromSec);
        }
        double area = 0;
        for (int i = 0; i + 1 < poly.size(); i++) {
            Pt a = poly.get(i), b = poly.get(i + 1);
            double lo = Math.max(a.sec(), fromSec), hi = Math.min(b.sec(), toSec);
            if (b.sec() <= a.sec() || hi <= lo) {
                continue;
            }
            double k = (b.rate() - a.rate()) / (b.sec() - a.sec());
            area += (2 * a.rate() + k * (lo - a.sec()) + k * (hi - a.sec())) / 2 * (hi - lo);
        }
        return area / (toSec - fromSec);
    }
}
