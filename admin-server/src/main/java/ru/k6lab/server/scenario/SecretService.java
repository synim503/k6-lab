package ru.k6lab.server.scenario;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.k6lab.server.audit.AuditService;
import ru.k6lab.server.common.SecretCipher;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Секреты сценариев. Значения подставляются только на агенте и маскируются во всех логах. */
@Service
public class SecretService {

    public static final Pattern REF = Pattern.compile("\\$\\{secret:([A-Za-z0-9_.-]+)}");
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,100}$");

    private final SecretRepository repo;
    private final SecretCipher cipher;
    private final AuditService audit;

    public SecretService(SecretRepository repo, SecretCipher cipher, AuditService audit) {
        this.repo = repo;
        this.cipher = cipher;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Secret> list() {
        return repo.findAllByOrderByNameAsc();
    }

    /** Создаёт или заменяет значение. Пустое значение при редактировании существующего - «не менять». */
    @Transactional
    public Secret save(String name, String description, String value) {
        if (name == null || !NAME.matcher(name.trim()).matches()) {
            throw new IllegalArgumentException("Имя секрета: латиница, цифры, _ . - (до 100 символов)");
        }
        Secret s = repo.findByNameIn(List.of(name.trim())).stream().findFirst().orElse(null);
        boolean created = s == null;
        if (created) {
            if (value == null || value.isEmpty()) {
                throw new IllegalArgumentException("Введите значение секрета");
            }
            s = new Secret();
            s.setName(name.trim());
        }
        s.setDescription(description == null || description.isBlank() ? null : description.trim());
        if (value != null && !value.isEmpty()) {
            s.setValueEnc(cipher.encrypt(value));
        }
        Secret saved = repo.save(s);
        audit.record(created ? "secret.create" : "secret.update", "secret", saved.getId(), Map.of("name", saved.getName()));
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        repo.findById(id).ifPresent(s -> {
            repo.delete(s);
            audit.record("secret.delete", "secret", id, Map.of("name", s.getName()));
        });
    }

    /** Имена секретов, на которые ссылаются значения env. */
    public static Set<String> referencedNames(Map<String, String> env) {
        Set<String> out = new HashSet<>();
        for (String v : env.values()) {
            Matcher m = REF.matcher(v == null ? "" : v);
            while (m.find()) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    /** Проверка: все ссылки ${secret:NAME} указывают на существующие секреты. */
    @Transactional(readOnly = true)
    public void requireExist(Set<String> names) {
        if (names.isEmpty()) {
            return;
        }
        Set<String> found = new HashSet<>();
        repo.findByNameIn(names).forEach(s -> found.add(s.getName()));
        Set<String> missing = new HashSet<>(names);
        missing.removeAll(found);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Не найдены секреты: " + String.join(", ", new java.util.TreeSet<>(missing)));
        }
    }

    /** Расшифрованные значения для PREPARE (живут только в памяти). */
    @Transactional(readOnly = true)
    public Map<String, String> resolve(Set<String> names) {
        Map<String, String> out = new LinkedHashMap<>();
        if (names.isEmpty()) {
            return out;
        }
        for (Secret s : repo.findByNameIn(names)) {
            out.put(s.getName(), cipher.decrypt(s.getValueEnc()));
        }
        return out;
    }
}
