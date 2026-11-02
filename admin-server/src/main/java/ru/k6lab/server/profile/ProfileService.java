package ru.k6lab.server.profile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.k6lab.core.profile.ProfileExpander;
import ru.k6lab.core.profile.ProfileType;
import ru.k6lab.core.profile.StepPlan;
import ru.k6lab.server.audit.AuditService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProfileService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record ProfileForm(Long id, String name, ProfileType type, Long templateId, Map<String, String> params,
                              int gracefulStopSec, int gracefulRampDownSec, boolean template) {
    }

    private final TestProfileRepository profiles;
    private final ProfileTemplateRepository templates;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public ProfileService(TestProfileRepository profiles, ProfileTemplateRepository templates, JdbcTemplate jdbc,
                          AuditService audit) {
        this.profiles = profiles;
        this.templates = templates;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ профили

    @Transactional(readOnly = true)
    public List<TestProfile> list() {
        return profiles.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public TestProfile get(Long id) {
        return profiles.findById(id).orElseThrow(() -> new IllegalArgumentException("Профиль не найден"));
    }

    /** Ступени профиля (с валидацией параметров). */
    @Transactional(readOnly = true)
    public List<StepPlan> steps(TestProfile p) {
        JsonNode def = null;
        if (p.getType() == ProfileType.TEMPLATE) {
            def = templates.findById(p.getTemplateId()).orElseThrow(() -> new IllegalStateException("Шаблон профиля удалён")).getDefinition();
        }
        return ProfileExpander.expand(p.getType(), p.getParams(), def);
    }

    @Transactional
    public TestProfile save(ProfileForm f) {
        if (f.name() == null || f.name().isBlank()) {
            throw new IllegalArgumentException("Укажите имя профиля");
        }
        if (f.type() == null) {
            throw new IllegalArgumentException("Выберите тип профиля");
        }
        TestProfile p;
        if (f.id() == null) {
            if (profiles.existsByNameIgnoreCase(f.name().trim())) {
                throw new IllegalArgumentException("Профиль с таким именем уже есть");
            }
            p = new TestProfile();
        } else {
            p = get(f.id());
            if (!p.getName().equalsIgnoreCase(f.name().trim()) && profiles.existsByNameIgnoreCase(f.name().trim())) {
                throw new IllegalArgumentException("Профиль с таким именем уже есть");
            }
        }
        if (f.gracefulStopSec() < 0 || f.gracefulRampDownSec() < 0) {
            throw new IllegalArgumentException("Graceful-значения не могут быть отрицательными");
        }
        JsonNode def = null;
        if (f.type() == ProfileType.TEMPLATE) {
            if (f.templateId() == null) {
                throw new IllegalArgumentException("Выберите шаблон профиля");
            }
            def = templates.findById(f.templateId()).orElseThrow(() -> new IllegalArgumentException("Шаблон профиля не найден")).getDefinition();
        }
        Map<String, Object> params = convertParams(f.type(), f.params());
        // проверка: параметры должны раскрываться в ступени
        ProfileExpander.expand(f.type(), params, def);
        p.setName(f.name().trim());
        p.setType(f.type());
        p.setTemplateId(f.type() == ProfileType.TEMPLATE ? f.templateId() : null);
        p.setParams(params);
        p.setGracefulStopSec(f.gracefulStopSec());
        p.setGracefulRampDownSec(f.gracefulRampDownSec());
        p.setTemplate(f.template());
        TestProfile saved = profiles.save(p);
        audit.record(f.id() == null ? "profile.create" : "profile.update", "test_profile", saved.getId(), Map.of("name", saved.getName()));
        return saved;
    }

    @Transactional
    public TestProfile clone(Long id, String newName) {
        TestProfile s = get(id);
        String name = newName == null || newName.isBlank() ? s.getName() + " (копия)" : newName.trim();
        if (profiles.existsByNameIgnoreCase(name)) {
            throw new IllegalArgumentException("Профиль с таким именем уже есть");
        }
        TestProfile c = new TestProfile();
        c.setName(name);
        c.setType(s.getType());
        c.setTemplateId(s.getTemplateId());
        c.setParams(new LinkedHashMap<>(s.getParams()));
        c.setGracefulStopSec(s.getGracefulStopSec());
        c.setGracefulRampDownSec(s.getGracefulRampDownSec());
        return profiles.save(c);
    }

    @Transactional
    public void delete(Long id) {
        Integer n = jdbc.queryForObject("select count(*) from scenario where profile_id = ?", Integer.class, id);
        if (n != null && n > 0) {
            throw new IllegalStateException("Профиль используется в сценариях");
        }
        profiles.deleteById(id);
        audit.record("profile.delete", "test_profile", id, null);
    }

    /** Значения из формы (строки) -> параметры; CUSTOM: строки таблицы ступеней. */
    static Map<String, Object> convertParams(ProfileType type, Map<String, String> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (type == ProfileType.CUSTOM) {
            List<Map<String, Object>> steps = new ArrayList<>();
            String text = raw.getOrDefault("steps", "");
            int n = 0;
            for (String line : text.split("\\R")) {
                String l = line.strip();
                if (l.isEmpty() || l.startsWith("#")) {
                    continue;
                }
                n++;
                String[] parts = l.split("[;\\t]");
                if (parts.length < 3) {
                    throw new IllegalArgumentException("Ступень " + n + ": ожидается «уровень %;разгон с;удержание с[;метка]»");
                }
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("levelPct", parts[0].strip());
                s.put("rampSec", parts[1].strip());
                s.put("holdSec", parts[2].strip());
                if (parts.length > 3 && !parts[3].isBlank()) {
                    s.put("label", parts[3].strip());
                }
                steps.add(s);
            }
            out.put("steps", steps);
            return out;
        }
        raw.forEach((k, v) -> {
            if (v != null && !v.isBlank()) {
                out.put(k, v.strip());
            }
        });
        return out;
    }

    /** Для формы редактирования: параметры обратно в строки. */
    public static Map<String, String> paramsToForm(TestProfile p) {
        Map<String, String> out = new LinkedHashMap<>();
        if (p.getType() == ProfileType.CUSTOM) {
            StringBuilder sb = new StringBuilder();
            Object steps = p.getParams().get("steps");
            if (steps instanceof List<?> l) {
                for (Object o : l) {
                    if (o instanceof Map<?, ?> m) {
                        sb.append(m.get("levelPct")).append(';').append(m.get("rampSec")).append(';').append(m.get("holdSec"));
                        if (m.get("label") != null) {
                            sb.append(';').append(m.get("label"));
                        }
                        sb.append('\n');
                    }
                }
            }
            out.put("steps", sb.toString().stripTrailing());
        } else {
            p.getParams().forEach((k, v) -> out.put(k, String.valueOf(v)));
        }
        return out;
    }

    // ------------------------------------------------------------------ шаблоны профилей (ADMIN)

    @Transactional(readOnly = true)
    public List<ProfileTemplate> templates() {
        return templates.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public ProfileTemplate template(Long id) {
        return templates.findById(id).orElseThrow(() -> new IllegalArgumentException("Шаблон профиля не найден"));
    }

    /** Проверяет определение шаблона: JSON, параметры, раскрытие со значениями по умолчанию. */
    public List<StepPlan> validateDefinition(String json) {
        JsonNode def;
        try {
            def = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Некорректный JSON: " + e.getOriginalMessage());
        }
        if (def == null || !def.has("blocks") || !def.get("blocks").isArray()) {
            throw new IllegalArgumentException("Нужен массив blocks со ступенями");
        }
        for (JsonNode p : def.path("params")) {
            if (!p.path("name").asText().matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("Имя параметра должно быть идентификатором: " + p.path("name").asText());
            }
        }
        return ProfileExpander.expand(ProfileType.TEMPLATE, Map.of(), def);
    }

    @Transactional
    public ProfileTemplate saveTemplate(Long id, String name, String description, String json) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Укажите имя шаблона");
        }
        validateDefinition(json);
        ProfileTemplate t;
        if (id == null) {
            if (templates.existsByNameIgnoreCase(name.trim())) {
                throw new IllegalArgumentException("Шаблон с таким именем уже есть");
            }
            t = new ProfileTemplate();
        } else {
            t = template(id);
        }
        try {
            t.setDefinition(MAPPER.readTree(json));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Некорректный JSON");
        }
        t.setName(name.trim());
        t.setDescription(description == null || description.isBlank() ? null : description.trim());
        ProfileTemplate saved = templates.save(t);
        audit.record("profile_template.save", "profile_template", saved.getId(), Map.of("name", saved.getName()));
        return saved;
    }

    @Transactional
    public void deleteTemplate(Long id) {
        if (profiles.existsByTemplateId(id)) {
            throw new IllegalStateException("Шаблон используется профилями");
        }
        templates.deleteById(id);
    }
}
