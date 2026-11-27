package ru.k6lab.server.git;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

class GitHostKeyResolutionTest {

    @Test
    void prefersLogicalHostnameOverResolvedIpAddress() {
        InetSocketAddress rawIp = new InetSocketAddress("140.82.121.4", 22);
        assertThat(GitClient.resolveHostName("github.com", null, rawIp)).isEqualTo("github.com");
        assertThat(GitClient.resolveHostName(null, "[gitlab.corp.local]:2222", rawIp)).isEqualTo("gitlab.corp.local");
        assertThat(GitClient.resolveHostName(null, "gitverse.ru:22", rawIp)).isEqualTo("gitverse.ru");
        assertThat(GitClient.resolveHostName(null, "", rawIp)).isEqualTo("140.82.121.4");
    }
}
