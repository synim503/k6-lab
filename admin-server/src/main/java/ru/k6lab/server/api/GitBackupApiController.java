package ru.k6lab.server.api;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.k6lab.server.git.GitBackupService;
import ru.k6lab.server.git.GitClient;
import ru.k6lab.server.git.GitRepoService;
import ru.k6lab.server.script.ScriptService;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** REST API резервного копирования и восстановления скриптов в Git-репозиторий. */
@RestController
@RequestMapping("/api/v1/git-backup")
public class GitBackupApiController {

    private final GitBackupService backup;
    private final GitRepoService repos;
    private final ScriptService scripts;

    public GitBackupApiController(GitBackupService backup, GitRepoService repos, ScriptService scripts) {
        this.backup = backup;
        this.repos = repos;
        this.scripts = scripts;
    }

    public record ConfigPayload(Long repoId, String branch, String subdir, Boolean autoSync, Boolean pruneDeleted) {
    }

    public record SyncPayload(Long repoId, String branch, String subdir, Boolean pruneDeleted) {
    }

    public record InspectPayload(Long repoId, String branch, String subdir) {
    }

    public record RestorePayload(Long repoId, String branch, String subdir, List<String> slugs, Boolean overwriteExisting) {
    }

    @GetMapping
    public Map<String, Object> status() {
        var cfg = backup.getConfig();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("config", cfg);
        out.put("repos", repos.list().stream().map(GitRepoApiController::toRepoDto).toList());
        out.put("localScriptCount", scripts.list().size());
        return out;
    }

    @PostMapping("/config")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> saveConfig(@RequestBody ConfigPayload p) {
        var saved = backup.saveConfig(
                p.repoId(),
                p.branch(),
                p.subdir(),
                p.autoSync() != null && p.autoSync(),
                p.pruneDeleted() != null && p.pruneDeleted()
        );
        return Map.of("ok", true, "config", saved);
    }

    @PostMapping("/push-all")
    public ResponseEntity<Map<String, Object>> pushAll(@RequestBody(required = false) SyncPayload p,
                                                       Authentication auth) throws IOException {
        Long effRepoId = p != null && p.repoId() != null ? p.repoId() : backup.getConfig().repoId();
        try {
            var res = backup.backupAll(
                    p != null ? p.repoId() : null,
                    p != null ? p.branch() : null,
                    p != null ? p.subdir() : null,
                    p != null ? p.pruneDeleted() : null,
                    auth != null ? auth.getName() : "admin"
            );
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("outcome", res);
            out.put("config", backup.getConfig());
            return ResponseEntity.ok(out);
        } catch (GitClient.GitHostKeyException e) {
            return GitRepoApiController.hostKeyResponse(e, effRepoId);
        }
    }

    @PostMapping("/push-script/{scriptId}")
    public ResponseEntity<Map<String, Object>> pushScript(@PathVariable Long scriptId,
                                                          Authentication auth) throws IOException {
        Long effRepoId = backup.getConfig().repoId();
        try {
            var res = backup.backupSingleScript(scriptId, auth != null ? auth.getName() : "admin");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("outcome", res);
            out.put("config", backup.getConfig());
            return ResponseEntity.ok(out);
        } catch (GitClient.GitHostKeyException e) {
            return GitRepoApiController.hostKeyResponse(e, effRepoId);
        }
    }

    @PostMapping("/inspect")
    public ResponseEntity<Map<String, Object>> inspect(@RequestBody(required = false) InspectPayload p) throws IOException {
        Long effRepoId = p != null && p.repoId() != null ? p.repoId() : backup.getConfig().repoId();
        try {
            var list = backup.inspectRemote(
                    p != null ? p.repoId() : null,
                    p != null ? p.branch() : null,
                    p != null ? p.subdir() : null
            );
            return ResponseEntity.ok(Map.of("ok", true, "scripts", list));
        } catch (GitClient.GitHostKeyException e) {
            return GitRepoApiController.hostKeyResponse(e, effRepoId);
        }
    }

    @PostMapping("/restore")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> restore(@RequestBody RestorePayload p,
                                                       Authentication auth) throws IOException {
        Long effRepoId = p != null && p.repoId() != null ? p.repoId() : backup.getConfig().repoId();
        try {
            var res = backup.restoreFromGit(
                    p.repoId(),
                    p.branch(),
                    p.subdir(),
                    p.slugs(),
                    p.overwriteExisting() != null && p.overwriteExisting(),
                    auth != null ? auth.getName() : "admin"
            );
            return ResponseEntity.ok(Map.of("ok", true, "outcome", res));
        } catch (GitClient.GitHostKeyException e) {
            return GitRepoApiController.hostKeyResponse(e, effRepoId);
        }
    }
}
