package ru.k6lab.server.scenario;

import lombok.Getter;
import lombok.Setter;
import ru.k6lab.server.testdata.ScenarioDataFile;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class ScenarioForm {

    public enum VersionMode { LATEST, PINNED, FOLLOW_BRANCH }

    public enum StopMode { AUTO, MANUAL }

    private Long id;
    private String name;
    private String description;
    private Long scriptId;
    private VersionMode versionMode = VersionMode.LATEST;
    private Long scriptVersionId;
    private Long modelId;
    private Long profileId;
    private String envText;
    private boolean expertMode;
    private String expertConfigText;
    private Long sutId;
    private boolean template;

    private StopMode stopMode = StopMode.AUTO;
    private boolean stopPercentile;
    private Double stopSlaMultiplier = 1.5;
    private boolean stopErrors;
    private boolean stopRate;
    private Double stopMinRatePct = 90.0;
    private boolean stopDropped;
    private Integer stopConsecutive = 3;
    private Integer stopWindowSec = 60;

    private Double successMinRatePct = 95.0;
    private Double successMaxDropped = 0.0;
    private Double successMaxCpuPct = 80.0;

    private List<DataForm> dataFiles = new ArrayList<>();

    @Getter
    @Setter
    public static class DataForm {
        private String path;
        private ScenarioDataFile.Mode mode = ScenarioDataFile.Mode.SHARED;
        private boolean hasHeader;
        private boolean consumed = true;
    }
}
