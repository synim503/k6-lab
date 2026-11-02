package ru.k6lab.server.common;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.k6lab.server.config.AppProperties;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class FileSystemStorageService implements StorageService {

    private final Path root;

    @Autowired
    public FileSystemStorageService(AppProperties props) {
        this(props.getStorageDir());
    }

    public FileSystemStorageService(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public StoredFile store(String category, String filename, InputStream content) throws IOException {
        String key = sanitize(category) + "/" + LocalDate.now().toString().substring(0, 7)
                + "/" + UUID.randomUUID() + "-" + sanitize(filename);
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        MessageDigest md = Hashes.sha256Digest();
        long size = 0;
        try (OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = content.read(buf)) > 0) {
                md.update(buf, 0, n);
                out.write(buf, 0, n);
                size += n;
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        return new StoredFile(key, size, HexFormat.of().formatHex(md.digest()));
    }

    @Override
    public InputStream open(String key) throws IOException {
        return Files.newInputStream(resolve(key));
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public long size(String key) throws IOException {
        return Files.size(resolve(key));
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }

    private Path resolve(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("Недопустимый ключ хранилища");
        }
        return p;
    }

    private static String sanitize(String name) {
        String s = name == null ? "file" : name.replace('\\', '/');
        s = s.substring(s.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        if (s.isBlank() || s.equals(".") || s.equals("..")) {
            s = "file";
        }
        return s.length() > 150 ? s.substring(s.length() - 150) : s;
    }
}
