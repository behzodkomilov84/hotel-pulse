package behzoddev.hotelpulse.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class AesGcmCipherTest {

    private final AesGcmCipher cipher = new AesGcmCipher(Base64.getEncoder().encodeToString(new byte[32]));

    @Test
    void encryptThenDecryptReturnsOriginal() {
        String secret = "exely-api-key-123";
        String stored = cipher.encrypt(secret);
        assertTrue(stored.startsWith("v1:"));
        assertFalse(stored.contains(secret));
        assertEquals(secret, cipher.decrypt(stored));
    }

    @Test
    void sameInputGivesDifferentCiphertext() {
        assertNotEquals(cipher.encrypt("a"), cipher.encrypt("a"));
    }

    @Test
    void wrongKeyFailsToDecrypt() {
        byte[] other = new byte[32];
        other[0] = 1;
        AesGcmCipher otherCipher = new AesGcmCipher(Base64.getEncoder().encodeToString(other));
        String stored = cipher.encrypt("secret");
        assertThrows(IllegalStateException.class, () -> otherCipher.decrypt(stored));
    }

    @Test
    void rejectsShortKey() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThrows(IllegalStateException.class, () -> new AesGcmCipher(shortKey));
    }
}
