package ru.k6lab.server.generator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import ru.k6lab.server.common.Auditable;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "generator")
public class Generator extends Auditable {

    public enum Status {
        INSTALLING("Установка"), ONLINE("Онлайн"), OFFLINE("Оффлайн"), ERROR("Ошибка"), REMOVING("Удаление");

        private final String title;

        Status(String title) {
            this.title = title;
        }

        public String getTitle() {
            return title;
        }
    }

    public enum InstallMode { SYSTEM, USER }

    /** Чем поддерживается жизнь агента на хосте. */
    public enum Supervisor { SYSTEMD, SYSTEMD_USER, CRON }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private String host;

    @Column(name = "ssh_port", nullable = false)
    private int sshPort = 22;

    @Column(name = "ssh_credential_id")
    private Long sshCredentialId;

    @Enumerated(EnumType.STRING)
    @Column(name = "install_mode", nullable = false)
    private InstallMode installMode = InstallMode.SYSTEM;

    @Column(name = "install_dir", nullable = false)
    private String installDir;

    @Column(name = "server_url", nullable = false)
    private String serverUrl;

    @Column(name = "max_concurrent_runs", nullable = false)
    private int maxConcurrentRuns = 1;

    @Column(name = "tune_os", nullable = false)
    private boolean tuneOs;

    @Enumerated(EnumType.STRING)
    private Supervisor supervisor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.INSTALLING;

    @Column(nullable = false)
    private boolean maintenance;

    @Column(name = "agent_version")
    private String agentVersion;

    @Column(name = "k6_version")
    private String k6Version;

    private String arch;

    @Column(name = "os_name")
    private String osName;

    @Column(name = "one_time_token_hash")
    private String oneTimeTokenHash;

    @Column(name = "one_time_expires")
    private Instant oneTimeExpires;

    @Column(name = "agent_token_hash")
    private String agentTokenHash;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "last_hello_at")
    private Instant lastHelloAt;

    @Column(name = "clock_offset_ms")
    private Long clockOffsetMs;

    @Column(name = "last_seq", nullable = false)
    private long lastSeq;

    @Column(name = "active_runs", nullable = false)
    private int activeRuns;

    public boolean isBusy() {
        return activeRuns > 0;
    }

    /** Генератор может принимать новые run. */
    public boolean isAvailableForRuns() {
        return status == Status.ONLINE && !maintenance && activeRuns < maxConcurrentRuns;
    }
}
