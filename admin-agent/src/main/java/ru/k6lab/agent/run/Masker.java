package ru.k6lab.agent.run;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** Маскирует значения секретов в строках лога k6. */
public class Masker {

    private final List<String> secrets;

    public Masker(Collection<String> values) {
        this.secrets = values.stream().filter(v -> v != null && v.length() >= 3)
                .sorted(Comparator.comparingInt(String::length).reversed()).toList();
    }

    public String mask(String line) {
        String out = line;
        for (String s : secrets) {
            out = out.replace(s, "******");
        }
        return out;
    }
}
