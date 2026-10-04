package behzoddev.hotelpulse.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Maxfiy qiymatlarni (Exely API kalitlari) bazada saqlash uchun AES-256-GCM.
 * Shifrlangan matn formati: "v1:" + base64(iv[12] + ciphertext+tag).
 */
@Slf4j
@Component
public class AesGcmCipher {

    private static final String PREFIX = "v1:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmCipher(@Value("${app.encryption-key:}") String base64Key) {
        this.key = new SecretKeySpec(resolveKey(base64Key), "AES");
    }

    private static byte[] resolveKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            // Faqat lokal ishlab chiqish uchun: production'da APP_ENCRYPTION_KEY
            // berilmasa, bu kalit bilan shifrlangan ma'lumot himoyalanmagan hisoblanadi.
            log.warn("APP_ENCRYPTION_KEY berilmagan — lokal ishlab chiqish kaliti ishlatilmoqda. Production'da albatta bering!");
            try {
                return MessageDigest.getInstance("SHA-256")
                        .digest("hotelpulse-local-dev-key".getBytes(StandardCharsets.UTF_8));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
        byte[] decoded = Base64.getDecoder().decode(base64Key.trim());
        if (decoded.length != 32) {
            throw new IllegalStateException("APP_ENCRYPTION_KEY base64 ko'rinishidagi 32 baytlik kalit bo'lishi kerak");
        }
        return decoded;
    }

    public String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Shifrlashda xatolik", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        if (!stored.startsWith(PREFIX)) {
            throw new IllegalStateException("Noma'lum shifr formati");
        }
        try {
            byte[] data = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 0, IV_LENGTH));
            byte[] plain = cipher.doFinal(data, IV_LENGTH, data.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Shifrni ochishda xatolik (APP_ENCRYPTION_KEY o'zgarganmi?)", e);
        }
    }
}
