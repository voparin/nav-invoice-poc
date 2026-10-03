package poc.nav;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

import hu.gov.nav.schemas.osa._3_0.api.ManageInvoiceResponse;

class NavClientTest {

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

    // A well-formed ManageInvoiceResponse. This is the verbatim output of marshalling a
    // ManageInvoiceResponse via the api JAXBContext, so it is guaranteed schema-correct and
    // round-trips: ManageInvoiceResponse lives in the OSA/3.0/api namespace while the
    // header/result (BasicResponseType) live in the NTCA/1.0/common namespace (the default
    // xmlns here), and software/transactionId are in the api namespace.
    private static final String OK_RESPONSE = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <ns2:ManageInvoiceResponse xmlns="http://schemas.nav.gov.hu/NTCA/1.0/common" xmlns:ns2="http://schemas.nav.gov.hu/OSA/3.0/api">
            <header>
                <requestId>RID1</requestId>
                <timestamp>2026-10-03T10:00:00.000Z</timestamp>
                <requestVersion>3.0</requestVersion>
                <headerVersion>1.0</headerVersion>
            </header>
            <result>
                <funcCode>OK</funcCode>
            </result>
            <ns2:software>
                <ns2:softwareId>HU00000000POC00001</ns2:softwareId>
                <ns2:softwareName>poc</ns2:softwareName>
                <ns2:softwareOperation>LOCAL_SOFTWARE</ns2:softwareOperation>
                <ns2:softwareMainVersion>1.0</ns2:softwareMainVersion>
                <ns2:softwareDevName>dev</ns2:softwareDevName>
                <ns2:softwareDevContact>dev@example.com</ns2:softwareDevContact>
            </ns2:software>
            <ns2:transactionId>ABC123</ns2:transactionId>
        </ns2:ManageInvoiceResponse>
        """;

    // A NAV GeneralExceptionResponse (NTCA/1.0/common namespace). It extends BasicResultType,
    // so it carries funcCode/errorCode/message directly. Verbatim marshaller output.
    private static final String ERROR_RESPONSE = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <GeneralExceptionResponse xmlns="http://schemas.nav.gov.hu/NTCA/1.0/common" xmlns:ns2="http://schemas.nav.gov.hu/OSA/3.0/api">
            <funcCode>ERROR</funcCode>
            <errorCode>INVALID_SECURITY_USER</errorCode>
            <message>Invalid user</message>
        </GeneralExceptionResponse>
        """;

    @Test
    void parsesSuccessResponse() {
        Object parsed = new NavClient(cfg())
            .parseResponse(OK_RESPONSE.getBytes(StandardCharsets.UTF_8));
        assertInstanceOf(ManageInvoiceResponse.class, parsed);
        assertEquals("ABC123", ((ManageInvoiceResponse) parsed).getTransactionId());
    }

    @Test
    void throwsOnErrorResponse() {
        NavClient c = new NavClient(cfg());
        NavClient.NavApiException ex = assertThrows(NavClient.NavApiException.class,
            () -> c.parseResponse(ERROR_RESPONSE.getBytes(StandardCharsets.UTF_8)));
        assertTrue(ex.getMessage().contains("INVALID_SECURITY_USER")
                || ex.getMessage().contains("ERROR"));
    }
}
