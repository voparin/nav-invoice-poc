package poc.nav;

import org.junit.jupiter.api.Test;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class ConfigTest {

    private Properties valid() {
        Properties p = new Properties();
        p.setProperty("nav.baseUrl", "https://api-test.onlineszamla.nav.gov.hu");
        p.setProperty("nav.login", "testlogin123456");
        p.setProperty("nav.password", "TestPass123");
        p.setProperty("nav.signingKey", "aa-0000-00000000000000AAAAAAAAAA");
        p.setProperty("nav.exchangeKey", "TESTEXCHANGEKEY0");
        p.setProperty("nav.taxNumber", "84072436");
        p.setProperty("nav.softwareId", "HU00000000POC00001");
        p.setProperty("nav.softwareDevContact", "dev@example.com");
        return p;
    }

    @Test
    void loadsValidConfig() {
        Config c = Config.from(valid());
        assertEquals("testlogin123456", c.login());
        assertEquals("84072436", c.taxNumber());
    }

    @Test
    void rejectsMissingSigningKey() {
        Properties p = valid();
        p.remove("nav.signingKey");
        IllegalArgumentException ex =
            assertThrows(IllegalArgumentException.class, () -> Config.from(p));
        assertTrue(ex.getMessage().contains("signingKey"));
    }

    @Test
    void rejectsBadTaxNumber() {
        Properties p = valid();
        p.setProperty("nav.taxNumber", "123");
        IllegalArgumentException ex =
            assertThrows(IllegalArgumentException.class, () -> Config.from(p));
        assertTrue(ex.getMessage().contains("taxNumber"));
    }

    @Test
    void rejectsBadSoftwareId() {
        Properties p = valid();
        p.setProperty("nav.softwareId", "too-short");
        assertThrows(IllegalArgumentException.class, () -> Config.from(p));
    }
}
