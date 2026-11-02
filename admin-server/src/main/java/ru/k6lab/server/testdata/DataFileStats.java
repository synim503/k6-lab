package ru.k6lab.server.testdata;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Статистика файлов данных внутри zip версии скрипта. */
public final class DataFileStats {

    private DataFileStats() {
    }

    /** Число строк данных (без заголовка) либо -1, если файла нет. */
    public static long countRows(byte[] zip, String path, boolean header) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.isDirectory() || !e.getName().equals(path)) {
                    continue;
                }
                long lines = 0;
                int last = -1;
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    for (int i = 0; i < n; i++) {
                        if (buf[i] == '\n') {
                            lines++;
                        }
                    }
                    last = buf[n - 1];
                }
                if (last >= 0 && last != '\n') {
                    lines++;
                }
                return header ? Math.max(0, lines - 1) : lines;
            }
        }
        return -1;
    }

    /** Путь файла похож на файл данных, нарезаемый построчно (CSV/TXT/JSONL...). */
    public static boolean isLineBased(String path) {
        String p = path.toLowerCase();
        return p.endsWith(".csv") || p.endsWith(".txt") || p.endsWith(".tsv") || p.endsWith(".jsonl") || p.endsWith(".ndjson");
    }
}
