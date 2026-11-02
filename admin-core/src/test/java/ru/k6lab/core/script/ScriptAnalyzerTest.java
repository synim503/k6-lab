package ru.k6lab.core.script;

import org.junit.jupiter.api.Test;
import ru.k6lab.core.util.ZipUtil;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScriptAnalyzerTest {

    @Test
    void extractsExportedFunctionsExcludingLifecycle() {
        var a = ScriptAnalyzer.analyze("""
                import http from 'k6/http';
                export const options = { thresholds: {} };
                export function createPayment() {}
                export async function getPayment() {}
                export const refund = () => {};
                export const cancel = async (id) => { };
                export const legacy = function () {};
                export const single = x => x;
                export const notAFunction = 42;
                export function setup() {}
                export function teardown() {}
                export function handleSummary(data) {}
                export default function () {}
                function internal() {}
                """);
        assertThat(a.operations()).extracting(ScriptAnalyzer.Operation::name)
                .containsExactly("createPayment", "getPayment", "refund", "cancel", "legacy", "single");
        assertThat(a.operations()).allMatch(o -> o.origin().equals("HEURISTIC"));
    }

    @Test
    void ignoresCommentedOutExportsButNotStrings() {
        var a = ScriptAnalyzer.analyze("""
                // export function hidden() {}
                /* export function alsoHidden() {} */
                const url = "http://example.com/export function fake() {}";
                export function real() {}
                """);
        assertThat(a.operations()).extracting(ScriptAnalyzer.Operation::name).contains("real").doesNotContain("hidden", "alsoHidden");
    }

    @Test
    void exportListAndAliases() {
        var a = ScriptAnalyzer.analyze("function a(){} function b(){} export { a, b as renamed, setup }; ");
        assertThat(a.operations()).extracting(ScriptAnalyzer.Operation::name).containsExactly("a", "renamed");
    }

    @Test
    void detectsHelperUsage() {
        assertThat(ScriptAnalyzer.analyze("import { op } from './__admin/admin.js';").usesHelper()).isTrue();
        assertThat(ScriptAnalyzer.analyze("import { op } from \"../__admin/admin.js\"").usesHelper()).isTrue();
        assertThat(ScriptAnalyzer.analyze("const a = require('./__admin/admin.js');").usesHelper()).isTrue();
        assertThat(ScriptAnalyzer.analyze("import x from './other.js';").usesHelper()).isFalse();
        assertThat(ScriptAnalyzer.analyze("// import { op } from './__admin/admin.js';").usesHelper()).isFalse();
    }

    @Test
    void warnsAboutSleepCheckAndDynamicUrls() {
        var w = ScriptAnalyzer.analyze("""
                import http from 'k6/http';
                import { sleep } from 'k6';
                import { check } from 'k6';
                import { op } from './__admin/admin.js';
                export function f() { op(() => { http.get(`${__ENV.BASE}/items/${id}`); sleep(1); }); }
                """).warnings();
        assertThat(w).anyMatch(s -> s.contains("sleep"));
        assertThat(w).anyMatch(s -> s.contains("check импортирован"));
        assertThat(w).anyMatch(s -> s.contains("динамическими URL"));

        var ok = ScriptAnalyzer.analyze("""
                import http from 'k6/http';
                export function f() { http.get(`${__ENV.BASE}/items/${id}`, { tags: { name: 'GET /items/{id}' } }); }
                """).warnings();
        assertThat(ok).noneMatch(s -> s.contains("динамическими"));
    }

    @Test
    void staticFallbackFindsBlockingOptions() {
        assertThat(ScriptAnalyzer.blockedOptionKeys("export const options = { vus: 10, duration: '1m' };"))
                .contains("duration", "vus").hasSize(2);
        assertThat(ScriptAnalyzer.blockedOptionKeys("export const options = { scenarios: { a: { executor: 'x', iterations: 5 } }, stages: [] };"))
                .contains("scenarios", "stages", "iterations");
        assertThat(ScriptAnalyzer.blockedOptionKeys("export const options = { thresholds: { http_req_duration: ['p(95)<500'] }, tags: { a: 'b' } };"))
                .isEmpty();
        assertThat(ScriptAnalyzer.blockedOptionKeys("// export const options = { vus: 1 };\nexport const options = { insecureSkipTLSVerify: true };")).isEmpty();
        assertThat(ScriptAnalyzer.blockedOptionKeys("const x = 1;")).isEmpty();
    }

    // ---------------------------------------------------------------- zip

    @Test
    void zipRoundTripAndReplace() throws IOException {
        byte[] zip = ZipUtil.singleFile("main.js", "a".getBytes(StandardCharsets.UTF_8));
        assertThat(ZipUtil.list(new ByteArrayInputStream(zip))).extracting(ZipUtil.Entry::path).containsExactly("main.js");
        byte[] replaced = ZipUtil.replace(zip, "data/users.csv", "u1\n".getBytes(StandardCharsets.UTF_8));
        assertThat(ZipUtil.readAll(new ByteArrayInputStream(replaced))).containsOnlyKeys("main.js", "data/users.csv");
        assertThat(ZipUtil.read(new ByteArrayInputStream(replaced), "main.js")).isEqualTo("a".getBytes());
        assertThat(ZipUtil.read(new ByteArrayInputStream(replaced), "nope")).isNull();
        // воспроизводимость: одинаковое содержимое - одинаковые байты архива
        assertThat(ZipUtil.singleFile("main.js", "a".getBytes())).isEqualTo(zip);
    }

    @Test
    void zipSlipIsBlocked(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        Map<String, byte[]> evil = new LinkedHashMap<>();
        evil.put("ok.js", new byte[0]);
        assertThatThrownBy(() -> ZipUtil.write(Map.of("../evil.js", new byte[0]))).isInstanceOf(IOException.class);
        assertThat(ZipUtil.safePath("../x")).isNull();
        assertThat(ZipUtil.safePath("/etc/passwd")).isNull();
        assertThat(ZipUtil.safePath("C:/x")).isNull();
        assertThat(ZipUtil.safePath("a/../../b")).isNull();
        assertThat(ZipUtil.safePath("./a/b.js")).isEqualTo("a/b.js");

        // вручную собранный архив с опасной записью
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (var out = new java.util.zip.ZipOutputStream(bos)) {
            out.putNextEntry(new java.util.zip.ZipEntry("../evil.txt"));
            out.write(1);
            out.closeEntry();
        }
        assertThatThrownBy(() -> ZipUtil.extract(new ByteArrayInputStream(bos.toByteArray()), dir.resolve("x")))
                .hasMessageContaining("Недопустимый путь");
        assertThat(Files.exists(dir.resolve("evil.txt"))).isFalse();
    }

    @Test
    void extractAndStripTopDir(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("proj/main.js", "x".getBytes());
        files.put("proj/lib/util.js", "y".getBytes());
        assertThat(ZipUtil.stripSingleTopDir(files)).containsOnlyKeys("main.js", "lib/util.js");
        Map<String, byte[]> mixed = new LinkedHashMap<>();
        mixed.put("a/x.js", new byte[0]);
        mixed.put("b/y.js", new byte[0]);
        assertThat(ZipUtil.stripSingleTopDir(mixed)).containsOnlyKeys("a/x.js", "b/y.js");

        ZipUtil.extract(new ByteArrayInputStream(ZipUtil.write(ZipUtil.stripSingleTopDir(files))), dir.resolve("out"));
        assertThat(Files.readString(dir.resolve("out/lib/util.js"))).isEqualTo("y");
    }
}
