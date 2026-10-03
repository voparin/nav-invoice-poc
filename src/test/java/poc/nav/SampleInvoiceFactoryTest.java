package poc.nav;

import org.junit.jupiter.api.Test;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.xml.sax.SAXException;
import org.w3c.dom.ls.LSResourceResolver;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class SampleInvoiceFactoryTest {

    // Minimal config for building invoices (no network, no validation of NAV creds).
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

    /** Build a validator for invoiceData.xsd that resolves common/base imports from the xsd dir. */
    private static Validator invoiceDataValidator() throws SAXException {
        SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        File xsdDir = new File("src/main/resources/xsd");
        // Resolve namespace-only imports to local files by namespace -> filename.
        sf.setResourceResolver(new LocalXsdResolver(xsdDir));
        Schema schema = sf.newSchema(new StreamSource(new File(xsdDir, "invoiceData.xsd")));
        return schema.newValidator();
    }

    private static void assertValid(byte[] xml) throws Exception {
        invoiceDataValidator().validate(
            new StreamSource(new ByteArrayInputStream(xml)));
    }

    @Test
    void domesticGoods_isSchemaValid() throws Exception {
        byte[] xml = SampleInvoiceFactory.createDomesticGoods(cfg(), "POC-D-1");
        assertValid(xml);
        String s = new String(xml, StandardCharsets.UTF_8);
        assertTrue(s.contains("<vatPercentage>0.27</vatPercentage>")
                || s.contains("vatPercentage"));
    }

    @Test
    void intraCommunity_isSchemaValid_andVatExemptKBAET() throws Exception {
        byte[] xml = SampleInvoiceFactory.createIntraCommunityAcquisition(cfg(), "POC-I-1");
        assertValid(xml);
        String s = new String(xml, StandardCharsets.UTF_8);
        // Intra-Community supply of goods: VAT-exempt with case code KBAET (ÁFA tv. §89),
        // customer is an EU taxpayer (customerVatStatus OTHER).
        assertTrue(s.contains("vatExemption"));
        assertTrue(s.contains("KBAET"));
        assertTrue(s.contains("OTHER"));
    }

    @Test
    void modify_isSchemaValid_andReferencesOriginal() throws Exception {
        byte[] xml = SampleInvoiceFactory.createModify(cfg(), "POC-M-1", "POC-D-1", BigInteger.ONE);
        assertValid(xml);
        String s = new String(xml, StandardCharsets.UTF_8);
        assertTrue(s.contains("invoiceReference"));
        assertTrue(s.contains("POC-D-1"));
    }

    @Test
    void storno_isSchemaValid_andReferencesOriginal() throws Exception {
        byte[] xml = SampleInvoiceFactory.createStorno(cfg(), "POC-S-1", "POC-I-1", BigInteger.ONE);
        assertValid(xml);
        String s = new String(xml, StandardCharsets.UTF_8);
        assertTrue(s.contains("invoiceReference"));
        assertTrue(s.contains("POC-I-1"));
    }
}
