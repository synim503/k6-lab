package ru.k6lab.server.git;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.k6lab.server.config.AppProperties;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GitBackupTest {

    @TempDir
    Path tempDir;

    @Test
    void commitAndPushToEmptyBareRemoteIsIdempotentAndSupportsPartialUpdate() throws Exception {
        Path remoteDir = tempDir.resolve("remote.git");
        Git.init().setBare(true).setDirectory(remoteDir.toFile()).call().close();
        String remoteUrl = remoteDir.toUri().toString();

        AppProperties props = new AppProperties();
        props.setStorageDir(tempDir.resolve("storage"));
        GitClient client = new GitClient(props, mock(ru.k6lab.server.ssh.KnownHostService.class));

        Map<String, byte[]> initial = new LinkedHashMap<>();
        initial.put("README.md", "# Backup\n".getBytes(StandardCharsets.UTF_8));
        initial.put("scripts/payments/src/main.js", "export default function() {}\n".getBytes(StandardCharsets.UTF_8));
        initial.put("scripts/payments/k6lab-script.json", "{\"name\":\"payments\"}\n".getBytes(StandardCharsets.UTF_8));
        initial.put("scripts/auth/src/login.js", "export function login() {}\n".getBytes(StandardCharsets.UTF_8));

        // 1. Первичная выгрузка в пустой удалённый репозиторий
        GitClient.PushResult first = client.commitAndPush(
                101L, remoteUrl, GitClient.Auth.none(), "backup",
                "scripts", initial, Set.of(), "admin", "initial backup"
        );
        assertThat(first.changed()).isTrue();
        assertThat(first.commit()).hasSize(40);

        // 2. Повторная выгрузка тех же файлов не создаёт пустой коммит
        GitClient.PushResult second = client.commitAndPush(
                101L, remoteUrl, GitClient.Auth.none(), "backup",
                "scripts", initial, Set.of(), "admin", "no-op backup"
        );
        assertThat(second.changed()).isFalse();
        assertThat(second.commit()).isEqualTo(first.commit());

        // 3. Обновление одного скрипта сохраняет соседний скрипт и удаляет устаревшие файлы внутри обновляемого
        Map<String, byte[]> singleUpdate = Map.of(
                "scripts/payments/src/v2.js", "export default function v2() {}\n".getBytes(StandardCharsets.UTF_8),
                "scripts/payments/k6lab-script.json", "{\"name\":\"payments\",\"versionNumber\":2}\n".getBytes(StandardCharsets.UTF_8)
        );
        GitClient.PushResult third = client.commitAndPush(
                101L, remoteUrl, GitClient.Auth.none(), "backup",
                "scripts/payments", singleUpdate, Set.of(), "admin", "update payments"
        );
        assertThat(third.changed()).isTrue();
        assertThat(third.commit()).isNotEqualTo(first.commit());

        // 4. Сверяем из независимого кеша, что пуш действительно дошёл до remote.git
        client.sync(102L, remoteUrl, GitClient.Auth.none());
        Map<String, byte[]> readBack = client.readBranchFiles(102L, "backup", "scripts");
        assertThat(readBack.keySet()).containsExactlyInAnyOrder(
                "scripts/auth/src/login.js",
                "scripts/payments/src/v2.js",
                "scripts/payments/k6lab-script.json"
        );
        assertThat(new String(readBack.get("scripts/payments/src/v2.js"), StandardCharsets.UTF_8))
                .contains("function v2()");
    }

    @Test
    void slugifyKeepsReadableUnicodeAndStripsUnsafeChars() {
        assertThat(GitBackupService.slugifyName("Платежи / API v2")).isEqualTo("Платежи-API-v2");
        assertThat(GitBackupService.slugifyName("  ../weird\\name..  ")).isEqualTo("weird-name");
    }
}
