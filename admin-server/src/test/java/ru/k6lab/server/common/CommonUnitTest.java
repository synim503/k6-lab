package ru.k6lab.server.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommonUnitTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    // ---------------------------------------------------------------- SecretCipher

    @Test
    void cipherRoundTripsAndUsesRandomIv() {
        SecretCipher c = new SecretCipher(KEY);
        byte[] a = c.encrypt("пароль-123");
        byte[] b = c.encrypt("пароль-123");
        assertThat(a).isNotEqualTo(b);
        assertThat(c.decrypt(a)).isEqualTo("пароль-123");
        assertThat(c.decrypt(b)).isEqualTo("пароль-123");
        assertThat(new String(a, StandardCharsets.ISO_8859_1)).doesNotContain("123");
    }

    @Test
    void cipherHandlesNullAndEmpty() {
        SecretCipher c = new SecretCipher(KEY);
        assertThat(c.encrypt(null)).isNull();
        assertThat(c.decrypt(null)).isNull();
        assertThat(c.decrypt(c.encrypt(""))).isEmpty();
    }

    @Test
    void cipherDetectsTamperingAndWrongKey() {
        SecretCipher c = new SecretCipher(KEY);
        byte[] blob = c.encrypt("secret");
        blob[blob.length - 1] ^= 1;
        assertThatThrownBy(() -> c.decrypt(blob)).isInstanceOf(IllegalStateException.class);

        byte[] ok = c.encrypt("secret");
        byte[] other = new byte[32];
        other[0] = 1;
        SecretCipher wrong = new SecretCipher(Base64.getEncoder().encodeToString(other));
        assertThatThrownBy(() -> wrong.decrypt(ok)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cipherRequiresValidKey() {
        assertThatThrownBy(() -> new SecretCipher("")).hasMessageContaining("K6LAB_MASTER_KEY");
        assertThatThrownBy(() -> new SecretCipher("!!!")).hasMessageContaining("base64");
        assertThatThrownBy(() -> new SecretCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .hasMessageContaining("32");
    }

    // ---------------------------------------------------------------- SecretMasker

    @Test
    void maskerHidesSecretsLongestFirst() {
        SecretMasker m = new SecretMasker().add("pass", "pass-word-123", null, "ab");
        assertThat(m.mask("sudo pass-word-123 and pass")).isEqualTo("sudo ****** and ******");
        // слишком короткие секреты (меньше 3 символов) не маскируются, чтобы не портить вывод
        assertThat(m.mask("ab")).isEqualTo("ab");
        assertThat(m.mask(null)).isNull();
    }

    // ---------------------------------------------------------------- Storage

    @Test
    void storageStoresWithSha256AndBlocksTraversal(@TempDir Path dir) throws IOException {
        FileSystemStorageService s = new FileSystemStorageService(dir);
        var stored = s.store("artifacts", "../../evil name.jar", new ByteArrayInputStream("hello".getBytes()));
        assertThat(stored.size()).isEqualTo(5);
        assertThat(stored.sha256()).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        assertThat(stored.key()).startsWith("artifacts/").doesNotContain("..").endsWith("evil_name.jar");
        assertThat(s.exists(stored.key())).isTrue();
        assertThat(s.size(stored.key())).isEqualTo(5);
        try (var in = s.open(stored.key())) {
            assertThat(new String(in.readAllBytes())).isEqualTo("hello");
        }
        assertThatThrownBy(() -> s.open("../outside")).isInstanceOf(IllegalArgumentException.class);
        s.delete(stored.key());
        assertThat(s.exists(stored.key())).isFalse();
    }

    @Test
    void storageDoesNotLeaveFilesOnFailedRead(@TempDir Path dir) {
        FileSystemStorageService s = new FileSystemStorageService(dir);
        assertThatThrownBy(() -> s.store("x", "f", new java.io.InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("boom");
            }
        })).isInstanceOf(IOException.class);
        try (var files = java.nio.file.Files.walk(dir)) {
            assertThat(files.filter(java.nio.file.Files::isRegularFile)).isEmpty();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void hashesProduceUniqueTokens() {
        assertThat(Hashes.randomToken()).hasSize(43).isNotEqualTo(Hashes.randomToken());
        assertThat(Hashes.sha256Hex("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
