package ru.k6lab.server.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

/** Запись действий пользователей. Никогда не должна ронять основную операцию. */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repo;

    public AuditService(AuditLogRepository repo) {
        this.repo = repo;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String entityType, Object entityId, Map<String, Object> details) {
        try {
            AuditLog a = new AuditLog();
            a.setAction(action);
            a.setEntityType(entityType);
            a.setEntityId(entityId == null ? null : String.valueOf(entityId));
            a.setDetails(details);
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            a.setUsername(auth != null && auth.isAuthenticated() ? auth.getName() : "system");
            RequestAttributes ra = RequestContextHolder.getRequestAttributes();
            if (ra instanceof ServletRequestAttributes sra) {
                HttpServletRequest req = sra.getRequest();
                a.setIp(req.getRemoteAddr());
            }
            repo.save(a);
        } catch (RuntimeException e) {
            log.warn("Не удалось записать аудит {}: {}", action, e.toString());
        }
    }
}
