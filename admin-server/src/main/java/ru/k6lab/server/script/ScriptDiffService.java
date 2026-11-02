package ru.k6lab.server.script;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import org.springframework.stereotype.Service;
import ru.k6lab.core.util.ZipUtil;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Построчный diff версий скрипта в unified-формате (рисуется diff2html на клиенте). */
@Service
public class ScriptDiffService {

    private static final long MAX_TEXT = 2L * 1024 * 1024;
    private static final Set<String> TEXT_EXT = Set.of("js", "mjs", "ts", "json", "csv", "tsv", "txt", "md", "yml", "yaml",
            "xml", "html", "properties", "sh", "env", "ini", "toml", "proto");

    public record FileDiff(String path, Status status, String unified) {
        public enum Status { ADDED, REMOVED, MODIFIED, BINARY_CHANGED }
    }

    public List<FileDiff> diff(byte[] oldZip, byte[] newZip) throws IOException {
        Map<String, byte[]> a = ZipUtil.readAll(new ByteArrayInputStream(oldZip));
        Map<String, byte[]> b = ZipUtil.readAll(new ByteArrayInputStream(newZip));
        Set<String> paths = new TreeSet<>();
        paths.addAll(a.keySet());
        paths.addAll(b.keySet());
        List<FileDiff> out = new ArrayList<>();
        for (String p : paths) {
            byte[] x = a.get(p);
            byte[] y = b.get(p);
            if (x != null && y != null && Arrays.equals(x, y)) {
                continue;
            }
            boolean text = (x == null || isText(p, x)) && (y == null || isText(p, y));
            if (!text) {
                out.add(new FileDiff(p, x == null ? FileDiff.Status.ADDED : y == null ? FileDiff.Status.REMOVED
                        : FileDiff.Status.BINARY_CHANGED, null));
                continue;
            }
            List<String> la = x == null ? List.of() : lines(x);
            List<String> lb = y == null ? List.of() : lines(y);
            Patch<String> patch = DiffUtils.diff(la, lb);
            List<String> unified = UnifiedDiffUtils.generateUnifiedDiff("a/" + p, "b/" + p, la, patch, 3);
            StringBuilder sb = new StringBuilder("diff --git a/").append(p).append(" b/").append(p).append('\n');
            if (x == null) {
                sb.append("new file mode 100644\n");
            } else if (y == null) {
                sb.append("deleted file mode 100644\n");
            }
            for (String l : unified) {
                sb.append(l).append('\n');
            }
            out.add(new FileDiff(p, x == null ? FileDiff.Status.ADDED : y == null ? FileDiff.Status.REMOVED
                    : FileDiff.Status.MODIFIED, sb.toString()));
        }
        return out;
    }

    private static List<String> lines(byte[] data) {
        return new String(data, StandardCharsets.UTF_8).lines().toList();
    }

    static boolean isText(String path, byte[] data) {
        if (data.length > MAX_TEXT) {
            return false;
        }
        int dot = path.lastIndexOf('.');
        String ext = dot >= 0 ? path.substring(dot + 1).toLowerCase() : "";
        if (TEXT_EXT.contains(ext)) {
            return true;
        }
        for (int i = 0; i < Math.min(data.length, 8000); i++) {
            if (data[i] == 0) {
                return false;
            }
        }
        return true;
    }
}
