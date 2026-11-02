package ru.k6lab.server.run;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import ru.k6lab.core.runstate.RunStatus;
import ru.k6lab.server.common.Hashes;
import ru.k6lab.server.common.StorageService;

import java.io.IOException;
import java.time.Instant;

/**
 * Раздача архива скрипта агенту по одноразовой ссылке (/agent/artifact/{runId}/{token}). Доступна только на порту
 * агентов. Токен привязан к паре run + генератор, действует, пока run готовится, и гасится после первой загрузки.
 */
@RestController
public class AgentArtifactController {

    private final RunGeneratorRepository runGenerators;
    private final RunRepository runs;
    private final RunArtifactService artifacts;
    private final StorageService storage;
    private final RunService runService;

    public AgentArtifactController(RunGeneratorRepository runGenerators, RunRepository runs, RunArtifactService artifacts,
                                   StorageService storage, RunService runService) {
        this.runGenerators = runGenerators;
        this.runs = runs;
        this.artifacts = artifacts;
        this.storage = storage;
        this.runService = runService;
    }

    @GetMapping("/agent/artifact/{runId}/{token}")
    public ResponseEntity<InputStreamResource> download(@PathVariable Long runId, @PathVariable String token) throws IOException {
        RunGenerator rg = runGenerators.findByRunIdAndArtifactTokenHash(runId, Hashes.sha256Hex(token)).orElse(null);
        Run run = rg == null ? null : runs.findById(runId).orElse(null);
        if (rg == null || run == null || run.getStatus() != RunStatus.PREPARING || rg.getArtifactConsumedAt() != null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        RunArtifactService.Artifact a = artifacts.forVersion(runService.versionOf(run));
        if (!runService.consumeArtifact(rg.getId(), Instant.now())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(a.size())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new InputStreamResource(storage.open(a.storageKey())));
    }
}
