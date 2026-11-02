package ru.k6lab.server.deploy;

import ru.k6lab.server.distribution.DistributionArtifact;
import ru.k6lab.server.generator.Generator;

import java.util.ArrayList;
import java.util.List;

/** Результат pre-flight проверок хоста. */
public class PreflightReport {

    public String uname = "";
    public DistributionArtifact.Arch arch;
    public String osName = "";
    public boolean systemd;
    public boolean sudoOk = true;
    public boolean linger;
    public boolean cron;
    public String home = "";
    public long diskFreeKb = -1;
    public Boolean portReachable;
    public Long clockSkewMs;
    public boolean existingInstall;
    public Generator.Supervisor supervisor;
    public final List<String> warnings = new ArrayList<>();
    public final List<String> errors = new ArrayList<>();

    public boolean ok() {
        return errors.isEmpty();
    }

    public void warn(String message) {
        warnings.add(message);
    }

    public void error(String message) {
        errors.add(message);
    }
}
