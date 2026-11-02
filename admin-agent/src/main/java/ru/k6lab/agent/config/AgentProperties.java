package ru.k6lab.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Настройки агента. Источник: {@code agent.properties} в рабочем каталоге (создаётся при установке, права 600).
 */
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    /** Каталог установки агента (agent.jar, bin/k6, data/, logs/). */
    private Path home = Path.of(".").toAbsolutePath().normalize();
    /** URL WebSocket сервера, например ws://k6admin:47331/agent/ws. */
    private String serverUrl;
    /** Одноразовый токен регистрации, после первого подключения заменяется постоянным. */
    private String token;
    /** Идентификатор агента (имя генератора). */
    private String agentId = "unknown";
    /** Путь к бинарю k6. По умолчанию home/bin/k6. */
    private Path k6Path;
    private Duration heartbeatInterval = Duration.ofSeconds(5);
    private Duration reconnectMin = Duration.ofSeconds(1);
    private Duration reconnectMax = Duration.ofSeconds(60);
    /** Максимальный размер дискового журнала событий. */
    private long journalMaxBytes = 256L * 1024 * 1024;

    public Path getHome() {
        return home;
    }

    public void setHome(Path home) {
        this.home = home;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public Path getK6Path() {
        return k6Path != null ? k6Path : home.resolve("bin").resolve("k6");
    }

    public void setK6Path(Path k6Path) {
        this.k6Path = k6Path;
    }

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    public Duration getReconnectMin() {
        return reconnectMin;
    }

    public void setReconnectMin(Duration reconnectMin) {
        this.reconnectMin = reconnectMin;
    }

    public Duration getReconnectMax() {
        return reconnectMax;
    }

    public void setReconnectMax(Duration reconnectMax) {
        this.reconnectMax = reconnectMax;
    }

    public long getJournalMaxBytes() {
        return journalMaxBytes;
    }

    public void setJournalMaxBytes(long journalMaxBytes) {
        this.journalMaxBytes = journalMaxBytes;
    }
}
