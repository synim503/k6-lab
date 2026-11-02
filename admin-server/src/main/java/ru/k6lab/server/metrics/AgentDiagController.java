package ru.k6lab.server.metrics;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Артефакт smoke-теста диагностики: одноразовая ссылка, доступна только на порту агентов (см. PortSeparationFilter). */
@RestController
public class AgentDiagController {

    private final PrometheusDiagnostics diagnostics;

    public AgentDiagController(PrometheusDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
    }

    @GetMapping("/agent/diag/{token}")
    public ResponseEntity<byte[]> download(@PathVariable String token) {
        return diagnostics.takeArtifact(token)
                .map(b -> ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).header(HttpHeaders.CACHE_CONTROL, "no-store").body(b))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.FORBIDDEN).build());
    }
}
