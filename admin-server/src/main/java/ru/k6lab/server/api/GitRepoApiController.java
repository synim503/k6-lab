package ru.k6lab.server.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.k6lab.server.git.GitClient;
import ru.k6lab.server.git.GitRepo;
import ru.k6lab.server.git.GitRepoService;
import ru.k6lab.server.ssh.KnownHostService;
import ru.k6lab.server.ssh.SshCredentialService;
import ru.k6lab.server.ssh.SshExceptions;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** REST API для Git-репозиториев и веток/тегов. */
@RestController
@RequestMapping("/api/v1/git-repos")
public class GitRepoApiController {

    private final GitRepoService repos;
    private final SshCredentialService sshCreds;
    private final KnownHostService knownHosts;

    public GitRepoApiController(GitRepoService repos, SshCredentialService sshCreds, KnownHostService knownHosts) {
        this.repos = repos;
        this.sshCreds = sshCreds;
        this.knownHosts = knownHosts;
    }

    public record RepoPayload(Long id, String name, String url, GitRepo.AuthType authType,
                              String token, Long sshCredentialId, String subdir, Boolean clearToken) {
    }

    public record ConfirmHostKeyPayload(Long repoId, String host, int port, String keyType,
                                        String fingerprint, String publicKey) {
    }

    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, Object>> items = repos.list().stream().map(r -> {
            Map<String, Object> m = toRepoDto(r);
            m.put("cachedRefs", repos.cachedRefs(r.getId()));
            return m;
        }).toList();
        List<Map<String, Object>> creds = sshCreds.list().stream().map(c -> {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("id", c.getId());
            cm.put("name", c.getName());
            cm.put("username", c.getUsername());
            cm.put("authType", c.getAuthType().name());
            return cm;
        }).toList();
        return Map.of("items", items, "sshCredentials", creds);
    }

    @GetMapping("/{id}/refs")
    public Map<String, Object> cachedRefs(@PathVariable Long id) {
        GitRepo r = repos.get(id);
        return Map.of("repo", toRepoDto(r), "refs", repos.cachedRefs(id));
    }

    @PostMapping("/{id}/refresh")
    public ResponseEntity<Map<String, Object>> refresh(@PathVariable Long id) throws IOException {
        try {
            List<GitClient.RefInfo> refs = repos.refresh(id);
            return ResponseEntity.ok(Map.of("ok", true, "refs", refs));
        } catch (GitClient.GitHostKeyException e) {
            return hostKeyResponse(e, id);
        }
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> save(@RequestBody RepoPayload p) {
        GitRepo saved = repos.save(new GitRepoService.Form(
                p.id(),
                p.name(),
                p.url(),
                p.authType() != null ? p.authType() : GitRepo.AuthType.NONE,
                p.token(),
                p.sshCredentialId(),
                p.subdir() != null ? p.subdir() : "",
                p.clearToken() != null && p.clearToken()
        ));
        return Map.of("ok", true, "repo", toRepoDto(saved));
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> delete(@PathVariable Long id) {
        repos.delete(id);
        return Map.of("ok", true);
    }

    @PostMapping("/confirm-host-key")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> confirmHostKey(@RequestBody ConfirmHostKeyPayload p,
                                                              Authentication auth) throws IOException {
        knownHosts.confirm(p.host(), p.port(), p.keyType(), p.fingerprint(), p.publicKey(),
                auth != null ? auth.getName() : "admin");
        if (p.repoId() != null) {
            List<GitClient.RefInfo> refs = repos.refresh(p.repoId());
            return ResponseEntity.ok(Map.of("ok", true, "refs", refs));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    public static Map<String, Object> toRepoDto(GitRepo r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("name", r.getName());
        m.put("url", r.getUrl());
        m.put("authType", r.getAuthType().name());
        m.put("hasToken", r.getTokenEnc() != null && r.getTokenEnc().length > 0);
        m.put("sshCredentialId", r.getSshCredentialId());
        m.put("subdir", r.getSubdir());
        m.put("updatedAt", r.getUpdatedAt());
        return m;
    }

    public static ResponseEntity<Map<String, Object>> hostKeyResponse(GitClient.GitHostKeyException e, Long repoId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        if (e.failure() instanceof SshExceptions.HostKeyUnconfirmed u) {
            out.put("hostKeyRequired", true);
            out.put("repoId", repoId);
            out.put("host", u.host());
            out.put("port", u.port());
            out.put("keyType", u.keyType());
            out.put("fingerprint", u.fingerprint());
            out.put("publicKey", u.publicKey());
            out.put("error", "Ключ SSH-хоста " + u.host() + ":" + u.port() + " ещё не подтверждён");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(out);
        }
        out.put("error", e.getMessage() != null ? e.getMessage() : "Ошибка проверки SSH-ключа Git-сервера");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(out);
    }
}
