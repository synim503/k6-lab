package ru.k6lab.server.settings;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.k6lab.server.audit.AuditService;
import ru.k6lab.server.common.SecretCipher;
import ru.k6lab.server.config.AppProperties;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Настройки в таблице setting (ключ -> JSON-строка). Секреты (пароль/токен Prometheus) шифруются. */
@Service
public class SettingsService {

    public static final String PROM_RW_URL = "prometheus.rw.url";
    public static final String PROM_QUERY_URL = "prometheus.query.url";
    public static final String PROM_AUTH_TYPE = "prometheus.auth.type";
    public static final String PROM_AUTH_USER = "prometheus.auth.user";
    public static final String PROM_AUTH_SECRET = "prometheus.auth.secret";
    public static final String GRAFANA_URL_TPL = "grafana.url.template";
    public static final String DIAGNOSTICS = "diagnostics.last";
    public static final String METRIC_NAMES = "metrics.names";
    /** NATIVE - k6 шлёт native histograms прямо в Prometheus; CLASSIC - агент переводит их в классические _bucket{le}. */
    public static final String HISTOGRAM_MODE = "metrics.histogram.mode";
    public static final String LOG_LINES_PER_SEC = "agent.log.lines_per_second";
    public static final String RETENTION_LOG_DAYS = "retention.logs.days";
    public static final String RETENTION_METRICS_DAYS = "retention.metrics.days";

    public enum AuthType { NONE, BASIC, BEARER }

    /** Параметры доступа к Prometheus (секрет расшифрован - не логировать). */
    public record Prometheus(String rwUrl, String queryUrl, AuthType authType, String user, String secret) {

        public boolean configured() {
            return rwUrl != null && !rwUrl.isBlank() && queryUrl != null && !queryUrl.isBlank();
        }

        /** Заголовки аутентификации для запросов (и для remote write k6). */
        public Map<String, String> authHeaders() {
            Map<String, String> h = new LinkedHashMap<>();
            if (authType == AuthType.BASIC && user != null && secret != null) {
                h.put("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + secret).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            } else if (authType == AuthType.BEARER && secret != null && !secret.isBlank()) {
                h.put("Authorization", "Bearer " + secret);
            }
            return h;
        }
    }

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final AuditService audit;
    private final AppProperties props;

    public SettingsService(JdbcTemplate jdbc, SecretCipher cipher, AuditService audit, AppProperties props) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.audit = audit;
        this.props = props;
    }

    public Optional<String> get(String key) {
        List<String> r = jdbc.queryForList("select value #>> '{}' from setting where key = ?", String.class, key);
        return r.isEmpty() || r.get(0) == null ? Optional.empty() : Optional.of(r.get(0));
    }

    public String get(String key, String def) {
        return get(key).orElse(def);
    }

    public int getInt(String key, int def) {
        try {
            return get(key).map(Integer::parseInt).orElse(def);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public void set(String key, String value) {
        jdbc.update("insert into setting (key, value, updated_at) values (?, to_jsonb(?::text), now()) "
                + "on conflict (key) do update set value = excluded.value, updated_at = now()", key, value);
    }

    public void delete(String key) {
        jdbc.update("delete from setting where key = ?", key);
    }

    public Prometheus prometheus() {
        AuthType t;
        try {
            t = AuthType.valueOf(get(PROM_AUTH_TYPE, "NONE"));
        } catch (IllegalArgumentException e) {
            t = AuthType.NONE;
        }
        String secret = get(PROM_AUTH_SECRET).map(s -> cipher.decrypt(Base64.getDecoder().decode(s))).orElse(null);
        return new Prometheus(get(PROM_RW_URL, props.getPrometheusRwUrl()), get(PROM_QUERY_URL, props.getPrometheusQueryUrl()), t,
                get(PROM_AUTH_USER).orElse(null), secret);
    }

    /** Сохраняет настройки Prometheus; пустой секрет = не менять. */
    public void savePrometheus(String rwUrl, String queryUrl, AuthType type, String user, String secret, String grafanaTpl) {
        set(PROM_RW_URL, rwUrl == null ? "" : rwUrl.trim());
        set(PROM_QUERY_URL, queryUrl == null ? "" : queryUrl.trim());
        set(PROM_AUTH_TYPE, type.name());
        set(PROM_AUTH_USER, user == null ? "" : user.trim());
        if (secret != null && !secret.isEmpty()) {
            set(PROM_AUTH_SECRET, Base64.getEncoder().encodeToString(cipher.encrypt(secret)));
        }
        if (type == AuthType.NONE) {
            delete(PROM_AUTH_SECRET);
        }
        set(GRAFANA_URL_TPL, grafanaTpl == null ? "" : grafanaTpl.trim());
        audit.record("settings.prometheus", "setting", "prometheus", Map.of("rw", String.valueOf(rwUrl), "query", String.valueOf(queryUrl)));
    }

    public boolean hasPrometheusSecret() {
        return get(PROM_AUTH_SECRET).isPresent();
    }

    /** По умолчанию классические гистограммы: работают на любом Prometheus, без флага native-histograms. */
    public boolean classicHistograms() {
        return !"NATIVE".equals(get(HISTOGRAM_MODE, "CLASSIC"));
    }

    public static final String PUSH_INTERVAL_SEC = "metrics.push.interval_sec";
    public static final int DEFAULT_PUSH_INTERVAL_SEC = 10;

    /** Как часто k6 на генераторе отправляет метрики в Prometheus (секунды, 5..60). */
    public int pushIntervalSec() {
        return Math.max(5, Math.min(60, getInt(PUSH_INTERVAL_SEC, DEFAULT_PUSH_INTERVAL_SEC)));
    }

    public int logLinesPerSecond() {
        return Math.max(1, getInt(LOG_LINES_PER_SEC, 200));
    }

    public int logRetentionDays() {
        return Math.max(1, getInt(RETENTION_LOG_DAYS, 30));
    }

    public int metricsRetentionDays() {
        return Math.max(1, getInt(RETENTION_METRICS_DAYS, 7));
    }
}
