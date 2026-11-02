package ru.k6lab.server.agent;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.k6lab.server.common.Hashes;
import ru.k6lab.server.config.AppProperties;
import ru.k6lab.server.generator.Generator;
import ru.k6lab.server.generator.GeneratorRepository;

import java.time.Instant;
import java.util.Optional;

/** Токены агентов: одноразовый (при установке) обменивается на постоянный. В БД только sha256. */
@Service
public class TokenService {

    public record Resolved(Generator generator, AgentSession.TokenKind kind) {
    }

    private final GeneratorRepository generators;
    private final AppProperties props;

    public TokenService(GeneratorRepository generators, AppProperties props) {
        this.generators = generators;
        this.props = props;
    }

    /** Выпускает новый одноразовый токен; возвращает открытое значение (в БД остаётся хеш). */
    @Transactional
    public String issueOneTime(Long generatorId) {
        Generator g = generators.findById(generatorId).orElseThrow();
        String token = Hashes.randomToken();
        g.setOneTimeTokenHash(Hashes.sha256Hex(token));
        g.setOneTimeExpires(Instant.now().plus(props.getOneTimeTokenTtl()));
        return token;
    }

    @Transactional(readOnly = true)
    public Optional<Resolved> resolve(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            return Optional.empty();
        }
        String hash = Hashes.sha256Hex(bearerToken);
        Optional<Generator> permanent = generators.findByAgentTokenHash(hash);
        if (permanent.isPresent()) {
            return Optional.of(new Resolved(permanent.get(), AgentSession.TokenKind.PERMANENT));
        }
        return generators.findByOneTimeTokenHash(hash)
                .filter(g -> g.getOneTimeExpires() != null && g.getOneTimeExpires().isAfter(Instant.now()))
                .map(g -> new Resolved(g, AgentSession.TokenKind.ONE_TIME));
    }

    /**
     * Обмен одноразового токена на постоянный. Одноразовый остаётся действительным, пока агент не подключится
     * с постоянным (на случай, если агент не успел сохранить выданный токен), после чего гасится.
     */
    @Transactional
    public String issuePermanent(Long generatorId) {
        Generator g = generators.findById(generatorId).orElseThrow();
        String token = Hashes.randomToken();
        g.setAgentTokenHash(Hashes.sha256Hex(token));
        return token;
    }

    @Transactional
    public void consumeOneTime(Long generatorId) {
        generators.findById(generatorId).ifPresent(g -> {
            g.setOneTimeTokenHash(null);
            g.setOneTimeExpires(null);
        });
    }

    @Transactional
    public void revoke(Long generatorId) {
        generators.findById(generatorId).ifPresent(g -> {
            g.setOneTimeTokenHash(null);
            g.setOneTimeExpires(null);
            g.setAgentTokenHash(null);
        });
    }
}
