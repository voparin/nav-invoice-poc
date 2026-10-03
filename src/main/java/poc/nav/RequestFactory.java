package poc.nav;

import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;

import hu.gov.nav.schemas.osa._3_0.api.*;      // request/response roots + SoftwareType
import hu.gov.nav.schemas.ntca._1_0.common.*;  // BasicHeaderType, UserHeaderType, CryptoType

/** Assembles signed TokenExchangeRequest and ManageInvoiceRequest objects. */
public final class RequestFactory {

    private static final DateTimeFormatter SIG_TS =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String REQUEST_VERSION = "3.0";
    private static final String HEADER_VERSION = "1.0";

    private final Config cfg;
    private final hu.gov.nav.schemas.osa._3_0.api.ObjectFactory api =
        new hu.gov.nav.schemas.osa._3_0.api.ObjectFactory();

    public RequestFactory(Config cfg) { this.cfg = cfg; }

    public TokenExchangeRequest buildTokenExchange() {
        String requestId = newRequestId();
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC).withNano(
            // truncate to millisecond precision so XML tag and signing mask agree
            (ZonedDateTime.now(ZoneOffset.UTC).getNano() / 1_000_000) * 1_000_000);

        TokenExchangeRequest req = api.createTokenExchangeRequest();
        req.setHeader(header(requestId, now));
        // signature outside manageInvoice: SHA3-512(requestId + ts + signingKey)
        String sig = CryptoUtil.sha3_512Upper(requestId + now.format(SIG_TS) + cfg.signingKey());
        req.setUser(user(sig));
        req.setSoftware(software());
        return req;
    }

    public ManageInvoiceRequest buildManageInvoice(String exchangeToken,
                                                   String invoiceOperation,
                                                   byte[] invoiceXml) {
        String requestId = newRequestId();
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        now = now.withNano((now.getNano() / 1_000_000) * 1_000_000);
        String base64Invoice = Base64.getEncoder().encodeToString(invoiceXml);

        // requestSignature for manageInvoice: partial + per-index hash
        String indexHash = CryptoUtil.sha3_512Upper(invoiceOperation + base64Invoice);
        String sig = CryptoUtil.sha3_512Upper(
            requestId + now.format(SIG_TS) + cfg.signingKey() + indexHash);

        ManageInvoiceRequest req = api.createManageInvoiceRequest();
        req.setHeader(header(requestId, now));
        req.setUser(user(sig));
        req.setSoftware(software());
        req.setExchangeToken(exchangeToken);

        InvoiceOperationListType list = api.createInvoiceOperationListType();
        list.setCompressedContent(false);
        InvoiceOperationType op = api.createInvoiceOperationType();
        op.setIndex(1);
        op.setInvoiceOperation(ManageInvoiceOperationType.fromValue(invoiceOperation));
        op.setInvoiceData(invoiceXml); // JAXB base64Binary field takes raw bytes
        list.getInvoiceOperation().add(op);
        req.setInvoiceOperations(list);
        return req;
    }

    private BasicHeaderType header(String requestId, ZonedDateTime now) {
        BasicHeaderType h = new BasicHeaderType();
        h.setRequestId(requestId);
        h.setTimestamp(toXmlCal(now));
        h.setRequestVersion(REQUEST_VERSION);
        h.setHeaderVersion(HEADER_VERSION);
        return h;
    }

    private UserHeaderType user(String requestSignature) {
        UserHeaderType u = new UserHeaderType();
        u.setLogin(cfg.login());
        CryptoType pwd = new CryptoType();
        pwd.setValue(CryptoUtil.sha512Upper(cfg.password()));
        pwd.setCryptoType("SHA-512");
        u.setPasswordHash(pwd);
        CryptoType sig = new CryptoType();
        sig.setValue(requestSignature);
        sig.setCryptoType("SHA3-512");
        u.setRequestSignature(sig);
        u.setTaxNumber(cfg.taxNumber());
        return u;
    }

    private SoftwareType software() {
        SoftwareType s = api.createSoftwareType();
        s.setSoftwareId(cfg.softwareId());
        s.setSoftwareName("NAV Invoice PoC");
        s.setSoftwareOperation(SoftwareOperationType.LOCAL_SOFTWARE);
        s.setSoftwareMainVersion("1.0");
        s.setSoftwareDevName("PoC Developer");
        s.setSoftwareDevContact(cfg.softwareDevContact());
        s.setSoftwareDevCountryCode("HU");
        return s;
    }

    private static String newRequestId() {
        // pattern [+a-zA-Z0-9_]{1,30}; use alnum only, <=30 chars
        return "RID" + System.currentTimeMillis()
            + Integer.toHexString((int) (Math.random() * 0xFFFF));
    }

    /**
     * Build an XMLGregorianCalendar in UTC with explicit millisecond precision so JAXB
     * marshals the timestamp as exactly yyyy-MM-dd'T'HH:mm:ss.SSS'Z' (literal Z, millis
     * always present). Timezone offset 0 marshals as 'Z'; the signing mask is derived
     * from the same ZonedDateTime instant, keeping XML tag and signature consistent.
     */
    private static XMLGregorianCalendar toXmlCal(ZonedDateTime t) {
        try {
            return DatatypeFactory.newInstance().newXMLGregorianCalendar(
                t.getYear(),
                t.getMonthValue(),
                t.getDayOfMonth(),
                t.getHour(),
                t.getMinute(),
                t.getSecond(),
                t.getNano() / 1_000_000, // milliseconds
                0);                      // UTC offset -> marshals as 'Z'
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
