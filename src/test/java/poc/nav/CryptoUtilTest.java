package poc.nav;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;

class CryptoUtilTest {

    private static final String REQUEST_ID = "TSTKFT1222564";
    private static final String TS_MASK = "20171230182545"; // 2017-12-30T18:25:45Z stripped
    private static final String SIGNING_KEY = "ce-8f5e-215119fa7dd621DLMRHRLH2S";

    @Test
    void sha3_512Upper_matchesIndexHashExample() {
        String h1 = CryptoUtil.sha3_512Upper("CREATEQWJjZDEyMzQ=");
        assertEquals(
          "4317798460962869BC67F07C48EA7E4A3AFA301513CEB87B8EB94ECF92BC220A89C"
          + "480F87F0860E85E29A3B6C0463D4F29712C5AD48104A6486CE839DC2F24CB", h1);
    }

    @Test
    void manageInvoiceSignature_matchesSpecExample() {
        String base = REQUEST_ID + TS_MASK + SIGNING_KEY
            + CryptoUtil.sha3_512Upper("CREATEQWJjZDEyMzQ=")
            + CryptoUtil.sha3_512Upper("MODIFYRGNiYTQzMjE=");
        String sig = CryptoUtil.sha3_512Upper(base);
        // NOTE: the oracle string as supplied in the task was 129 hex chars
        // ("...49011D3726000A10DBCF...") which is impossible for a 512-bit
        // digest (must be 128). The stray leading zero in the "3726000A" run
        // was removed to the real SHA3-512 value "372600A", verified
        // independently against the NAV spec base string. Length is also
        // asserted below as a guard.
        assertEquals(128, sig.length());
        assertEquals(
          "60BC80609EE3B8F42FE904200A49A1921A1DADA08D55319ACD40C59F626514B74EEA"
          + "49011D372600A10DBCF8199D590DA9C2841D987308F2D83DAE17C2470C42", sig);
    }

    @Test
    void sha512Upper_isUppercaseHex() {
        String out = CryptoUtil.sha512Upper("abc");
        assertEquals(128, out.length());
        assertEquals(out, out.toUpperCase());
    }

    @Test
    void aesEcbDecrypt_roundTripsWith16ByteKey() throws Exception {
        String key = "TESTEXCHANGEKEY0"; // 16 chars -> AES-128
        byte[] plain = "0123456789ABCDEF".getBytes(StandardCharsets.UTF_8);
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding");
        c.init(javax.crypto.Cipher.ENCRYPT_MODE,
            new javax.crypto.spec.SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"));
        byte[] enc = c.doFinal(plain);
        byte[] dec = CryptoUtil.aesEcbDecrypt(enc, key);
        assertArrayEquals(plain, dec);
    }
}
