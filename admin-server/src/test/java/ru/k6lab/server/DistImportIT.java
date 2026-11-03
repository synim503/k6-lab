package ru.k6lab.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import ru.k6lab.server.config.AppProperties;
import ru.k6lab.server.distribution.DistributionArtifact;
import ru.k6lab.server.distribution.DistributionImporter;
import ru.k6lab.server.distribution.DistributionRepository;
import ru.k6lab.server.distribution.DistributionService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Портативная сборка: дистрибутивы из dist/manifest.json подхватываются сами и не дублируются при повторном старте. */
class DistImportIT extends AbstractIT {

    @Autowired
    DistributionService distributions;
    @Autowired
    DistributionRepository repo;

    @Test
    void importsFromManifestOnceAndSkipsBrokenEntries(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("k6/linux-amd64"));
        Files.createDirectories(dir.resolve("xk6/full/linux-amd64"));
        Files.writeString(dir.resolve("k6/linux-amd64/k6"), "fake-k6", StandardCharsets.UTF_8);
        Files.createDirectories(dir.resolve("agent/linux-amd64"));
        Files.writeString(dir.resolve("agent/linux-amd64/k6-agent"), "fake-go-agent", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("xk6/full/linux-amd64/k6"), "fake-xk6", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("manifest.json"), """
                {"items":[
                  {"kind":"AGENT","label":"","version":"9.9.9","arch":"LINUX_AMD64","file":"agent/linux-amd64/k6-agent","default":true},
                  {"kind":"K6","label":"","version":"9.9.9","arch":"LINUX_AMD64","file":"k6/linux-amd64/k6","default":true},
                  {"kind":"XK6","label":"full","version":"9.9.9-ext","arch":"LINUX_AMD64","file":"xk6/full/linux-amd64/k6","default":false},
                  {"kind":"K6","label":"","version":"9.9.9","arch":"LINUX_ARM64","file":"k6/linux-arm64/missing","default":true},
                  {"kind":"K6","label":"","version":"6.6.6","arch":"LINUX_ARM64","file":"../outside","default":true}
                ]}""");
        AppProperties props = new AppProperties();
        props.setDistDir(dir);
        DistributionImporter importer = new DistributionImporter(distributions, repo, props);

        // старая версия агента, загруженная раньше: после импорта новой остаётся только актуальная
        distributions.upload(DistributionArtifact.Kind.AGENT, "", "9.0.0", DistributionArtifact.Arch.LINUX_AMD64, "k6-agent",
                new java.io.ByteArrayInputStream("old-agent".getBytes(StandardCharsets.UTF_8)), true);
        assertThat(importer.importAll()).isEqualTo(3); // отсутствующий файл и путь вне каталога пропущены
        var agents = repo.findByKindOrderByCreatedAtDesc(DistributionArtifact.Kind.AGENT).stream()
                .filter(a -> a.getArch() == DistributionArtifact.Arch.LINUX_AMD64).toList();
        assertThat(agents).singleElement().satisfies(a -> {
            assertThat(a.getVersion()).isEqualTo("9.9.9");
            assertThat(a.isDefault()).isTrue();
        });
        assertThat(importer.importAll()).isZero();     // повторный старт ничего не дублирует
        var k6 = repo.findFirstByKindAndVersionAndArchAndLabel(DistributionArtifact.Kind.K6, "9.9.9", DistributionArtifact.Arch.LINUX_AMD64, "");
        assertThat(k6).isPresent();
        assertThat(k6.get().getSizeBytes()).isEqualTo(7);
        var agent = repo.findFirstByKindAndVersionAndArchAndLabel(DistributionArtifact.Kind.AGENT, "9.9.9", DistributionArtifact.Arch.LINUX_AMD64, "");
        assertThat(agent).isPresent();
        assertThat(agent.get().isNativeAgent()).isTrue(); // бинарь Go: JRE не нужна
        assertThat(repo.findFirstByKindAndVersionAndArchAndLabel(DistributionArtifact.Kind.XK6, "9.9.9-ext", DistributionArtifact.Arch.LINUX_AMD64, "full")).isPresent();
        // без manifest.json - ничего не делает и не падает
        props.setDistDir(dir.resolve("nothing"));
        assertThat(importer.importAll()).isZero();
    }
}
