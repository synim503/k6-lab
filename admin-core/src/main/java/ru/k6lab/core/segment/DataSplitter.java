package ru.k6lab.core.segment;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Нарезка файла данных (режим SPLIT): генератор получает непрерывный блок строк, пропорциональный его
 * execution segment; блоки разных генераторов не пересекаются и покрывают файл целиком.
 * Для CSV заголовок сохраняется. Терминаторы строк (LF/CRLF) не меняются.
 */
public final class DataSplitter {

    private DataSplitter() {
    }

    public record Range(long from, long toExclusive) {
        public long count() {
            return toExclusive - from;
        }
    }

    public record Result(long totalRows, Range range) {
    }

    /** Диапазон строк данных [floor(start*N), floor(end*N)). */
    public static Range range(long rows, Fraction start, Fraction end) {
        return new Range(start.floorTimes(rows), end.floorTimes(rows));
    }

    /** Число строк данных (без заголовка). */
    public static long countRows(Path file, boolean header) throws IOException {
        long lines = 0;
        int last = -1;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            int b;
            while ((b = in.read()) >= 0) {
                if (b == '\n') {
                    lines++;
                }
                last = b;
            }
        }
        if (last >= 0 && last != '\n') {
            lines++;
        }
        return header ? Math.max(0, lines - 1) : lines;
    }

    /**
     * Записывает в {@code dst} заголовок (если есть) и строки данных своего сегмента. {@code dst} может совпадать
     * с {@code src}: тогда файл подменяется атомарно.
     */
    public static Result split(Path src, Path dst, Fraction start, Fraction end, boolean header) throws IOException {
        long rows = countRows(src, header);
        Range range = range(rows, start, end);
        Path tmp = dst.resolveSibling(dst.getFileName() + ".split.tmp");
        try (InputStream in = new BufferedInputStream(Files.newInputStream(src), 1 << 16);
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16)) {
            long dataIdx = 0;
            boolean first = true;
            ByteArrayOutputStream line = new ByteArrayOutputStream(256);
            int b;
            while (true) {
                b = in.read();
                if (b >= 0) {
                    line.write(b);
                    if (b != '\n') {
                        continue;
                    }
                } else if (line.size() == 0) {
                    break;
                }
                if (first && header) {
                    line.writeTo(out);
                } else {
                    if (dataIdx >= range.from() && dataIdx < range.toExclusive()) {
                        line.writeTo(out);
                    }
                    dataIdx++;
                }
                first = false;
                line.reset();
                if (b < 0) {
                    break;
                }
            }
        }
        Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
        return new Result(rows, range);
    }
}
