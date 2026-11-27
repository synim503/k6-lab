package ru.k6lab.server.load;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import ru.k6lab.core.util.NaturalSort;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Профили нагрузки скрипта: имя, папка и JSON формы «Нагрузка» (операции, сценарии, SLA, переменные, файлы данных). */
@Repository
public class LoadProfileStore {

    public record Profile(long id, long scriptId, String name, String folder, String data,
                          Instant createdAt, String createdBy, Instant updatedAt, String updatedBy) {
        public Profile(long id, long scriptId, String name, String data,
                       Instant createdAt, String createdBy, Instant updatedAt, String updatedBy) {
            this(id, scriptId, name, "", data, createdAt, createdBy, updatedAt, updatedBy);
        }
    }

    public record ProfileRunStats(Instant lastRunAt, long runCount) {
    }

    public static final Comparator<Profile> SMART_ORDER = (a, b) -> {
        String fa = normalizeFolder(a.folder());
        String fb = normalizeFolder(b.folder());
        if (fa.isEmpty() != fb.isEmpty()) {
            return fa.isEmpty() ? -1 : 1;
        }
        int fc = NaturalSort.compare(fa, fb);
        if (fc != 0) {
            return fc;
        }
        return NaturalSort.compare(a.name(), b.name());
    };

    private static final RowMapper<Profile> ROWS = (rs, n) -> new Profile(
            rs.getLong("id"),
            rs.getLong("script_id"),
            rs.getString("name"),
            normalizeFolder(rs.getString("folder")),
            rs.getString("data"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getString("created_by"),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getString("updated_by")
    );

    private final JdbcTemplate jdbc;

    public LoadProfileStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static String normalizeFolder(String folder) {
        if (folder == null) {
            return "";
        }
        String f = folder.strip().replaceAll("\\s+", " ");
        if (f.length() > 120) {
            throw new IllegalArgumentException("Название папки: не более 120 символов");
        }
        return f;
    }

    public List<Profile> list(long scriptId) {
        List<Profile> raw = new ArrayList<>(jdbc.query("select * from load_profile where script_id = ?", ROWS, scriptId));
        raw.sort(SMART_ORDER);
        return raw;
    }

    /** Статистика запусков по профилям одного скрипта (время последнего запуска и число запусков). */
    public Map<Long, ProfileRunStats> runStatsByProfile(long scriptId) {
        Map<Long, ProfileRunStats> out = new HashMap<>();
        jdbc.query("""
                select (request ->> 'loadProfileId') as pid,
                       max(coalesce(started_at, queued_at)) as last_run_at,
                       count(*) as cnt
                from run
                where script_id = ?
                  and (request ->> 'loadProfileId') ~ '^[0-9]+$'
                group by (request ->> 'loadProfileId')
                """, rs -> {
            long pid = Long.parseLong(rs.getString("pid"));
            Timestamp ts = rs.getTimestamp("last_run_at");
            out.put(pid, new ProfileRunStats(ts != null ? ts.toInstant() : null, rs.getLong("cnt")));
        }, scriptId);
        return out;
    }

    /** Число сохранённых профилей нагрузки по каждому скрипту. */
    public Map<Long, Integer> countsByScript() {
        Map<Long, Integer> out = new HashMap<>();
        jdbc.query("select script_id, count(*) as cnt from load_profile group by script_id", rs -> {
            out.put(rs.getLong("script_id"), rs.getInt("cnt"));
        });
        return out;
    }

    public Optional<Profile> find(long scriptId, long id) {
        return jdbc.query("select * from load_profile where script_id = ? and id = ?", ROWS, scriptId, id).stream().findFirst();
    }

    /** Новый профиль или обновление существующего с тем же именем; возвращает id и признак «обновлён». */
    public record Saved(long id, boolean updated) {
    }

    public Saved save(long scriptId, String name, String data, String user) {
        return save(scriptId, name, null, data, user);
    }

    public Saved save(long scriptId, String name, String folder, String data, String user) {
        if (folder == null) {
            return jdbc.queryForObject(
                    "insert into load_profile (script_id, name, folder, data, created_by, updated_by) values (?, ?, '', ?, ?, ?) "
                            + "on conflict (script_id, name) do update set data = excluded.data, updated_at = now(), updated_by = excluded.updated_by "
                            + "returning id, (xmax <> 0) as updated",
                    (rs, n) -> new Saved(rs.getLong("id"), rs.getBoolean("updated")),
                    scriptId, name, data, user, user);
        }
        String normFolder = normalizeFolder(folder);
        return jdbc.queryForObject(
                "insert into load_profile (script_id, name, folder, data, created_by, updated_by) values (?, ?, ?, ?, ?, ?) "
                        + "on conflict (script_id, name) do update set folder = excluded.folder, data = excluded.data, updated_at = now(), updated_by = excluded.updated_by "
                        + "returning id, (xmax <> 0) as updated",
                (rs, n) -> new Saved(rs.getLong("id"), rs.getBoolean("updated")),
                scriptId, name, normFolder, data, user, user);
    }

    public boolean delete(long scriptId, long id) {
        return jdbc.update("delete from load_profile where script_id = ? and id = ?", scriptId, id) == 1;
    }

    /** Массовое удаление профилей нагрузки одного скрипта за один запрос. */
    public int deleteMany(long scriptId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        List<Long> clean = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (clean.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(clean.size(), "?"));
        Object[] args = new Object[clean.size() + 1];
        args[0] = scriptId;
        for (int i = 0; i < clean.size(); i++) {
            args[i + 1] = clean.get(i);
        }
        return jdbc.update("delete from load_profile where script_id = ? and id in (" + placeholders + ")", args);
    }

    /** Перемещает выбранные профили в указанную папку (пустая строка = корень без папки). */
    public int moveToFolder(long scriptId, List<Long> ids, String folder, String user) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        List<Long> clean = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (clean.isEmpty()) {
            return 0;
        }
        String normFolder = normalizeFolder(folder);
        String placeholders = String.join(",", java.util.Collections.nCopies(clean.size(), "?"));
        Object[] args = new Object[clean.size() + 3];
        args[0] = normFolder;
        args[1] = user;
        args[2] = scriptId;
        for (int i = 0; i < clean.size(); i++) {
            args[i + 3] = clean.get(i);
        }
        return jdbc.update(
                "update load_profile set folder = ?, updated_at = now(), updated_by = ? where script_id = ? and id in (" + placeholders + ")",
                args);
    }

