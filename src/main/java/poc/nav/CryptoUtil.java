package poc.nav;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pure crypto helpers for NAV Online Számla REST API v3.0.
 */
final class CryptoUtil {

    private CryptoUtil() {
    }

    static String sha512Upper(String input) {
        return digestUpper("SHA-512", input);
    }

    static String sha3_512Upper(String input) {
        return digestUpper("SHA3-512", input);
    }

    static byte[] aesEcbDecrypt(byte[] cipherText, String keyLiteral) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            SecretKeySpec key = new SecretKeySpec(
                keyLiteral.getBytes(StandardCharsets.UTF_8), "AES");
            cipher.init(Cipher.DECRYPT_MODE, key);
            return cipher.doFinal(cipherText);
        } catch (Exception e) {
            throw new IllegalStateException("AES/ECB/NoPadding decrypt failed", e);
        }
    }

    private static String digestUpper(String algorithm, String input) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02X", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(algorithm + " digest failed", e);
        }
    }
}
