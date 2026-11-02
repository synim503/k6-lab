package ru.k6lab.server.dev;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.nio.file.Path;

/** Локальный PostgreSQL без Docker для ручной проверки: java -cp ... DevPostgres [порт] [каталог данных]. Блокируется до Ctrl+C. */
public final class DevPostgres {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5544;
        Path data = Path.of(args.length > 1 ? args[1] : "target/dev-pg");
        try (EmbeddedPostgres pg = EmbeddedPostgres.builder().setPort(port).setDataDirectory(data).setCleanDataDirectory(false).start()) {
            try (var c = pg.getPostgresDatabase().getConnection(); var st = c.createStatement()) {
                st.execute("do $$ begin if not exists (select from pg_roles where rolname='k6lab') then create role k6lab login superuser password 'k6lab'; end if; end $$");
                var rs = st.executeQuery("select 1 from pg_database where datname='k6lab'");
                if (!rs.next()) {
                    st.execute("create database k6lab owner k6lab");
                }
            }
            System.out.println("PostgreSQL готов: jdbc:postgresql://localhost:" + port + "/k6lab  (k6lab/k6lab)");
            Thread.currentThread().join();
        }
    }
}