    /** Переименовывает папку у всех профилей скрипта (либо расформировывает её при пустом newFolder). */
    public int renameFolder(long scriptId, String oldFolder, String newFolder, String user) {
        String oldNorm = normalizeFolder(oldFolder);
        if (oldNorm.isEmpty()) {
            throw new IllegalArgumentException("Укажите исходную папку");
        }
        String newNorm = normalizeFolder(newFolder);
        return jdbc.update(
                "update load_profile set folder = ?, updated_at = now(), updated_by = ? where script_id = ? and folder = ?",
                newNorm, user, scriptId, oldNorm);
    }

    /** Переименовывает профиль нагрузки (с проверкой уникальности имени в пределах скрипта). */
    public Profile rename(long scriptId, long id, String newName, String user) {
        String n = newName == null ? "" : newName.strip();
        if (n.isEmpty() || n.length() > 120) {
            throw new IllegalArgumentException("Название профиля: от 1 до 120 символов");
        }
        Integer clash = jdbc.queryForObject("select count(*) from load_profile where script_id = ? and name = ? and id <> ?",
                Integer.class, scriptId, n, id);
        if (clash != null && clash > 0) {
            throw new IllegalArgumentException("Профиль с именем «" + n + "» уже существует");
        }
        try {
            int upd = jdbc.update("update load_profile set name = ?, updated_at = now(), updated_by = ? where script_id = ? and id = ?",
                    n, user, scriptId, id);
            if (upd == 0) {
                throw new IllegalArgumentException("Профиль не найден");
            }
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Профиль с именем «" + n + "» уже существует");
        }
        return find(scriptId, id).orElseThrow();
    }

    /** Создаёт копию профиля нагрузки с уникальным именем в той же папке. */
    public Profile duplicate(long scriptId, long id, String user) {
        Profile src = find(scriptId, id).orElseThrow(() -> new IllegalArgumentException("Профиль не найден"));
        java.util.Set<String> names = new java.util.HashSet<>();
        list(scriptId).forEach(p -> names.add(p.name()));
        String copyName = truncateName(src.name(), " (копия)");
        for (int i = 2; names.contains(copyName); i++) {
            copyName = truncateName(src.name(), " (копия " + i + ")");
        }
        try {
            long newId = jdbc.queryForObject(
                    "insert into load_profile (script_id, name, folder, data, created_by, updated_by) values (?, ?, ?, ?, ?, ?) returning id",
                    Long.class, scriptId, copyName, normalizeFolder(src.folder()), src.data(), user, user);
            return find(scriptId, newId).orElseThrow();
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Конфликт имени при копировании профиля «" + copyName + "», повторите попытку");
        }
    }

    private static String truncateName(String base, String suffix) {
        int maxBase = Math.max(1, 120 - suffix.length());
        return (base.length() > maxBase ? base.substring(0, maxBase) : base) + suffix;
    }
}
