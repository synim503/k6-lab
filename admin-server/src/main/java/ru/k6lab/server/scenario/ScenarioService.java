package ru.k6lab.server.scenario;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.k6lab.core.analysis.StepAnalysis;
import ru.k6lab.core.analysis.StopCriteriaEvaluator;
import ru.k6lab.server.audit.AuditService;
import ru.k6lab.server.loadmodel.LoadModel;
import ru.k6lab.server.loadmodel.LoadModelService;
import ru.k6lab.server.loadmodel.ModelForm;
import ru.k6lab.server.profile.ProfileService;
import ru.k6lab.server.profile.TestProfile;
import ru.k6lab.server.script.Script;
import ru.k6lab.server.script.ScriptService;
import ru.k6lab.server.script.ScriptVersion;
import ru.k6lab.server.sut.SutRepository;
import ru.k6lab.server.testdata.ScenarioDataFile;
import ru.k6lab.server.testdata.ScenarioDataFileRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ScenarioService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ScenarioRepository scenarios;
    private final ScenarioDataFileRepository dataFiles;
    private final ScriptService scripts;
    private final LoadModelService models;
    private final ProfileService profiles;
    private final SutRepository suts;
    private final SecretService secrets;
    private final AuditService audit;

    public ScenarioService(ScenarioRepository scenarios, ScenarioDataFileRepository dataFiles, ScriptService scripts,
                           LoadModelService models, ProfileService profiles, SutRepository suts, SecretService secrets,
                           AuditService audit) {
        this.scenarios = scenarios;
        this.dataFiles = dataFiles;
        this.scripts = scripts;
        this.models = models;
        this.profiles = profiles;
        this.suts = suts;
        this.secrets = secrets;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Scenario> list() {
        return scenarios.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public Scenario get(Long id) {
        return scenarios.findById(id).orElseThrow(() -> new IllegalArgumentException("Сценарий не найден"));
    }

    @Transactional(readOnly = true)
    public List<ScenarioDataFile> dataFiles(Long scenarioId) {
        return dataFiles.findByScenarioIdOrderByPathAsc(scenarioId);
    }

    @Transactional
    public Scenario save(ScenarioForm f) {
        if (f.getName() == null || f.getName().isBlank()) {
            throw new IllegalArgumentException("Укажите имя сценария");
        }
        Script script = scripts.get(requireId(f.getScriptId(), "Выберите скрипт"));
        LoadModel model = models.get(requireId(f.getModelId(), "Выберите модель нагрузки"));
        TestProfile profile = profiles.get(requireId(f.getProfileId(), "Выберите профиль теста"));
        if (!model.getScriptId().equals(script.getId())) {
            throw new IllegalArgumentException("Модель «" + model.getName() + "» относится к другому скрипту");
        }
        Scenario s;
        if (f.getId() == null) {
            if (scenarios.existsByNameIgnoreCase(f.getName().trim())) {
                throw new IllegalArgumentException("Сценарий с таким именем уже есть");
            }
            s = new Scenario();
        } else {
            s = get(f.getId());
            if (!s.getName().equalsIgnoreCase(f.getName().trim()) && scenarios.existsByNameIgnoreCase(f.getName().trim())) {
                throw new IllegalArgumentException("Сценарий с таким именем уже есть");
            }
        }

        // версия скрипта
        ScriptVersion pinned = null;
        switch (f.getVersionMode()) {
            case PINNED -> {
                if (f.getScriptVersionId() == null) {
                    throw new IllegalArgumentException("Выберите версию скрипта");
                }
                pinned = scripts.version(script.getId(), f.getScriptVersionId());
                if (!pinned.isValidForModel()) {
                    throw new IllegalArgumentException("Версия v" + pinned.getNumber() + " непригодна для запуска через модель: "
                            + pinned.getInvalidReason());
                }
            }
            case FOLLOW_BRANCH -> {
                if (script.getGitBranch() == null) {
                    throw new IllegalArgumentException("«Последняя версия ветки Git» доступна только для скриптов, импортированных из ветки Git");
                }
            }
            default -> {
            }
        }

        Map<String, String> env = ModelForm.parseKv(f.getEnvText());
        secrets.requireExist(SecretService.referencedNames(env));
        JsonNode expert = null;
        if (f.isExpertMode()) {
            try {
                expert = MAPPER.readTree(f.getExpertConfigText() == null ? "" : f.getExpertConfigText());
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Экспертный конфиг: некорректный JSON (" + e.getOriginalMessage() + ")");
            }
            if (expert == null || !expert.isObject() || !expert.has("scenarios")) {
                throw new IllegalArgumentException("Экспертный конфиг должен быть JSON-объектом со scenarios");
            }
        }
        if (f.getSutId() != null && !suts.existsById(f.getSutId())) {
            throw new IllegalArgumentException("SUT не найден");
        }

        s.setName(f.getName().trim());
        s.setDescription(f.getDescription() == null || f.getDescription().isBlank() ? null : f.getDescription().trim());
        s.setScriptId(script.getId());
        s.setScriptVersionId(pinned == null ? null : pinned.getId());
        s.setFollowBranch(f.getVersionMode() == ScenarioForm.VersionMode.FOLLOW_BRANCH);
        s.setModelId(model.getId());
        s.setProfileId(profile.getId());
        s.setEnv(env);
        s.setExpertMode(f.isExpertMode());
        s.setExpertConfig(expert);
        s.setSutId(f.getSutId());
        s.setTemplate(f.isTemplate());
        s.setStopCriteria(f.getStopMode() == ScenarioForm.StopMode.AUTO ? new LinkedHashMap<>() : ScenarioCriteria.stopToMap(
                new StopCriteriaEvaluator.Config(f.isStopPercentile(), orDefault(f.getStopSlaMultiplier(), 1.5), f.isStopErrors(),
                        f.isStopRate(), orDefault(f.getStopMinRatePct(), 90.0), f.isStopDropped(),
                        f.getStopConsecutive() == null || f.getStopConsecutive() < 1 ? 3 : f.getStopConsecutive(),
                        f.getStopWindowSec() == null || f.getStopWindowSec() < 15 ? 60 : f.getStopWindowSec())));
        s.setSuccessCriteria(ScenarioCriteria.successToMap(new StepAnalysis.SuccessCriteria(
                orDefault(f.getSuccessMinRatePct(), 95.0), orDefault(f.getSuccessMaxDropped(), 0.0),
                orDefault(f.getSuccessMaxCpuPct(), 80.0))));
        Scenario saved = scenarios.save(s);

        // режимы тестовых данных: только файлы данных выбранной версии
        ScriptVersion dataVersion = pinned != null ? pinned : scripts.latest(script.getId()).orElse(null);
        dataFiles.deleteByScenarioId(saved.getId());
        dataFiles.flush();
        if (dataVersion != null) {
            java.util.Set<String> dataPaths = new java.util.HashSet<>();
            dataVersion.getFiles().stream().filter(x -> x.kind().equals("DATA")).forEach(x -> dataPaths.add(x.path()));
            for (ScenarioForm.DataForm d : f.getDataFiles()) {
                if (d == null || d.getPath() == null || !dataPaths.contains(d.getPath())) {
                    continue;
                }
                ScenarioDataFile df = new ScenarioDataFile();
                df.setScenarioId(saved.getId());
                df.setPath(d.getPath());
                df.setMode(d.getMode());
                df.setHasHeader(d.isHasHeader());
                df.setConsumed(d.isConsumed());
                dataFiles.save(df);
            }
        }
        audit.record(f.getId() == null ? "scenario.create" : "scenario.update", "scenario", saved.getId(), Map.of("name", saved.getName()));
        return saved;
    }

    @Transactional
    public Scenario clone(Long id, String newName) {
        Scenario src = get(id);
        String name = newName == null || newName.isBlank() ? src.getName() + " (копия)" : newName.trim();
        if (scenarios.existsByNameIgnoreCase(name)) {
            throw new IllegalArgumentException("Сценарий с таким именем уже есть");
        }
        Scenario c = new Scenario();
        c.setName(name);
        c.setDescription(src.getDescription());
        c.setScriptId(src.getScriptId());
        c.setScriptVersionId(src.getScriptVersionId());
        c.setFollowBranch(src.isFollowBranch());
        c.setModelId(src.getModelId());
        c.setProfileId(src.getProfileId());
        c.setEnv(new LinkedHashMap<>(src.getEnv()));
        c.setExpertMode(src.isExpertMode());
        c.setExpertConfig(src.getExpertConfig());
        c.setStopCriteria(new LinkedHashMap<>(src.getStopCriteria()));
        c.setSuccessCriteria(new LinkedHashMap<>(src.getSuccessCriteria()));
        c.setSutId(src.getSutId());
        Scenario saved = scenarios.save(c);
        for (ScenarioDataFile d : dataFiles.findByScenarioIdOrderByPathAsc(id)) {
            ScenarioDataFile n = new ScenarioDataFile();
            n.setScenarioId(saved.getId());
            n.setPath(d.getPath());
            n.setMode(d.getMode());
            n.setHasHeader(d.isHasHeader());
            n.setConsumed(d.isConsumed());
            dataFiles.save(n);
        }
        audit.record("scenario.clone", "scenario", saved.getId(), Map.of("from", id));
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        scenarios.deleteById(id);
        audit.record("scenario.delete", "scenario", id, null);
    }

    /** Данные формы для редактирования. */
    @Transactional(readOnly = true)
    public ScenarioForm toForm(Long id) {
        Scenario s = get(id);
        ScenarioForm f = new ScenarioForm();
        f.setId(s.getId());
        f.setName(s.getName());
        f.setDescription(s.getDescription());
        f.setScriptId(s.getScriptId());
        f.setVersionMode(s.isFollowBranch() ? ScenarioForm.VersionMode.FOLLOW_BRANCH
                : s.getScriptVersionId() != null ? ScenarioForm.VersionMode.PINNED : ScenarioForm.VersionMode.LATEST);
        f.setScriptVersionId(s.getScriptVersionId());
        f.setModelId(s.getModelId());
        f.setProfileId(s.getProfileId());
        f.setEnvText(ModelForm.formatKv(s.getEnv()));
        f.setExpertMode(s.isExpertMode());
        f.setExpertConfigText(s.getExpertConfig() == null ? "" : s.getExpertConfig().toPrettyString());
        f.setSutId(s.getSutId());
        f.setTemplate(s.isTemplate());
        if (!s.getStopCriteria().isEmpty()) {
            StopCriteriaEvaluator.Config c = ScenarioCriteria.stop(s.getStopCriteria(), profiles.get(s.getProfileId()).getType());
            f.setStopMode(ScenarioForm.StopMode.MANUAL);
            f.setStopPercentile(c.percentileEnabled());
            f.setStopSlaMultiplier(c.slaMultiplier());
            f.setStopErrors(c.errorsEnabled());
            f.setStopRate(c.rateEnabled());
            f.setStopMinRatePct(c.minRatePct());
            f.setStopDropped(c.droppedEnabled());
            f.setStopConsecutive(c.consecutive());
            f.setStopWindowSec(c.windowSec());
        }
        StepAnalysis.SuccessCriteria sc = ScenarioCriteria.success(s.getSuccessCriteria());
        f.setSuccessMinRatePct(sc.minRatePct());
        f.setSuccessMaxDropped(sc.maxDropped());
        f.setSuccessMaxCpuPct(sc.maxCpuPct());
        for (ScenarioDataFile d : dataFiles.findByScenarioIdOrderByPathAsc(id)) {
            ScenarioForm.DataForm df = new ScenarioForm.DataForm();
            df.setPath(d.getPath());
            df.setMode(d.getMode());
            df.setHasHeader(d.isHasHeader());
            df.setConsumed(d.isConsumed());
            f.getDataFiles().add(df);
        }
        return f;
    }

    /** Строки файлов данных для формы: файлы данных версии с уже заданными режимами. */
    public List<ScenarioForm.DataForm> dataRowsFor(Long scriptId, Long versionId, List<ScenarioForm.DataForm> existing) {
        Optional<ScriptVersion> v = versionId != null ? Optional.of(scripts.version(scriptId, versionId)) : scripts.latest(scriptId);
        List<ScenarioForm.DataForm> out = new ArrayList<>();
        if (v.isEmpty()) {
            return out;
        }
        for (ScriptVersion.FileEntry fe : v.get().getFiles()) {
            if (!fe.kind().equals("DATA")) {
                continue;
            }
            ScenarioForm.DataForm row = existing == null ? null : existing.stream().filter(d -> fe.path().equals(d.getPath())).findFirst().orElse(null);
            if (row == null) {
                row = new ScenarioForm.DataForm();
                row.setPath(fe.path());
                row.setHasHeader(fe.path().toLowerCase().endsWith(".csv"));
            }
            out.add(row);
        }
        return out;
    }

    private static Long requireId(Long id, String message) {
        if (id == null) {
            throw new IllegalArgumentException(message);
        }
        return id;
    }

    private static double orDefault(Double v, double def) {
        return v == null ? def : v;
    }
}
