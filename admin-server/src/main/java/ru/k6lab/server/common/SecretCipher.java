package ru.k6lab.server.common;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.k6lab.server.config.AppProperties;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM. Формат: 0x01 | IV(12) | шифртекст+тег. Ключ - из конфигурации (env K6LAB_MASTER_KEY).
 */
@Component
public class SecretCipher {

    private static final byte VERSION = 1;
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public SecretCipher(AppProperties props) {
        this(props.getMasterKey());
    }

    public SecretCipher(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("Не задан мастер-ключ шифрования секретов. Задайте переменную окружения "
                    + "K6LAB_MASTER_KEY (base64 от 32 байт, например: openssl rand -base64 32).");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("K6LAB_MASTER_KEY должен быть в формате base64", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException("K6LAB_MASTER_KEY должен содержать ровно 32 байта (сейчас " + raw.length + ")");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public byte[] encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[1 + IV_LEN + ct.length];
            out[0] = VERSION;
            System.arraycopy(iv, 0, out, 1, IV_LEN);
            System.arraycopy(ct, 0, out, 1 + IV_LEN, ct.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ошибка шифрования", e);
        }
    }

    public String decrypt(byte[] blob) {
        if (blob == null) {
            return null;
        }
        if (blob.length < 1 + IV_LEN + 16 || blob[0] != VERSION) {
            throw new IllegalStateException("Неизвестный формат зашифрованного значения");
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, blob, 1, IV_LEN));
            return new String(c.doFinal(blob, 1 + IV_LEN, blob.length - 1 - IV_LEN), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Не удалось расшифровать значение (неверный мастер-ключ?)", e);
        }
    }
}
