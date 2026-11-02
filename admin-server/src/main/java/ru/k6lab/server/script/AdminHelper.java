package ru.k6lab.server.script;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** Модуль admin.js из k6-helpers: кладётся в __admin/admin.js рядом со скриптом и отдаётся на скачивание. */
@Component
public class AdminHelper {

    public static final String PATH = "__admin/admin.js";

    private final byte[] content;

    public AdminHelper() {
        try (InputStream in = new ClassPathResource("k6/admin.js").getInputStream()) {
            this.content = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Не найден k6/admin.js (модуль k6-helpers)", e);
        }
    }

    public byte[] content() {
        return content;
    }
}
