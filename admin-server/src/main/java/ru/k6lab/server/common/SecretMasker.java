package ru.k6lab.server.common;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Маскирует известные секреты в произвольном тексте (логи, вывод команд, сообщения об ошибках). */
public class SecretMasker {

    public static final String MASK = "******";

    private final List<String> secrets = new CopyOnWriteArrayList<>();

    public SecretMasker add(String... values) {
        for (String v : values) {
            if (v != null && v.length() >= 3 && !secrets.contains(v)) {
                secrets.add(v);
            }
        }
        return this;
    }

    public SecretMasker addAll(Collection<String> values) {
        values.forEach(this::add);
        return this;
    }

    public String mask(String text) {
        if (text == null || secrets.isEmpty()) {
            return text;
        }
        String out = text;
        // длинные секреты первыми, чтобы вложенные не оставляли хвосты
        for (String s : secrets.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            out = out.replace(s, MASK);
        }
        return out;
    }
}
