package ru.k6lab.server.loadmodel;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.k6lab.core.model.OperationSpec;
import ru.k6lab.core.model.RateUnit;
import ru.k6lab.server.audit.AuditService;
import ru.k6lab.server.script.ScriptRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class LoadModelService {

    private static final Pattern OP_NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern FN_NAME = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*$");

    private final LoadModelRepository models;
    private final ScriptRepository scripts;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public LoadModelService(LoadModelRepository models, ScriptRepository scripts, JdbcTemplate jdbc, AuditService audit) {
        this.models = models;
        this.scripts = scripts;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<LoadModel> list() {
        return models.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public LoadModel get(Long id) {
        return models.findById(id).orElseThrow(() -> new IllegalArgumentException("Модель не найдена"));
    }

    @Transactional
    public LoadModel save(ModelForm f) {
        if (f.getName() == null || f.getName().isBlank()) {
            throw new IllegalArgumentException("Укажите имя модели");
        }
        if (f.getScriptId() == null || !scripts.existsById(f.getScriptId())) {
            throw new IllegalArgumentException("Выберите скрипт");
        }
        LoadModel m;
        if (f.getId() == null) {
            if (models.existsByNameIgnoreCase(f.getName().trim())) {
                throw new IllegalArgumentException("Модель с таким именем уже есть");
            }
            m = new LoadModel();
        } else {
            m = get(f.getId());
            if (!m.getName().equalsIgnoreCase(f.getName().trim()) && models.existsByNameIgnoreCase(f.getName().trim())) {
                throw new IllegalArgumentException("Модель с таким именем уже есть");
            }
            if (!m.getScriptId().equals(f.getScriptId()) && usedByScenarios(m.getId())) {
                throw new IllegalStateException("Нельзя сменить скрипт модели, которая используется в сценариях");
            }
        }
        m.setName(f.getName().trim());
        m.setDescription(blankToNull(f.getDescription()));
        m.setScriptId(f.getScriptId());
        m.setTemplate(f.isTemplate());

        List<ModelForm.OpForm> forms = f.getOps().stream().filter(o -> o != null && !o.blank()).toList();
        if (forms.isEmpty()) {
            throw new IllegalArgumentException("Добавьте хотя бы одну операцию");
        }
        Set<String> names = new HashSet<>();
        double totalShareEnabled = 0;
        for (ModelForm.OpForm o : forms) {
            validateOp(o, names);
            if (o.isEnabled() && f.getInputMode() == ModelForm.InputMode.SHARE) {
                totalShareEnabled += o.getSharePct() == null ? 0 : o.getSharePct();
            }
        }
        double totalPerSec = 0;
        if (f.getInputMode() == ModelForm.InputMode.SHARE) {
            if (f.getTotalValue() == null || f.getTotalValue() <= 0) {
                throw new IllegalArgumentException("Укажите общую интенсивность модели");
            }
            if (Math.abs(totalShareEnabled - 100.0) > 0.01) {
                throw new IllegalArgumentException(String.format(java.util.Locale.ROOT,
                        "Сумма долей включённых операций должна быть 100%% (сейчас %.2f%%)", totalShareEnabled));
            }
            totalPerSec = f.getTotalUnit().toPerSec(f.getTotalValue());
        }

        m.getOperations().clear();
        int pos = 0;
        double sum = 0;
        java.util.ArrayList<LoadOperation> built = new java.util.ArrayList<>();
        for (ModelForm.OpForm o : forms) {
            LoadOperation op = new LoadOperation();
            op.setModel(m);
            op.setPosition(pos++);
            op.setName(o.getName().trim());
            op.setExecFn(o.getExecFn().trim());
            op.setMode(o.getMode());
            op.setExpectedDurationSec(o.getExpectedDurationSec() == null ? 0 : o.getExpectedDurationSec());
            op.setSlaPercentile(o.getSlaPercentile());
            op.setSlaThresholdMs(o.getSlaThresholdMs() == null ? 1000 : o.getSlaThresholdMs());
            op.setMaxErrorPct(o.getMaxErrorPct() == null ? 1 : o.getMaxErrorPct());
            op.setFailureSource(o.getFailureSource());
            op.setEnv(ModelForm.parseKv(o.getEnvText()));
            op.setTags(ModelForm.parseKv(o.getTagsText()));
            op.setEnabled(o.isEnabled());
            op.setFixedLevelPct(o.getFixedLevelPct());
            op.setPreAllocOverride(o.getPreAllocOverride());
            op.setMaxVusOverride(o.getMaxVusOverride());

            double ratePerSec;
            if (o.getMode() == OperationSpec.Mode.CLOSED) {
                op.setPacingMs(o.getPacingMs());
                double pacingSec = o.getPacingMs() / 1000.0;
                if (o.getClosedInput() == ModelForm.ClosedInput.VU_PACING) {
                    // VU + pacing -> интенсивность = VU / pacing_сек
                    op.setVusOverride(o.getVusOverride());
                    ratePerSec = o.getVusOverride() / pacingSec;
                } else {
                    // интенсивность + pacing -> VU = интенсивность_в_сек * pacing_сек
                    op.setVusOverride(null);
                    ratePerSec = f.getInputMode() == ModelForm.InputMode.SHARE
                            ? totalPerSec * (o.getSharePct() == null ? 0 : o.getSharePct()) / 100.0
                            : o.getIntensityUnit().toPerSec(o.getIntensityValue());
                }
            } else {
                op.setPacingMs(null);
                op.setVusOverride(null);
                ratePerSec = f.getInputMode() == ModelForm.InputMode.SHARE
                        ? totalPerSec * (o.getSharePct() == null ? 0 : o.getSharePct()) / 100.0
                        : o.getIntensityUnit().toPerSec(o.getIntensityValue());
            }
            if (!o.isEnabled() && f.getInputMode() == ModelForm.InputMode.SHARE) {
                ratePerSec = o.getIntensityValue() == null ? 0 : o.getIntensityUnit().toPerSec(o.getIntensityValue());
            }
            RateUnit unit = f.getInputMode() == ModelForm.InputMode.SHARE ? f.getTotalUnit() : o.getIntensityUnit();
            op.setIntensityUnit(unit);
            op.setIntensityValue(unit.fromPerSec(ratePerSec));
            if (op.isEnabled()) {
                sum += ratePerSec;
            }
            built.add(op);
        }
        for (LoadOperation op : built) {
            op.setSharePct(op.isEnabled() && sum > 0 ? op.ratePerSec() / sum * 100.0 : 0);
        }
        m.getOperations().addAll(built);
        m.setTotalPerSec(sum);
        LoadModel saved = models.save(m);
        audit.record(f.getId() == null ? "model.create" : "model.update", "load_model", saved.getId(), Map.of("name", saved.getName()));
        return saved;
    }

    private static void validateOp(ModelForm.OpForm o, Set<String> names) {
        String label = o.getName() == null ? "?" : o.getName();
        if (o.getName() == null || !OP_NAME.matcher(o.getName().trim()).matches()) {
            throw new IllegalArgumentException("Операция «" + label + "»: имя - латиница, цифры, _ и - (до 64 символов)");
        }
        if (!names.add(o.getName().trim())) {
            throw new IllegalArgumentException("Имя операции «" + label + "» повторяется");
        }
        if (o.getExecFn() == null || !FN_NAME.matcher(o.getExecFn().trim()).matches()) {
            throw new IllegalArgumentException("Операция «" + label + "»: укажите exec-функцию (имя экспортируемой функции)");
        }
        if (o.getSlaPercentile() != 90 && o.getSlaPercentile() != 95 && o.getSlaPercentile() != 99) {
            throw new IllegalArgumentException("Операция «" + label + "»: перцентиль SLA - 90, 95 или 99");
        }
        if (o.getSlaThresholdMs() != null && o.getSlaThresholdMs() <= 0) {
            throw new IllegalArgumentException("Операция «" + label + "»: порог SLA должен быть больше 0");
        }
        if (o.getMaxErrorPct() != null && (o.getMaxErrorPct() < 0 || o.getMaxErrorPct() > 100)) {
            throw new IllegalArgumentException("Операция «" + label + "»: допустимый % ошибок - от 0 до 100");
        }
        if (o.getFixedLevelPct() != null && o.getFixedLevelPct() < 0) {
            throw new IllegalArgumentException("Операция «" + label + "»: фиксированный уровень не может быть отрицательным");
        }
        if (o.getMode() == OperationSpec.Mode.OPEN) {
            if (o.getExpectedDurationSec() == null || o.getExpectedDurationSec() <= 0) {
                throw new IllegalArgumentException("Операция «" + label + "»: укажите ожидаемую длительность операции, сек (нужна для расчёта VU)");
            }
        } else {
            if (o.getPacingMs() == null || o.getPacingMs() <= 0) {
                throw new IllegalArgumentException("Операция «" + label + "»: для закрытой модели нужен pacing, мс");
            }
            if (o.getClosedInput() == ModelForm.ClosedInput.VU_PACING && (o.getVusOverride() == null || o.getVusOverride() <= 0)) {
                throw new IllegalArgumentException("Операция «" + label + "»: укажите число VU");
            }
        }
        boolean needsIntensity = o.isEnabled() && !(o.getMode() == OperationSpec.Mode.CLOSED
                && o.getClosedInput() == ModelForm.ClosedInput.VU_PACING);
        if (needsIntensity && o.getIntensityValue() == null && o.getSharePct() == null) {
            throw new IllegalArgumentException("Операция «" + label + "»: укажите интенсивность или долю");
        }
        if (o.getIntensityValue() != null && o.getIntensityValue() < 0) {
            throw new IllegalArgumentException("Операция «" + label + "»: интенсивность не может быть отрицательной");
        }
        ModelForm.parseKv(o.getEnvText());
        ModelForm.parseKv(o.getTagsText());
    }

    @Transactional
    public LoadModel clone(Long id, String newName) {
        LoadModel src = get(id);
        if (newName == null || newName.isBlank()) {
            newName = src.getName() + " (копия)";
        }
        if (models.existsByNameIgnoreCase(newName.trim())) {
            throw new IllegalArgumentException("Модель с таким именем уже есть");
        }
        LoadModel c = new LoadModel();
        c.setName(newName.trim());
        c.setDescription(src.getDescription());
        c.setScriptId(src.getScriptId());
        c.setTemplate(false);
        c.setTotalPerSec(src.getTotalPerSec());
        for (LoadOperation o : src.getOperations()) {
            LoadOperation n = new LoadOperation();
            n.setModel(c);
            n.setPosition(o.getPosition());
            n.setName(o.getName());
            n.setExecFn(o.getExecFn());
            n.setMode(o.getMode());
            n.setIntensityValue(o.getIntensityValue());
            n.setIntensityUnit(o.getIntensityUnit());
            n.setSharePct(o.getSharePct());
            n.setExpectedDurationSec(o.getExpectedDurationSec());
            n.setPacingMs(o.getPacingMs());
            n.setVusOverride(o.getVusOverride());
            n.setPreAllocOverride(o.getPreAllocOverride());
            n.setMaxVusOverride(o.getMaxVusOverride());
            n.setSlaPercentile(o.getSlaPercentile());
            n.setSlaThresholdMs(o.getSlaThresholdMs());
            n.setMaxErrorPct(o.getMaxErrorPct());
            n.setFailureSource(o.getFailureSource());
            n.setEnv(new java.util.LinkedHashMap<>(o.getEnv()));
            n.setTags(new java.util.LinkedHashMap<>(o.getTags()));
            n.setEnabled(o.isEnabled());
            n.setFixedLevelPct(o.getFixedLevelPct());
            c.getOperations().add(n);
        }
        LoadModel saved = models.save(c);
        audit.record("model.clone", "load_model", saved.getId(), Map.of("from", id));
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        if (usedByScenarios(id)) {
            throw new IllegalStateException("Модель используется в сценариях");
        }
        models.deleteById(id);
        audit.record("model.delete", "load_model", id, null);
    }

    public boolean usedByScenarios(Long modelId) {
        Integer n = jdbc.queryForObject("select count(*) from scenario where model_id = ?", Integer.class, modelId);
        return n != null && n > 0;
    }

    /** Форма для редактирования существующей модели. */
    @Transactional(readOnly = true)
    public ModelForm toForm(Long id) {
        LoadModel m = get(id);
        ModelForm f = new ModelForm();
        f.setId(m.getId());
        f.setName(m.getName());
        f.setDescription(m.getDescription());
        f.setScriptId(m.getScriptId());
        f.setTemplate(m.isTemplate());
        f.setInputMode(ModelForm.InputMode.ABS);
        f.setTotalUnit(RateUnit.PER_HOUR);
        f.setTotalValue(round2(RateUnit.PER_HOUR.fromPerSec(m.getTotalPerSec())));
        for (LoadOperation o : m.getOperations()) {
            ModelForm.OpForm of = new ModelForm.OpForm();
            of.setName(o.getName());
            of.setExecFn(o.getExecFn());
            of.setMode(o.getMode());
            of.setIntensityValue(o.getIntensityValue());
            of.setIntensityUnit(o.getIntensityUnit());
            of.setSharePct(round2(o.getSharePct()));
            of.setExpectedDurationSec(o.getExpectedDurationSec());
            of.setPacingMs(o.getPacingMs());
            of.setClosedInput(o.getVusOverride() != null ? ModelForm.ClosedInput.VU_PACING : ModelForm.ClosedInput.RATE_PACING);
            of.setVusOverride(o.getVusOverride());
            of.setPreAllocOverride(o.getPreAllocOverride());
            of.setMaxVusOverride(o.getMaxVusOverride());
            of.setSlaPercentile(o.getSlaPercentile());
            of.setSlaThresholdMs(o.getSlaThresholdMs());
            of.setMaxErrorPct(o.getMaxErrorPct());
            of.setFailureSource(o.getFailureSource());
            of.setEnvText(ModelForm.formatKv(o.getEnv()));
            of.setTagsText(ModelForm.formatKv(o.getTags()));
            of.setEnabled(o.isEnabled());
            of.setFixedLevelPct(o.getFixedLevelPct());
            f.getOps().add(of);
        }
        return f;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
