package ru.k6lab.agent.sys;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;
import ru.k6lab.agent.config.AgentProperties;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Версии агента и установленного k6. */
@Component
public class VersionInfo {

    private static final Pattern K6_VERSION = Pattern.compile("k6(?:\\.exe)?\\s+v?(\\S+)");

    private final String agentVersion;
    private final AgentProperties props;
    private volatile String k6Version;
    private volatile long k6CheckedAt;

    public VersionInfo(ObjectProvider<BuildProperties> build, AgentProperties props) {
        BuildProperties bp = build.getIfAvailable();
        this.agentVersion = bp != null ? bp.getVersion() : "dev";
        this.props = props;
    }

    public String agentVersion() {
        return agentVersion;
    }

    /** Версия k6 (кеш на 60 секунд); null, если k6 не установлен. */
    public String k6Version() {
        long now = System.currentTimeMillis();
        if (now - k6CheckedAt < 60_000) {
            return k6Version;
        }
        k6CheckedAt = now;
        k6Version = detect();
        return k6Version;
    }

    private String detect() {
        if (!Files.isExecutable(props.getK6Path())) {
            return null;
        }
        try {
            Process p = new ProcessBuilder(props.getK6Path().toString(), "version")
                    .redirectErrorStream(true).start();
            String out;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                out = r.readLine();
            }
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
            if (out == null) {
                return null;
            }
            Matcher m = K6_VERSION.matcher(out);
            return m.find() ? m.group(1) : out.trim();
        } catch (Exception e) {
            return null;
        }
    }
}
