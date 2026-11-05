package ru.k6lab.server;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import ru.k6lab.core.util.ZipUtil;
import ru.k6lab.server.git.GitRepo;
import ru.k6lab.server.git.GitRepoService;
import ru.k6lab.server.script.K6Tool;
import ru.k6lab.server.script.Script;
import ru.k6lab.server.script.ScriptService;
import ru.k6lab.server.script.ScriptVersion;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Скрипты: загрузка, валидация (k6 inspect), версии, редактор, откат, Git (локальный репозиторий). */
class ScriptIT extends AbstractIT {

    @Autowired
    ScriptService scripts;
    @Autowired
    GitRepoService gitRepos;
    @Autowired
    K6Tool k6;
    @Autowired
    MockMvc mvc;

    private static String name() {
        return "s-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private ScriptVersion upload(String name, String filename, String content) throws IOException {
        return scripts.createFromUpload(name, "", filename, new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), "", null);
    }

    private static final String GOOD = """
            import http from 'k6/http';
            import { op, check } from './__admin/admin.js';

            export function createPayment() { op(() => { check(http.get('http://localhost/x'), { ok: (r) => r.status === 200 }); }); }
            export function getPayment() { op(() => { http.get('http://localhost/y'); }); }
            export function setup() {}
            export default function () {}
            """;

    @Test
    void validScriptGetsOperationsHelperFlagAndInspectResult() throws Exception {
        Assumptions.assumeTrue(k6.version().isPresent(), "k6 недоступен");
        ScriptVersion v = upload(name(), "payments.js", GOOD);
        assertThat(v.getNumber()).isEqualTo(1);
        assertThat(v.getEntrypoint()).isEqualTo("payments.js");
        assertThat(v.operationNames()).containsExactly("createPayment", "getPayment", "default");
        assertThat(v.isUsesHelper()).isTrue();
        assertThat(v.getInspectStatus()).isEqualTo(ScriptVersion.InspectStatus.OK);
        assertThat(v.getInspectResult()).containsKey("scenarios");
        assertThat(v.isValidForModel()).isTrue();
        assertThat(v.getInvalidReason()).isNull();
        assertThat(v.getSha256()).hasSize(64);
        assertThat(v.sourceLabel()).isEqualTo("загрузка");
    }

    @Test
    void loadOptionsAreCutFromTheSourceBecauseTheAdminSetsThemAtRun() throws Exception {
        Assumptions.assumeTrue(k6.version().isPresent(), "k6 недоступен");
        ScriptVersion vus = upload(name(), "a.js", "export const options = { vus: 10, duration: '1m' };\nexport function op1() {}\nexport default function () {}");
        assertThat(vus.isValidForModel()).as(String.valueOf(vus.getInvalidReason())).isTrue();
        assertThat(vus.getWarnings()).anyMatch(w -> w.contains("вырезано") && w.contains("vus") && w.contains("duration"));
        assertThat(vus.getInspectResult().get("vus")).isNull();

        ScriptVersion sc = upload(name(), "b.js", "export const options = { scenarios: { s: { executor: 'constant-vus', vus: 1, duration: '1s' } }, insecureSkipTLSVerify: true };\nexport default function () {}");
        assertThat(sc.isValidForModel()).isTrue();
        assertThat(sc.getWarnings()).anyMatch(w -> w.contains("scenarios"));
        // прочие опции остались
        assertThat(sc.getInspectResult().get("insecureSkipTLSVerify")).isEqualTo(true);

        ScriptVersion st = upload(name(), "c.js", "export const options = { stages: [{ duration: '1s', target: 1 }], iterations: 5, thresholds: { http_req_duration: ['p(95)<500'] }, tags: { a: 'b' } };\nexport default function () {}");
        assertThat(st.isValidForModel()).isTrue();
        assertThat(st.getWarnings()).anyMatch(w -> w.contains("stages") && w.contains("thresholds") && w.contains("tags"));

        // опции без таких полей скрипт не меняют и предупреждения не добавляют
        ScriptVersion ok = upload(name(), "d.js", "export const options = { insecureSkipTLSVerify: true };\nexport default function () {}");
        assertThat(ok.isValidForModel()).isTrue();
        assertThat(ok.getWarnings()).noneMatch(w -> w.contains("вырезано"));

        // options, собранные динамически, вырезать нельзя: версия остаётся заблокированной с объяснением
        ScriptVersion dyn = upload(name(), "e.js", "const o = { vus: 3, duration: '5s' };\nexport const options = o;\nexport default function () {}");
        assertThat(dyn.isValidForModel()).isFalse();
        assertThat(dyn.getInvalidReason()).contains("vus", "duration").contains("приоритет над --config");
    }

    @Test
    void brokenScriptFailsInspectAndIsBlocked() throws Exception {
        Assumptions.assumeTrue(k6.version().isPresent(), "k6 недоступен");
        ScriptVersion v = upload(name(), "broken.js", "export default function ( {");
        assertThat(v.getInspectStatus()).isEqualTo(ScriptVersion.InspectStatus.FAILED);
        assertThat(v.isValidForModel()).isFalse();
        assertThat(v.getInvalidReason()).contains("k6 inspect");
    }

    @Test
    void archiveUploadKeepsDataFilesStripsAdminDirAndUsesEntrypoint() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("proj/main.js", "import { op } from './__admin/admin.js'; import { u } from './lib/util.js'; export function pay() { op(() => u()); } export default function () {}".getBytes());
        files.put("proj/lib/util.js", "export function u() {}".getBytes());
        files.put("proj/data/users.csv", "login,password\nu1,p1\n".getBytes());
        files.put("proj/__admin/admin.js", "// пользовательская копия".getBytes());
        byte[] zip = ZipUtil.write(files);
        ScriptVersion v = scripts.createFromUpload(name(), "", "proj.zip", new ByteArrayInputStream(zip), "main.js", "архив");
        assertThat(v.getEntrypoint()).isEqualTo("main.js");
        assertThat(v.getFiles()).extracting(ScriptVersion.FileEntry::path)
                .containsExactly("data/users.csv", "lib/util.js", "main.js");
        assertThat(v.getFiles()).filteredOn(f -> f.path().equals("data/users.csv")).extracting(ScriptVersion.FileEntry::kind).containsExactly("DATA");
        assertThat(v.getWarnings()).anyMatch(w -> w.contains("__admin"));
        assertThat(v.isUsesHelper()).isTrue();
        assertThat(v.operationNames()).containsExactly("pay", "default");

        // без entrypoint при нескольких .js - понятная ошибка
        Map<String, byte[]> two = new LinkedHashMap<>();
        two.put("a.js", "export default function(){}".getBytes());
        two.put("b.js", "export default function(){}".getBytes());
        assertThatThrownBy(() -> scripts.createFromUpload(name(), "", "x.zip", new ByteArrayInputStream(ZipUtil.write(two)), "", null))
                .hasMessageContaining("Укажите entrypoint");
        // неверный entrypoint
        assertThatThrownBy(() -> scripts.createFromUpload(name(), "", "x.zip", new ByteArrayInputStream(zip), "nope.js", null))
                .hasMessageContaining("не найден");
    }

    @Test
    void tarGzArchiveIsConvertedToZip() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(bos))) {
            byte[] js = "export function a() {}\nexport default function () {}".getBytes();
            TarArchiveEntry e = new TarArchiveEntry("test/script.js");
            e.setSize(js.length);
            tar.putArchiveEntry(e);
            tar.write(js);
            tar.closeArchiveEntry();
        }
        ScriptVersion v = scripts.createFromUpload(name(), "", "bundle.tar.gz", new ByteArrayInputStream(bos.toByteArray()), "", null);
        assertThat(v.getEntrypoint()).isEqualTo("script.js");
        assertThat(ZipUtil.list(new ByteArrayInputStream(scripts.zipBytes(v)))).extracting(ZipUtil.Entry::path).containsExactly("script.js");
        assertThatThrownBy(() -> scripts.createFromUpload(name(), "", "x.exe", new ByteArrayInputStream(new byte[]{1}), "", null))
                .hasMessageContaining("Поддерживаются");
    }

    @Test
    void editorSaveRollbackAndManualOperations() throws Exception {
        ScriptVersion v1 = upload(name(), "m.js", "export function a() {}\nexport default function () {}\n");
        Long sid = v1.getScriptId();

        // правка в редакторе = новая версия, старая не меняется
        ScriptVersion v2 = scripts.saveEdit(sid, v1.getId(), "m.js", "export function a() {}\nexport function b() {}\nexport default function () {}\n", "добавил b");
        assertThat(v2.getNumber()).isEqualTo(2);
        assertThat(v2.getParentVersionId()).isEqualTo(v1.getId());
        assertThat(v2.getSource()).isEqualTo(ScriptVersion.Source.EDITOR);
        assertThat(v2.operationNames()).containsExactly("a", "b", "default");
        assertThat(scripts.version(sid, v1.getId()).operationNames()).containsExactly("a", "default");

        // откат = новая версия с содержимым старой
        ScriptVersion v3 = scripts.rollback(sid, v1.getId());
        assertThat(v3.getNumber()).isEqualTo(3);
        assertThat(v3.getSource()).isEqualTo(ScriptVersion.Source.ROLLBACK);
        assertThat(v3.getSha256()).isEqualTo(v1.getSha256());
        assertThat(v3.getComment()).contains("версии 1");

        // ручные операции
        ScriptVersion withManual = scripts.addOperation(sid, v3.getId(), "dynamicOp");
        assertThat(withManual.getOperations()).extracting(ScriptVersion.OpEntry::origin).contains("MANUAL");
        assertThatThrownBy(() -> scripts.addOperation(sid, v3.getId(), "bad name")).isInstanceOf(IllegalArgumentException.class);
        assertThat(scripts.removeOperation(sid, v3.getId(), "dynamicOp").operationNames()).doesNotContain("dynamicOp");
        assertThatThrownBy(() -> scripts.saveEdit(sid, v1.getId(), "nope.js", "x", null)).hasMessageContaining("нет в версии");
        assertThatThrownBy(() -> scripts.saveEdit(sid, v1.getId(), "../x.js", "x", null)).hasMessageContaining("Недопустимый");
    }

    @Test
    void duplicateNamesAndFailedCreationLeaveNoGarbage() throws Exception {
        String n = name();
        upload(n, "a.js", "export default function () {}");
        assertThatThrownBy(() -> upload(n, "b.js", "export default function () {}")).hasMessageContaining("уже есть");
        String n2 = name();
        assertThatThrownBy(() -> scripts.createFromUpload(n2, "", "x.zip", new ByteArrayInputStream(new byte[0]), "", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(scripts.list()).extracting(Script::getName).doesNotContain(n2);
    }

    @Test
    void gitImportPinsCommitAndDetectsNewCommits() throws Exception {
        Path repoDir = Files.createTempDirectory("k6lab-git-it");
        try (Git git = Git.init().setDirectory(repoDir.toFile()).setInitialBranch("main").call()) {
            Files.createDirectories(repoDir.resolve("perf/scripts"));
            Files.writeString(repoDir.resolve("perf/scripts/pay.js"), "export function pay() {}\nexport default function () {}\n");
            Files.writeString(repoDir.resolve("perf/data.csv"), "a\nb\n");
            Files.writeString(repoDir.resolve("README.md"), "ignored");
            git.add().addFilepattern(".").call();
            var c1 = git.commit().setMessage("first").setAuthor("dev", "dev@example.com").setCommitter("dev", "dev@example.com").call();

            GitRepo repo = gitRepos.save(new GitRepoService.Form(null, "it-repo-" + UUID.randomUUID().toString().substring(0, 6),
                    repoDir.toUri().toString(), GitRepo.AuthType.NONE, null, null, "perf", false));
            assertThat(gitRepos.refresh(repo.getId())).extracting(r -> r.name()).contains("main");

            ScriptVersion v1 = scripts.importFromGit(null, name(), repo.getId(), "main", "scripts/pay.js", "импорт");
            assertThat(v1.getSource()).isEqualTo(ScriptVersion.Source.GIT);
            assertThat(v1.getCommitHash()).isEqualTo(c1.getName());
            assertThat(v1.getGitBranch()).isEqualTo("main");
            assertThat(v1.sourceLabel()).startsWith("git main @ " + c1.getName().substring(0, 8));
            assertThat(v1.getFiles()).extracting(ScriptVersion.FileEntry::path).containsExactly("data.csv", "scripts/pay.js");
            Long sid = v1.getScriptId();

            // тот же коммит - та же версия
            assertThat(scripts.importFromGit(sid, null, repo.getId(), "main", "scripts/pay.js", null).getId()).isEqualTo(v1.getId());
            assertThat(scripts.checkGitUpdates(sid)).isEmpty();

            // новый коммит в ветке
            Files.writeString(repoDir.resolve("perf/scripts/pay.js"), "export function pay() {}\nexport function refund() {}\nexport default function () {}\n");
            git.add().addFilepattern(".").call();
            var c2 = git.commit().setMessage("add refund").setAuthor("dev", "dev@example.com").setCommitter("dev", "dev@example.com").call();

            var updates = scripts.checkGitUpdates(sid);
            assertThat(updates).singleElement().satisfies(c -> {
                assertThat(c.hash()).isEqualTo(c2.getName());
                assertThat(c.message()).isEqualTo("add refund");
            });
            // «последняя версия ветки» фиксирует коммит
            ScriptVersion latest = scripts.importLatestOfBranch(sid);
            assertThat(latest.getNumber()).isEqualTo(2);
            assertThat(latest.getCommitHash()).isEqualTo(c2.getName());
            assertThat(latest.operationNames()).containsExactly("pay", "refund", "default");
            assertThat(scripts.checkGitUpdates(sid)).isEmpty();

            // разрешение по тегу и по сокращённому hash
            git.tag().setName("v1").setObjectId(c1).call();
            gitRepos.refresh(repo.getId());
            ScriptVersion byTag = scripts.importFromGit(sid, null, repo.getId(), "v1", "scripts/pay.js", null);
            // коммит c1 уже импортирован (ветка main) - новая версия не создаётся
            assertThat(byTag.getId()).isEqualTo(v1.getId());
            assertThatThrownBy(() -> scripts.importFromGit(sid, null, repo.getId(), "no-such-ref", "scripts/pay.js", null))
                    .hasMessageContaining("не найдены");
            assertThatThrownBy(() -> scripts.importFromGit(sid, null, repo.getId(), "main", "scripts/missing.js", null))
                    .hasMessageContaining("не найден");
        }
    }

    @Test
    @WithMockUser(roles = "USER")
    void pagesRenderAndHelperIsDownloadable() throws Exception {
        ScriptVersion v = upload(name(), "page.js", GOOD);
        ScriptVersion v2 = scripts.saveEdit(v.getScriptId(), v.getId(), "page.js", GOOD + "\n// change\n", "c");
        String base = "/scripts/" + v.getScriptId();
        for (String path : new String[]{"/scripts", "/scripts/new", base, base + "/versions/" + v.getId(),
                base + "/versions/" + v.getId() + "/edit",
                "/git-repos"}) {
            mvc.perform(get(path)).andExpect(status().isOk());
        }
        mvc.perform(get(base + "/versions/" + v.getId() + "/file").param("path", "page.js"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("createPayment")));
        mvc.perform(get("/scripts/admin-helper")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("export function op")));
        mvc.perform(get(base + "/versions/" + v.getId() + "/download")).andExpect(status().isOk());
        // новую git-конфигурацию USER создавать не может, но смотреть может
        mvc.perform(get("/git-repos/new")).andExpect(status().isForbidden());
        mvc.perform(post("/scripts/" + v.getScriptId() + "/delete").with(csrf())).andExpect(status().is3xxRedirection());
    }
}
