package poc.nav;

import org.junit.jupiter.api.Test;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

import hu.gov.nav.schemas.osa._3_0.api.ManageInvoiceRequest;
import hu.gov.nav.schemas.osa._3_0.api.TokenExchangeRequest;

class RequestFactoryTest {

    private static Config cfg() {
        java.util.Properties p = new java.util.Properties();
        p.setProperty("nav.baseUrl", "https://api-test.onlineszamla.nav.gov.hu");
        p.setProperty("nav.login", "testlogin123456");
        p.setProperty("nav.password", "TestPass123");
        p.setProperty("nav.signingKey", "aa-0000-00000000000000AAAAAAAAAA");
        p.setProperty("nav.exchangeKey", "TESTEXCHANGEKEY0");
        p.setProperty("nav.taxNumber", "84072436");
        p.setProperty("nav.softwareId", "HU00000000POC00001");
        p.setProperty("nav.softwareDevContact", "dev@example.com");
        return Config.from(p);
    }

    private static String marshal(Object o) throws Exception {
        JAXBContext ctx = JAXBContext.newInstance("hu.gov.nav.schemas.osa._3_0.api");
        Marshaller m = ctx.createMarshaller();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        m.marshal(o, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void tokenExchange_hasPasswordHashAndSignature() throws Exception {
        TokenExchangeRequest req = new RequestFactory(cfg()).buildTokenExchange();
        String xml = marshal(req);
        // passwordHash = uppercase SHA-512 of the password
        assertTrue(xml.contains(CryptoUtil.sha512Upper("TestPass123")));
        assertTrue(xml.contains("SHA-512"));
        assertTrue(xml.contains("SHA3-512"));
        assertTrue(xml.contains("testlogin123456")); // login
        assertTrue(xml.contains("84072436"));         // taxNumber
    }

    @Test
    void manageInvoice_signatureMatchesOwnHeaderAndInvoice() throws Exception {
        byte[] invoice = "<x>hello</x>".getBytes(StandardCharsets.UTF_8);
        RequestFactory f = new RequestFactory(cfg());
        ManageInvoiceRequest req = f.buildManageInvoice("TOKEN123", "CREATE", invoice);
        String xml = marshal(req);

        // Pull requestId + timestamp back out of the marshalled request.
        // (Common-namespace elements marshal with the JAXB-assigned prefix, e.g.
        //  <ns2:requestId>; match on the tag suffix so an optional prefix is tolerated.)
        String requestId = between(xml, "requestId>", "</");
        String timestamp = between(xml, "timestamp>", "</");
        String tsMask = java.time.OffsetDateTime.parse(timestamp)
            .withOffsetSameInstant(java.time.ZoneOffset.UTC)
            .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));

        String base64Invoice = Base64.getEncoder().encodeToString(invoice);
        String indexHash = CryptoUtil.sha3_512Upper("CREATE" + base64Invoice);
        String expectedSig = CryptoUtil.sha3_512Upper(
            requestId + tsMask + "aa-0000-00000000000000AAAAAAAAAA" + indexHash);

        assertTrue(xml.contains(expectedSig),
            "requestSignature must equal SHA3-512(requestId+ts+signingKey+indexHash)");
        assertTrue(xml.contains("TOKEN123"));       // exchangeToken present
        assertTrue(xml.contains(base64Invoice));    // invoiceData base64 present
    }

    private static String between(String s, String a, String b) {
        int i = s.indexOf(a) + a.length();
        return s.substring(i, s.indexOf(b, i));
    }
}
