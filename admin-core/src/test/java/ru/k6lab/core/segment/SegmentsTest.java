package ru.k6lab.core.segment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SegmentsTest {

    @Test
    void fractionsAreNormalisedAndFormattedForK6() {
        assertThat(Fraction.of(2, 6)).hasToString("1/3");
        assertThat(Fraction.of(0, 5)).hasToString("0");
        assertThat(Fraction.of(4, 4)).hasToString("1");
        assertThat(Fraction.of(1, -2)).isEqualTo(Fraction.of(-1, 2));
        assertThat(Fraction.parse("3/9")).isEqualTo(Fraction.of(1, 3));
        assertThat(Fraction.of(1, 3).compareTo(Fraction.of(2, 6))).isZero();
        assertThat(Fraction.of(1, 3).plus(Fraction.of(1, 6))).isEqualTo(Fraction.of(1, 2));
        assertThat(Fraction.of(1, 3).floorTimes(10)).isEqualTo(3);
        assertThat(Fraction.of(2, 3).floorTimes(Long.MAX_VALUE / 2)).isPositive(); // без переполнения
        assertThatThrownBy(() -> Fraction.of(1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalShares() {
        var plan = ExecutionSegments.equal(3);
        assertThat(plan.sequence()).isEqualTo("0,1/3,2/3,1");
        assertThat(plan.segment(0)).isEqualTo("0:1/3");
        assertThat(plan.segment(1)).isEqualTo("1/3:2/3");
        assertThat(plan.segment(2)).isEqualTo("2/3:1");
        assertThat(plan.size()).isEqualTo(3);
        assertThat(ExecutionSegments.equal(1).segment(0)).isEqualTo("0:1");
        assertThat(ExecutionSegments.equal(2).sequence()).isEqualTo("0,1/2,1");
    }

    @Test
    void weightedShares() {
        var plan = ExecutionSegments.weighted(List.of(1, 1, 2));
        assertThat(plan.sequence()).isEqualTo("0,1/4,1/2,1");
        assertThat(plan.share(2)).isEqualTo(Fraction.of(1, 2));
        var p2 = ExecutionSegments.weighted(List.of(3, 1));
        assertThat(p2.sequence()).isEqualTo("0,3/4,1");
        assertThat(ExecutionSegments.weighted(List.of(7)).segment(0)).isEqualTo("0:1");
        assertThatThrownBy(() -> ExecutionSegments.weighted(List.of(1, 0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExecutionSegments.weighted(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExecutionSegments.equal(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sharesAlwaysSumToOneAndHaveNoGaps() {
        for (int n = 1; n <= 23; n++) {
            var plan = ExecutionSegments.equal(n);
            Fraction sum = Fraction.ZERO;
            for (int i = 0; i < n; i++) {
                sum = sum.plus(plan.share(i));
                if (i > 0) {
                    assertThat(plan.start(i)).isEqualTo(plan.end(i - 1));
                }
            }
            assertThat(sum).isEqualTo(Fraction.ONE);
        }
        var w = ExecutionSegments.weighted(List.of(5, 3, 7, 11, 1));
        assertThat(w.start(0)).isEqualTo(Fraction.ZERO);
        assertThat(w.end(4)).isEqualTo(Fraction.ONE);
    }

    @Test
    void sequenceParsingRoundTripsAndValidates() {
        var plan = ExecutionSegments.parseSequence("0,1/3,2/3,1");
        assertThat(plan.sequence()).isEqualTo("0,1/3,2/3,1");
        assertThatThrownBy(() -> ExecutionSegments.parseSequence("0,2/3,1/3,1")).hasMessageContaining("возрастать");
        assertThatThrownBy(() -> ExecutionSegments.parseSequence("1/3,1")).hasMessageContaining("начинаться");
        Fraction[] seg = ExecutionSegments.parseSegment("1/3:2/3");
        assertThat(seg[0]).isEqualTo(Fraction.of(1, 3));
        assertThat(seg[1]).isEqualTo(Fraction.of(2, 3));
    }

    // ---------------------------------------------------------------- нарезка данных

    private static List<String> lines(Path p) throws IOException {
        return Files.readAllLines(p, StandardCharsets.UTF_8);
    }

    @Test
    void splitKeepsHeaderAndGivesDisjointContiguousBlocks(@TempDir Path dir) throws IOException {
        StringBuilder sb = new StringBuilder("login,password\n");
        for (int i = 1; i <= 10; i++) {
            sb.append("user").append(i).append(",p").append(i).append('\n');
        }
        Path src = dir.resolve("users.csv");
        Files.writeString(src, sb.toString());
        var plan = ExecutionSegments.equal(3);
        List<String> all = new ArrayList<>();
        long total = 0;
        for (int i = 0; i < 3; i++) {
            Path dst = dir.resolve("out" + i + ".csv");
            var res = DataSplitter.split(src, dst, plan.start(i), plan.end(i), true);
            assertThat(res.totalRows()).isEqualTo(10);
            List<String> got = lines(dst);
            assertThat(got.get(0)).isEqualTo("login,password");
            all.addAll(got.subList(1, got.size()));
            total += got.size() - 1;
        }
        // 10 строк на 3 генератора: [0,3) [3,6) [6,10), без пересечений и потерь
        assertThat(total).isEqualTo(10);
        assertThat(all).containsExactly("user1,p1", "user2,p2", "user3,p3", "user4,p4", "user5,p5", "user6,p6",
                "user7,p7", "user8,p8", "user9,p9", "user10,p10");
    }

    @Test
    void splitWithoutHeaderAndWithoutTrailingNewlineAndCrlf(@TempDir Path dir) throws IOException {
        Path src = dir.resolve("data.txt");
        Files.writeString(src, "a\r\nb\r\nc\r\nd"); // без финального перевода строки, CRLF
        var first = DataSplitter.split(src, dir.resolve("o1"), Fraction.ZERO, Fraction.of(1, 2), false);
        var second = DataSplitter.split(src, dir.resolve("o2"), Fraction.of(1, 2), Fraction.ONE, false);
        assertThat(first.totalRows()).isEqualTo(4);
        assertThat(Files.readString(dir.resolve("o1"))).isEqualTo("a\r\nb\r\n");
        assertThat(Files.readString(dir.resolve("o2"))).isEqualTo("c\r\nd");
    }

    @Test
    void splitCanReplaceFileInPlaceAndHandlesEdgeCases(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("x.csv");
        Files.writeString(f, "h\n1\n2\n3\n4\n");
        DataSplitter.split(f, f, Fraction.of(1, 2), Fraction.ONE, true);
        assertThat(lines(f)).containsExactly("h", "3", "4");

        Path empty = dir.resolve("empty.csv");
        Files.writeString(empty, "");
        assertThat(DataSplitter.split(empty, empty, Fraction.ZERO, Fraction.ONE, true).totalRows()).isZero();

        Path headerOnly = dir.resolve("h.csv");
        Files.writeString(headerOnly, "only,header\n");
        DataSplitter.split(headerOnly, headerOnly, Fraction.ZERO, Fraction.ONE, true);
        assertThat(lines(headerOnly)).containsExactly("only,header");

        // строк меньше, чем генераторов: у части блок пустой
        Path two = dir.resolve("two.csv");
        Files.writeString(two, "1\n2\n");
        var plan = ExecutionSegments.equal(5);
        long sum = 0;
        for (int i = 0; i < 5; i++) {
            sum += DataSplitter.split(two, dir.resolve("t" + i), plan.start(i), plan.end(i), false).range().count();
        }
        assertThat(sum).isEqualTo(2);
    }

    @Test
    void rangeIsProportional() {
        assertThat(DataSplitter.range(100, Fraction.of(1, 4), Fraction.of(1, 2)))
                .isEqualTo(new DataSplitter.Range(25, 50));
        assertThat(DataSplitter.range(7, Fraction.ZERO, Fraction.ONE).count()).isEqualTo(7);
    }
}
