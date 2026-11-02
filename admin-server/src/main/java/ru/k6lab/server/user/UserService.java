package ru.k6lab.server.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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

    public UserService(UserRepository users, PasswordEncoder encoder, AppProperties props, AuditService audit) {
        this.users = users;
        this.encoder = encoder;
        this.props = props;
        this.audit = audit;
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
        if (users.existsByUsername(username)) {
            throw new IllegalArgumentException("Пользователь «" + username + "» уже существует");
        }
        validatePassword(rawPassword);
        AppUser u = new AppUser();
        u.setUsername(username.trim());
        u.setPasswordHash(encoder.encode(rawPassword));
        u.setRole(role);
        AppUser saved = users.save(u);
        audit.record("user.create", "user", saved.getId(), java.util.Map.of("username", username, "role", role));
        return saved;
    }

    @Transactional
    public void changePassword(Long id, String rawPassword) {
        validatePassword(rawPassword);
        AppUser u = users.findById(id).orElseThrow();
        u.setPasswordHash(encoder.encode(rawPassword));
        audit.record("user.password", "user", id, null);
    }

    @Transactional
    public void update(Long id, Role role, boolean enabled) {
        AppUser u = users.findById(id).orElseThrow();
        boolean lastAdminLost = u.getRole() == Role.ADMIN && u.isEnabled()
                && (role != Role.ADMIN || !enabled) && users.countByRoleAndEnabledTrue(Role.ADMIN) <= 1;
        if (lastAdminLost) {
            throw new IllegalStateException("Нельзя лишить прав или отключить последнего администратора");
        }
        u.setRole(role);
        u.setEnabled(enabled);
        audit.record("user.update", "user", id, java.util.Map.of("role", role, "enabled", enabled));
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
