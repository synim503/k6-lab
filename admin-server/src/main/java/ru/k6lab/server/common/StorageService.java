package ru.k6lab.server.common;

import java.io.IOException;
import java.io.InputStream;

/** Хранилище файлов (дистрибутивы, версии скриптов, отчёты). Реализация - на файловой системе. */
public interface StorageService {

    /** Сохраняет поток, считает размер и sha256. category - логическая папка (например, "artifacts"). */
    StoredFile store(String category, String filename, InputStream content) throws IOException;

    InputStream open(String key) throws IOException;

    boolean exists(String key);

    long size(String key) throws IOException;

    void delete(String key) throws IOException;

    record StoredFile(String key, long size, String sha256) {
    }
}
