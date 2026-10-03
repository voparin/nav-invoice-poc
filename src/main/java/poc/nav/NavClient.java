package poc.nav;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import hu.gov.nav.schemas.ntca._1_0.common.BasicResponseType;
import hu.gov.nav.schemas.ntca._1_0.common.BasicResultType;
import hu.gov.nav.schemas.ntca._1_0.common.FunctionCodeType;
import hu.gov.nav.schemas.ntca._1_0.common.GeneralExceptionResponse;
import hu.gov.nav.schemas.osa._3_0.api.GeneralErrorResponse;
import hu.gov.nav.schemas.osa._3_0.api.ManageInvoiceRequest;
import hu.gov.nav.schemas.osa._3_0.api.ManageInvoiceResponse;
import hu.gov.nav.schemas.osa._3_0.api.TokenExchangeRequest;
import hu.gov.nav.schemas.osa._3_0.api.TokenExchangeResponse;

/**
 * Posts XML to the NAV Online Szamla REST API v3.0 and unmarshals the response,
 * routing NAV error responses to {@link NavApiException}.
 */
public final class NavClient {

    private final Config cfg;
    private final HttpClient http;
    private final JAXBContext ctx;

    public NavClient(Config cfg) {
        this.cfg = cfg;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();
        try {
            // The api context also resolves the referenced common types (via @XmlSeeAlso),
            // so it is the right context for marshalling requests AND unmarshalling responses
            // and error/exception roots.
            this.ctx = JAXBContext.newInstance("hu.gov.nav.schemas.osa._3_0.api");
        } catch (Exception e) {
            throw new IllegalStateException("JAXB context init failed", e);
        }
    }

    public TokenExchangeResponse tokenExchange(TokenExchangeRequest req) {
        return post("tokenExchange", req, TokenExchangeResponse.class);
    }

    public ManageInvoiceResponse manageInvoice(ManageInvoiceRequest req) {
        return post("manageInvoice", req, ManageInvoiceResponse.class);
    }

    private <T> T post(String op, Object requestObj, Class<T> responseType) {
        String url = cfg.baseUrl() + "/invoiceService/v3/" + op;
        byte[] body = marshal(requestObj);
        try {
            HttpRequest httpReq = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/xml")
                .header("Accept", "application/xml")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
            HttpResponse<byte[]> resp =
                http.send(httpReq, HttpResponse.BodyHandlers.ofByteArray());

            // parseResponse unmarshals and either returns the business object or throws
            // NavApiException on a NAV error/exception body or an ERROR funcCode.
            Object parsed = parseResponse(resp.body());
            if (responseType.isInstance(parsed)) {
                return responseType.cast(parsed);
            }
            throw new NavApiException("NAV " + op + " returned unexpected response type "
                + (parsed == null ? "null" : parsed.getClass().getSimpleName())
                + ": " + new String(resp.body(), StandardCharsets.UTF_8));
        } catch (NavApiException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("HTTP call to " + op + " failed", e);
        }
    }

    /**
     * Unmarshals a NAV response body. Returns the business response object on success,
     * or throws {@link NavApiException} when the body is a NAV error/exception response
     * or carries a {@code result/funcCode} of ERROR.
     *
     * <p>Package-visible so the parse/error-routing seam can be unit-tested with canned XML
     * without any network call.</p>
     */
    Object parseResponse(byte[] xml) {
        Object parsed;
        try {
            Unmarshaller u = ctx.createUnmarshaller();
            Object o = u.unmarshal(new ByteArrayInputStream(xml));
            parsed = (o instanceof JAXBElement<?> je) ? je.getValue() : o;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse NAV response: "
                + new String(xml, StandardCharsets.UTF_8), e);
        }

        // Case 1: dedicated error/exception roots carrying funcCode/errorCode/message directly
        // (GeneralExceptionResponse extends BasicResultType).
        if (parsed instanceof BasicResultType result) {
            throw errorFromResult(result, new String(xml, StandardCharsets.UTF_8));
        }

        // Case 2: Online-Invoice general error root (GeneralErrorResponse) — carries a result.
        if (parsed instanceof GeneralErrorResponse err) {
            throw errorFromResult(err.getResult(), new String(xml, StandardCharsets.UTF_8));
        }

        // Case 3: a normal business response whose result/funcCode is ERROR.
        if (parsed instanceof BasicResponseType resp) {
            BasicResultType result = resp.getResult();
            if (result != null && result.getFuncCode() == FunctionCodeType.ERROR) {
                throw errorFromResult(result, new String(xml, StandardCharsets.UTF_8));
            }
        }

        return parsed;
    }

    private NavApiException errorFromResult(BasicResultType result, String raw) {
        if (result == null) {
            return new NavApiException("NAV error response: " + raw);
        }
        String code = result.getErrorCode() != null ? result.getErrorCode()
            : String.valueOf(result.getFuncCode());
        String message = result.getMessage() != null ? result.getMessage() : "";
        return new NavApiException("NAV error " + code
            + (message.isEmpty() ? "" : ": " + message));
    }

    private byte[] marshal(Object obj) {
        try {
            Marshaller m = ctx.createMarshaller();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            m.marshal(obj, out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("marshal failed", e);
        }
    }

    /** Raised when NAV returns an error/exception response instead of the business result. */
    public static final class NavApiException extends RuntimeException {
        public NavApiException(String message) {
            super(message);
        }
    }
}
