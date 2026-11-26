package ru.k6lab.server.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.k6lab.server.audit.AuditService;
import ru.k6lab.server.common.Hashes;
import ru.k6lab.server.config.AppProperties;

import java.util.List;

@Service
public class UserService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AppProperties props;
    private final AuditService audit;
    private final SessionRegistry sessions;

    public UserService(UserRepository users, PasswordEncoder encoder, AppProperties props, AuditService audit,
                       SessionRegistry sessions) {
        this.users = users;
        this.encoder = encoder;
        this.props = props;
        this.audit = audit;
        this.sessions = sessions;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser u = users.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден"));
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole().name()));
        return User.withUsername(u.getUsername()).password(u.getPasswordHash()).authorities(authorities)
                .disabled(!u.isEnabled()).build();
    }

    @Transactional
    public AppUser create(String username, String rawPassword, Role role) {
        String trimmed = username == null ? "" : username.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Укажите имя пользователя");
        }
        if (users.existsByUsername(trimmed)) {
            throw new IllegalArgumentException("Пользователь «" + trimmed + "» уже существует");
        }
        validatePassword(rawPassword);
        AppUser u = new AppUser();
        u.setUsername(trimmed);
        u.setPasswordHash(encoder.encode(rawPassword));
        u.setRole(role);
        AppUser saved = users.save(u);
        audit.record("user.create", "user", saved.getId(), java.util.Map.of("username", trimmed, "role", role));
        return saved;
    }

    @Transactional
    public void changePassword(Long id, String rawPassword) {
        validatePassword(rawPassword);
        AppUser u = users.findById(id).orElseThrow();
        u.setPasswordHash(encoder.encode(rawPassword));
        audit.record("user.password", "user", id, null);
        expireUserSessions(u.getUsername());
    }

    @Transactional
    public void update(Long id, Role role, boolean enabled) {
        AppUser u = users.findById(id).orElseThrow();
        boolean lastAdminLost = u.getRole() == Role.ADMIN && u.isEnabled()
                && (role != Role.ADMIN || !enabled) && users.countByRoleAndEnabledTrue(Role.ADMIN) <= 1;
        if (lastAdminLost) {
            throw new IllegalStateException("Нельзя лишить прав или отключить последнего администратора");
        }
        boolean mustExpire = !enabled || u.getRole() != role;
        u.setRole(role);
        u.setEnabled(enabled);
        audit.record("user.update", "user", id, java.util.Map.of("role", role, "enabled", enabled));
        if (mustExpire) {
            expireUserSessions(u.getUsername());
        }
    }

    @Transactional
    public void delete(Long id, String currentUsername) {
        AppUser u = users.findById(id).orElseThrow();
        if (u.getUsername().equals(currentUsername)) {
            throw new IllegalStateException("Нельзя удалить самого себя");
        }
        if (u.getRole() == Role.ADMIN && u.isEnabled() && users.countByRoleAndEnabledTrue(Role.ADMIN) <= 1) {
            throw new IllegalStateException("Нельзя удалить последнего администратора");
        }
        users.delete(u);
        audit.record("user.delete", "user", id, java.util.Map.of("username", u.getUsername()));
        expireUserSessions(u.getUsername());
    }

    private void expireUserSessions(String username) {
        if (sessions == null || username == null) {
            return;
        }
        for (Object principal : sessions.getAllPrincipals()) {
            if (principal instanceof UserDetails ud && username.equals(ud.getUsername())) {
                for (SessionInformation info : sessions.getAllSessions(principal, false)) {
                    info.expireNow();
                }
            }
        }
    }

    private static void validatePassword(String p) {
        if (p == null || p.length() < 8) {
            throw new IllegalArgumentException("Пароль должен быть не короче 8 символов");
        }
    }

    /** Первый запуск: создаёт администратора admin. Пароль - из конфигурации либо случайный (в лог один раз). */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void bootstrapAdmin() {
        if (users.count() > 0) {
            return;
        }
        String password = props.getAdminPassword();
        boolean generated = password == null || password.isBlank();
        if (generated) {
            password = Hashes.randomToken().substring(0, 16);
        }
        create("admin", password, Role.ADMIN);
        if (generated) {
            log.warn("Создан администратор admin с паролем: {}  (сохраните и смените пароль; сообщение показывается один раз)",
                    password);
        } else {
            log.info("Создан администратор admin (пароль из K6LAB_ADMIN_PASSWORD)");
        }
    }
}
