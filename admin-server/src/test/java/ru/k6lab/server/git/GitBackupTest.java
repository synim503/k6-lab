package ru.k6lab.server.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.storage.file.WindowCacheConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
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

    @TempDir(cleanup = CleanupMode.NEVER)
    Path tempDir;

    @AfterEach
    void releaseJGitPackCache() {
        new WindowCacheConfig().install();
    }

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

    @Test
    void pushScriptToTargetCodeOnlyPushesFilesAndBindsScript() throws Exception {
        Path remoteDir = tempDir.resolve("code-remote.git");
        Git.init().setBare(true).setDirectory(remoteDir.toFile()).call().close();
        String remoteUrl = remoteDir.toUri().toString();

        AppProperties props = new AppProperties();
        props.setStorageDir(tempDir.resolve("storage-code"));
        GitClient client = new GitClient(props, mock(ru.k6lab.server.ssh.KnownHostService.class));

        GitRepoService repoService = mock(GitRepoService.class);
        GitRepo repo = new GitRepo();
        repo.setId(55L);
        repo.setName("scenarios-repo");
        repo.setUrl(remoteUrl);
        org.mockito.Mockito.when(repoService.get(55L)).thenReturn(repo);
        org.mockito.Mockito.when(repoService.authFor(repo)).thenReturn(GitClient.Auth.none());

        ru.k6lab.server.script.ScriptService scriptService = mock(ru.k6lab.server.script.ScriptService.class);
        ru.k6lab.server.script.Script script = new ru.k6lab.server.script.Script();
        script.setId(7L);
        script.setName("Checkout API");
        ru.k6lab.server.script.ScriptVersion ver = new ru.k6lab.server.script.ScriptVersion();
        ver.setId(70L);
        ver.setScriptId(7L);
        ver.setNumber(3);
        ver.setEntrypoint("main.js");

        byte[] zip = ru.k6lab.core.util.ZipUtil.write(Map.of(
                "main.js", "export default function checkout() {}\n".getBytes(StandardCharsets.UTF_8),
                "data.csv", "id\n1\n".getBytes(StandardCharsets.UTF_8)
        ));
        org.mockito.Mockito.when(scriptService.get(7L)).thenReturn(script);
        org.mockito.Mockito.when(scriptService.latest(7L)).thenReturn(java.util.Optional.of(ver));
        org.mockito.Mockito.when(scriptService.hasFiles(ver)).thenReturn(true);
        org.mockito.Mockito.when(scriptService.zipBytes(ver)).thenReturn(zip);

        GitBackupService svc = new GitBackupService(
                mock(ru.k6lab.server.settings.SettingsService.class),
                repoService,
                client,
                scriptService,
                mock(ru.k6lab.server.load.LoadService.class),
                mock(ru.k6lab.server.load.LoadProfileStore.class),
                mock(ru.k6lab.server.result.ChartTemplateStore.class),
                mock(ru.k6lab.server.result.ChartVariableStore.class),
                mock(ru.k6lab.server.script.StorageHealth.class),
                mock(ru.k6lab.server.audit.AuditService.class)
        );

        var outcome = svc.pushScriptToTarget(7L, 55L, "main", "checkout", "CODE_ONLY", "push checkout v3", true, "admin");
        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.changed()).isTrue();
        org.mockito.Mockito.verify(scriptService).bindGit(7L, 55L, "main", "checkout/main.js");

        client.sync(56L, remoteUrl, GitClient.Auth.none());
        Map<String, byte[]> pushed = client.readBranchFiles(56L, "main", "checkout");
        assertThat(pushed.keySet()).containsExactlyInAnyOrder("checkout/main.js", "checkout/data.csv");
    }
}
