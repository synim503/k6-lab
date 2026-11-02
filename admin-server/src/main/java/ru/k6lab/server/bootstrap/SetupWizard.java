package ru.k6lab.server.bootstrap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

/**
 * Мастер первого запуска портативной версии: до старта Spring спрашивает параметры PostgreSQL, проверяет подключение,
 * генерирует ключ шифрования и сохраняет всё в config/k6lab.properties. Работает на JDK HttpServer (без Spring и БД).
 * Если заданы переменные K6LAB_DB_URL (серверная установка) - мастер не нужен.
 */
public final class SetupWizard {

    public static final String CONFIG_FILE = "config/k6lab.properties";

    private SetupWizard() {
    }

    /** Нужно ли показывать мастер: конфигурация отсутствует или БД из неё недоступна. */
    public static boolean needed(Path configFile, Map<String, String> env) {
        if (env.get("K6LAB_DB_URL") != null && !env.get("K6LAB_DB_URL").isBlank()) {
            return false;
        }
        if (!Files.isRegularFile(configFile)) {
            return true;
        }
        Properties p = load(configFile);
        return check(p.getProperty("spring.datasource.url", ""), p.getProperty("spring.datasource.username", ""),
                p.getProperty("spring.datasource.password", "")) != null;
    }

    /** Блокирует до завершения настройки. */
    public static void run(Path configFile, int httpPort) throws IOException, InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress(httpPort), 0);
        server.createContext("/", ex -> handle(ex, configFile, done));
        server.start();
        System.out.println("=====================================================================");
        System.out.println(" k6-lab: первый запуск. Откройте http://localhost:" + httpPort + " и введите параметры PostgreSQL");
        System.out.println("=====================================================================");
        done.await();
        Thread.sleep(500); // страница успеха должна дойти до браузера
        server.stop(0);
    }

    private static void handle(HttpExchange ex, Path configFile, CountDownLatch done) throws IOException {
        Map<String, String> f = new LinkedHashMap<>();
        String error = null;
        boolean ok = false;
        if ("POST".equals(ex.getRequestMethod())) {
            f = form(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String url = "jdbc:postgresql://" + f.getOrDefault("host", "localhost").trim() + ":" + f.getOrDefault("port", "5432").trim()
                    + "/" + f.getOrDefault("db", "k6lab").trim();
            error = check(url, f.getOrDefault("user", ""), f.getOrDefault("password", ""));
            if (error == null) {
                String admin = f.getOrDefault("admin", "").isBlank() ? randomPassword() : f.get("admin");
                save(configFile, url, f.getOrDefault("user", ""), f.getOrDefault("password", ""), admin);
                ok = true;
                send(ex, 200, page("Готово", "<div class='ok'>Настройки сохранены в <code>" + esc(configFile.toString()) + "</code>.</div>"
                        + "<p>Приложение запускается, страница обновится сама. Логин администратора: <b>admin</b>, пароль: <b>" + esc(admin)
                        + "</b> (запишите его).</p><script>setTimeout(function(){location.href='/login'},12000)</script>"));
                done.countDown();
            }
        }
        if (!ok) {
            send(ex, 200, page("Настройка k6-lab", formHtml(f, error)));
        }
    }

    /** Проверка подключения; null - успешно, иначе сообщение для пользователя. */
    public static String check(String url, String user, String password) {
        if (url == null || url.isBlank()) {
            return "Не задан адрес базы данных";
        }
        try {
            // драйвер создаём напрямую: в fat jar DriverManager не находит его через ServiceLoader (вложенный classloader)
            Properties props = new Properties();
            props.setProperty("user", user == null ? "" : user);
            props.setProperty("password", password == null ? "" : password);
            props.setProperty("connectTimeout", "5");
            props.setProperty("loginTimeout", "5");
            java.sql.Driver driver = (java.sql.Driver) Class.forName("org.postgresql.Driver").getDeclaredConstructor().newInstance();
            try (Connection c = driver.connect(url, props)) {
                if (c == null) {
                    return "Адрес базы должен начинаться с jdbc:postgresql://";
                }
                if (!c.isValid(3)) {
                    return "Соединение установлено, но база не отвечает";
                }
            }
            return null;
        } catch (Exception e) {
            return "Не удалось подключиться к PostgreSQL: " + e.getMessage();
        }
    }

    static void save(Path file, String url, String user, String password, String adminPassword) throws IOException {
        Properties p = Files.isRegularFile(file) ? load(file) : new Properties();
        p.setProperty("spring.datasource.url", url);
        p.setProperty("spring.datasource.username", user);
        p.setProperty("spring.datasource.password", password);
        if (p.getProperty("k6lab.master-key", "").isBlank()) {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            p.setProperty("k6lab.master-key", Base64.getEncoder().encodeToString(key));
        }
        if (p.getProperty("k6lab.admin-password", "").isBlank()) {
            p.setProperty("k6lab.admin-password", adminPassword);
        }
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        try (var out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            p.store(out, "k6-lab. Файл содержит секреты (ключ шифрования!): храните его и не публикуйте.");
        }
        try {
            file.toFile().setReadable(false, false);
            file.toFile().setReadable(true, true);
        } catch (RuntimeException ignored) {
            // права не поддерживаются файловой системой - не критично
        }
    }

    static Properties load(Path file) {
        Properties p = new Properties();
        try (var in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (IOException e) {
            // пустые свойства -> мастер запросит данные заново
        }
        return p;
    }

    private static String randomPassword() {
        byte[] b = new byte[12];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static Map<String, String> form(String body) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String kv : body.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) {
                m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return m;
    }

    private static void send(HttpExchange ex, int code, String html) throws IOException {
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private static String formHtml(Map<String, String> f, String error) {
        return (error == null ? "" : "<div class='err'>" + esc(error) + "</div>")
                + "<form method='post'>"
                + field("Адрес PostgreSQL", "host", f.getOrDefault("host", "localhost"), "text")
                + field("Порт", "port", f.getOrDefault("port", "5432"), "text")
                + field("Имя базы данных", "db", f.getOrDefault("db", "k6lab"), "text")
                + field("Пользователь", "user", f.getOrDefault("user", "k6lab"), "text")
                + field("Пароль", "password", "", "password")
                + field("Пароль администратора k6-lab (пусто — сгенерировать)", "admin", "", "text")
                + "<button>Проверить и сохранить</button></form>"
                + "<p class='hint'>База должна существовать, пользователь — быть её владельцем: "
                + "<code>create user k6lab with password '...'; create database k6lab owner k6lab;</code></p>";
    }

    private static String field(String label, String name, String value, String type) {
        return "<label>" + esc(label) + "<input name='" + name + "' type='" + type + "' value='" + esc(value) + "'></label>";
    }

    private static String page(String title, String body) {
        return "<!DOCTYPE html><html lang='ru'><head><meta charset='utf-8'><title>" + esc(title) + "</title><style>"
                + "body{font-family:system-ui,sans-serif;max-width:520px;margin:40px auto;padding:0 16px;color:#212529}"
                + "label{display:block;margin:10px 0;font-size:14px}input{display:block;width:100%;padding:8px;margin-top:4px;box-sizing:border-box}"
                + "button{margin-top:12px;padding:10px 18px;background:#0d6efd;color:#fff;border:0;border-radius:4px;font-size:15px;cursor:pointer}"
                + ".err{background:#f8d7da;padding:10px;border-radius:4px;margin-bottom:10px}.ok{background:#d1e7dd;padding:10px;border-radius:4px}"
                + ".hint{color:#6c757d;font-size:13px}</style></head><body><h2>" + esc(title) + "</h2>" + body + "</body></html>";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;");
    }

    @SuppressWarnings("unused")
    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
