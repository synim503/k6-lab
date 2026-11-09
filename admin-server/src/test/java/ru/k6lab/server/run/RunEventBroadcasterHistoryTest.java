package ru.k6lab.server.run;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunEventBroadcasterHistoryTest {

    @Test
    void thinningKeepsTheWholeRunWithOlderPointsSparserAndTheLatestOnesIntact() {
        ArrayDeque<String> h = new ArrayDeque<>();
        for (int i = 0; i <= 3000; i++) {
            h.addLast(String.valueOf(i));
        }
        RunEventBroadcaster.thin(h);
        List<Integer> kept = new ArrayList<>();
        h.forEach(s -> kept.add(Integer.parseInt(s)));
        assertThat(kept.get(0)).isZero();                                  // начало запуска не потеряно
        assertThat(kept.get(kept.size() - 1)).isEqualTo(3000);             // и конец тоже
        assertThat(kept).isSorted();                                       // порядок во времени сохранён
        assertThat(kept.size()).isLessThan(2100).isGreaterThan(1900);      // вдвое реже в старой части
        // последние 1000 точек - все до одной
        assertThat(kept.subList(kept.size() - 1000, kept.size())).containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(2001, 3000).boxed().toList());
        // повторное прореживание на следующем переполнении: старая часть ещё реже, но снова от начала до конца
        for (int i = 3001; i <= 4500; i++) {
            h.addLast(String.valueOf(i));
        }
        RunEventBroadcaster.thin(h);
        assertThat(h.peekFirst()).isEqualTo("0");
        assertThat(h.peekLast()).isEqualTo("4500");
        assertThat(h.size()).isLessThan(3000);
    }
}
