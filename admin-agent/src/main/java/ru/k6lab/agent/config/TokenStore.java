package ru.k6lab.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Хранит текущий токен и записывает постоянный токен в agent.properties (права 600). */
@Component
public class TokenStore {

    private static final Logger log = LoggerFactory.getLogger(TokenStore.class);

    private final Path file;
    private final AtomicReference<String> token;

    public TokenStore(AgentProperties props) {
        this.file = props.getHome().resolve("agent.properties");
        this.token = new AtomicReference<>(props.getToken());
    }

    public String current() {
        return token.get();
    }

    public synchronized void savePermanent(String permanentToken) {
        token.set(permanentToken);
        try {
            List<String> lines = Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : new ArrayList<>();
            List<String> out = new ArrayList<>();
            boolean replaced = false;
            for (String l : lines) {
                if (l.startsWith("agent.token=")) {
                    out.add("agent.token=" + permanentToken);
                    replaced = true;
                } else {
                    out.add(l);
                }
            }
            if (!replaced) {
                out.add("agent.token=" + permanentToken);
            }
            Path tmp = file.resolveSibling("agent.properties.tmp");
            Files.write(tmp, out, StandardCharsets.UTF_8);
            restrict(tmp);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            restrict(file);
            log.info("Постоянный токен сохранён в {}", file);
        } catch (IOException e) {
            // токен остаётся в памяти; после рестарта агент попросит регистрацию заново
            log.error("Не удалось сохранить постоянный токен: {}", e.toString());
        }
    }

    private static void restrict(Path p) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // не POSIX-ФС (dev на Windows)
        }
    }
}
