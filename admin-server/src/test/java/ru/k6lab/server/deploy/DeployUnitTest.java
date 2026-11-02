package ru.k6lab.server.deploy;

import org.junit.jupiter.api.Test;
import ru.k6lab.server.generator.Generator;
import ru.k6lab.server.ssh.SshConnection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeployUnitTest {

    private static DeployEngine.Target target(String host, String dir, String url, Generator.InstallMode mode) {
        return new DeployEngine.Target(1L, "gen-01", host, 22, 1L, mode, dir, url, false, null);
    }

    @Test
    void validatesTargetFields() {
        DeployEngine.validate(target("10.0.0.1", "/opt/k6-agent", "ws://k6admin:47331/agent/ws", Generator.InstallMode.SYSTEM));
        DeployEngine.validate(target("host.example", "~/k6-agent", "ws://10.1.1.1:47331/agent/ws", Generator.InstallMode.USER));

        assertThatThrownBy(() -> DeployEngine.validate(target("a;rm -rf /", "/opt/k6", "ws://h:1/agent/ws", Generator.InstallMode.SYSTEM)))
                .hasMessageContaining("хоста");
        assertThatThrownBy(() -> DeployEngine.validate(target("h", "/opt/k6; reboot", "ws://h:1/agent/ws", Generator.InstallMode.SYSTEM)))
                .hasMessageContaining("каталог");
        assertThatThrownBy(() -> DeployEngine.validate(target("h", "/", "ws://h:1/agent/ws", Generator.InstallMode.SYSTEM)))
                .hasMessageContaining("каталог");
        assertThatThrownBy(() -> DeployEngine.validate(target("h", "relative/dir", "ws://h:1/agent/ws", Generator.InstallMode.SYSTEM)))
                .hasMessageContaining("абсолютный");
        assertThatThrownBy(() -> DeployEngine.validate(target("h", "/opt/k6-agent", "http://h/agent/ws", Generator.InstallMode.SYSTEM)))
                .hasMessageContaining("URL сервера");
        assertThatThrownBy(() -> DeployEngine.validate(target("h", "/opt/k6-agent", "ws://h/agent/ws", Generator.InstallMode.SYSTEM)))
                .hasMessageContaining("URL сервера");
    }

    @Test
    void validatesGeneratorName() {
        var bad = new DeployEngine.Target(1L, "bad name!", "h", 22, 1L, Generator.InstallMode.SYSTEM, "/opt/k6-agent",
                "ws://h:1/agent/ws", false, null);
        assertThatThrownBy(() -> DeployEngine.validate(bad)).hasMessageContaining("Имя");
    }

    @Test
    void shellQuotingNeutralisesQuotes() {
        assertThat(SshConnection.quote("a'b")).isEqualTo("'a'\\''b'");
        assertThat(SshConnection.quote("plain")).isEqualTo("'plain'");
        assertThat(SshConnection.quote("$(reboot)")).isEqualTo("'$(reboot)'");
    }

    @Test
    void unitsContainRequiredHardening() {
        String unit = Units.systemUnit("/opt/k6-agent", false);
        assertThat(unit).contains("User=k6agent", "Restart=always", "LimitNOFILE=1048576", "-Xmx256m",
                "WantedBy=multi-user.target");
        assertThat(Units.userUnit("/home/u/k6-agent", false)).contains("WantedBy=default.target").doesNotContain("User=");
        assertThat(Units.sysctl()).contains("ip_local_port_range", "tcp_tw_reuse", "somaxconn", "fs.file-max");
        String cfg = Units.agentConfig("ws://h:47331/agent/ws", "tok", "gen-01", "/opt/k6-agent");
        assertThat(cfg).contains("agent.token=tok", "agent.agent-id=gen-01", "logging.file.name=/opt/k6-agent/logs/agent.log");
        assertThat(Units.ctlScript(false)).contains("ensure", "kill -9", "-Xmx256m");
    }

    @Test
    void nativeGoAgentIsStartedWithoutJre() {
        String unit = Units.systemUnit("/opt/k6-agent", true);
        assertThat(unit).contains("ExecStart=/opt/k6-agent/k6-agent --agent.home=/opt/k6-agent").doesNotContain("java").doesNotContain("jre");
        assertThat(Units.userUnit("/home/u/k6-agent", true)).contains("ExecStart=/home/u/k6-agent/k6-agent").doesNotContain("java");
        String ctl = Units.ctlScript(true);
        assertThat(ctl).contains("\"$DIR/k6-agent\" --agent.home=\"$DIR\"").doesNotContain("java").doesNotContain("@@CMD@@");
        assertThat(Units.ctlScript(false)).contains("jre/bin/java").doesNotContain("@@CMD@@");
    }
}
