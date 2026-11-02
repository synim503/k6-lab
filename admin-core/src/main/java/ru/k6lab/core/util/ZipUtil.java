package ru.k6lab.core.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Работа с zip-архивами версий скриптов: распаковка с защитой от zip-slip, чтение и замена записей. */
public final class ZipUtil {

    public static final int MAX_ENTRIES = 50_000;
    public static final long MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;

    private ZipUtil() {
    }

    public record Entry(String path, long size) {
    }

    /** Нормализует путь записи; null, если путь опасен (абсолютный, с .., пустой). */
    public static String safePath(String name) {
        String p = name.replace('\\', '/');
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        if (p.isEmpty() || p.startsWith("/") || p.matches("^[A-Za-z]:.*")) {
            return null;
        }
        for (String seg : p.split("/")) {
            if (seg.equals("..")) {
                return null;
            }
        }
        return p;
    }

    public static void extract(InputStream zip, Path dest) throws IOException {
        Path root = dest.toAbsolutePath().normalize();
        Files.createDirectories(root);
        long total = 0;
        int count = 0;
        try (ZipInputStream in = new ZipInputStream(zip)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                String sp = safePath(e.getName());
                if (sp == null) {
                    throw new IOException("Недопустимый путь в архиве: " + e.getName());
                }
                Path target = root.resolve(sp).normalize();
                if (!target.startsWith(root)) {
                    throw new IOException("Недопустимый путь в архиве: " + e.getName());
                }
                if (++count > MAX_ENTRIES) {
                    throw new IOException("В архиве слишком много файлов");
                }
                if (e.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (OutputStream out = Files.newOutputStream(target)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_TOTAL_BYTES) {
                            throw new IOException("Архив слишком большой после распаковки");
                        }
                        out.write(buf, 0, n);
                    }
                }
            }
        }
    }

    public static List<Entry> list(InputStream zip) throws IOException {
        List<Entry> out = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(zip)) {
            ZipEntry e;
            byte[] buf = new byte[64 * 1024];
            while ((e = in.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                long size = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    size += n;
                }
                out.add(new Entry(e.getName(), size));
            }
        }
        return out;
    }

    /** Содержимое записи или null, если её нет. */
    public static byte[] read(InputStream zip, String path) throws IOException {
        try (ZipInputStream in = new ZipInputStream(zip)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (!e.isDirectory() && e.getName().equals(path)) {
                    return in.readAllBytes();
                }
            }
        }
        return null;
    }

    public static Map<String, byte[]> readAll(InputStream zip) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(zip)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                byte[] b = in.readAllBytes();
                total += b.length;
                if (total > MAX_TOTAL_BYTES || out.size() > MAX_ENTRIES) {
                    throw new IOException("Архив слишком большой");
                }
                out.put(e.getName(), b);
            }
        }
        return out;
    }

    public static byte[] write(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> f : files.entrySet()) {
                String sp = safePath(f.getKey());
                if (sp == null) {
                    throw new IOException("Недопустимый путь: " + f.getKey());
                }
                ZipEntry ze = new ZipEntry(sp);
                ze.setTime(0); // воспроизводимый архив: одинаковое содержимое -> одинаковый sha256
                out.putNextEntry(ze);
                out.write(f.getValue());
                out.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    public static byte[] singleFile(String name, byte[] content) throws IOException {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put(name, content);
        return write(m);
    }

    /** Новый архив с заменённой (или добавленной) записью. */
    public static byte[] replace(byte[] zip, String path, byte[] content) throws IOException {
        Map<String, byte[]> files = readAll(new ByteArrayInputStream(zip));
        files.put(path, content);
        return write(files);
    }

    /** Если все файлы лежат в одном каталоге верхнего уровня - убирает его (частая упаковка «zip папки»). */
    public static Map<String, byte[]> stripSingleTopDir(Map<String, byte[]> files) {
        String top = null;
        for (String p : files.keySet()) {
            int slash = p.indexOf('/');
            if (slash < 0) {
                return files;
            }
            String t = p.substring(0, slash);
            if (top == null) {
                top = t;
            } else if (!top.equals(t)) {
                return files;
            }
        }
        if (top == null) {
            return files;
        }
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            out.put(e.getKey().substring(top.length() + 1), e.getValue());
        }
        return out;
    }
}
