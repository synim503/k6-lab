package ru.k6lab.server.api;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.k6lab.server.web.GlobalModel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Аутентификация, текущая сессия и CSRF-токен для React SPA. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthApiController {

    private final GlobalModel globalModel;

    public AuthApiController(GlobalModel globalModel) {
        this.globalModel = globalModel;
    }

    public record LoginRequest(String username, String password) {
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request, CsrfToken csrfToken) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
        List<String> roles = authenticated
                ? auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList()
                : List.of();
        boolean isAdmin = roles.contains("ROLE_ADMIN");

        // Гарантируем создание сессии/токена CSRF для последующих POST-запросов (включая /login)
        request.getSession(true);
        String tokenVal = csrfToken != null ? csrfToken.getToken() : null;
        String headerName = csrfToken != null ? csrfToken.getHeaderName() : "X-CSRF-TOKEN";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("authenticated", authenticated);
        out.put("username", authenticated ? auth.getName() : null);
        out.put("isAdmin", isAdmin);
        out.put("roles", roles);
        out.put("buildLabel", globalModel.buildLabel());
        out.put("csrfToken", tokenVal);
        out.put("csrfHeader", headerName);
        return out;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest body,
                                                     HttpServletRequest request,
                                                     CsrfToken csrfToken) {
        String user = body == null || body.username() == null ? "" : body.username().trim();
        String pass = body == null || body.password() == null ? "" : body.password();
        if (user.isEmpty() || pass.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Введите логин и пароль"));
        }
        try {
            Authentication cur = SecurityContextHolder.getContext().getAuthentication();
            if (cur != null && cur.isAuthenticated() && !(cur instanceof AnonymousAuthenticationToken)) {
                request.logout();
            }
            request.login(user, pass);
            request.changeSessionId();
            Map<String, Object> sessionData = me(request, csrfToken);
            sessionData.put("ok", true);
            return ResponseEntity.ok(sessionData);
        } catch (ServletException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("ok", false, "error", "Неверный логин или пароль"));
        }
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response, CsrfToken csrfToken) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        new SecurityContextLogoutHandler().logout(request, response, auth);
        Map<String, Object> out = me(request, csrfToken);
        out.put("ok", true);
        return out;
    }
}
