package poc.nav;

import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.util.List;

import hu.gov.nav.schemas.osa._3_0.api.ManageInvoiceResponse;
import hu.gov.nav.schemas.osa._3_0.api.TokenExchangeResponse;
import hu.gov.nav.schemas.osa._3_0.api.ManageInvoiceRequest;
import hu.gov.nav.schemas.osa._3_0.api.QueryTransactionStatusResponse;
import hu.gov.nav.schemas.osa._3_0.api.ProcessingResultType;
import hu.gov.nav.schemas.osa._3_0.api.BusinessValidationResultType;
import hu.gov.nav.schemas.osa._3_0.api.PointerType;
import hu.gov.nav.schemas.ntca._1_0.common.TechnicalValidationResultType;

/**
 * CLI entry point.
 *  - Default: tokenExchange -> submit four invoice variants -> print each transactionId.
 *  - "status &lt;transactionId&gt;": query the async processing result of a transaction
 *    and print per-invoice status + any technical/business validation errors.
 *
 * Usage:
 *   java -jar nav-invoice-poc.jar [config.properties]
 *   java -jar nav-invoice-poc.jar status &lt;transactionId&gt; [config.properties]
 */
public final class App {

    public static void main(String[] args) {
        try {
            if (args.length > 0 && args[0].equals("status")) {
                if (args.length < 2) {
                    System.err.println("Usage: status <transactionId> [config.properties]");
                    System.exit(1);
                }
                String transactionId = args[1];
                String configPath = args.length > 2 ? args[2] : "config.properties";
                queryStatus(Config.fromFile(configPath), transactionId);
                return;
            }

            String configPath = args.length > 0 ? args[0] : "config.properties";
            Config cfg = Config.fromFile(configPath);
            RequestFactory factory = new RequestFactory(cfg);
            NavClient client = new NavClient(cfg);

            long stamp = System.currentTimeMillis();
            String domesticNo = "POC-D-" + stamp;   // stays as a plain CREATE
            String intraNo    = "POC-I-" + stamp;
            String baseForModNo = "POC-BM-" + stamp; // base invoice that MODIFY targets
            String baseForStoNo = "POC-BS-" + stamp; // base invoice that STORNO targets
            String modifyNo   = "POC-M-" + stamp;
            String stornoNo   = "POC-S-" + stamp;

            // Each manageInvoice call needs its OWN exchange token: a NAV token is
            // single-use (reusing one yields INVALID_EXCHANGE_TOKEN).
            // MODIFY/STORNO must reference an invoice that has reached DONE on NAV's
            // side, so we submit the base invoices first and poll until DONE.

            // 1) CREATE domestic goods (belföldi termékértékesítés)
            System.out.println("CREATE domestic goods invoice " + domesticNo);
            submit(cfg, factory, client, "CREATE domestic", "CREATE",
                SampleInvoiceFactory.createDomesticGoods(cfg, domesticNo));

            // 2) CREATE intra-Community supply (közösségen belüli termékértékesítés)
            System.out.println("CREATE intra-Community supply invoice " + intraNo);
            submit(cfg, factory, client, "CREATE intra-Community", "CREATE",
                SampleInvoiceFactory.createIntraCommunityAcquisition(cfg, intraNo));

            // 3) MODIFY (módosító): first create a base invoice and wait for it to be DONE,
            //    then submit a modify that references it.
            System.out.println("CREATE base invoice for MODIFY " + baseForModNo);
            String baseModTx = submit(cfg, factory, client, "CREATE base-for-modify", "CREATE",
                SampleInvoiceFactory.createDomesticGoods(cfg, baseForModNo));
            waitForDone(cfg, baseModTx, "base-for-modify");
            System.out.println("MODIFY invoice " + modifyNo + " (references " + baseForModNo + ")");
            submit(cfg, factory, client, "MODIFY", "MODIFY",
                SampleInvoiceFactory.createModify(cfg, modifyNo, baseForModNo, BigInteger.ONE));

            // 4) STORNO (érvénytelenítő): same pattern with a separate base invoice.
            System.out.println("CREATE base invoice for STORNO " + baseForStoNo);
            String baseStoTx = submit(cfg, factory, client, "CREATE base-for-storno", "CREATE",
                SampleInvoiceFactory.createDomesticGoods(cfg, baseForStoNo));
            waitForDone(cfg, baseStoTx, "base-for-storno");
            System.out.println("STORNO invoice " + stornoNo + " (references " + baseForStoNo + ")");
            submit(cfg, factory, client, "STORNO", "STORNO",
                SampleInvoiceFactory.createStorno(cfg, stornoNo, baseForStoNo, BigInteger.ONE));

            System.out.println("DONE. Query each transactionId via /queryTransactionStatus.");
        } catch (NavClient.NavApiException e) {
            System.err.println("NAV rejected a request:");
            System.err.println("  " + e.getMessage());
            System.exit(2);
        } catch (IllegalArgumentException e) {
            System.err.println("Configuration problem: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Unexpected error: " + e.getMessage());
            e.printStackTrace();
            System.exit(3);
        }
    }

    /** Request a fresh single-use exchange token and return its decrypted value. */
    private static String freshToken(Config cfg, RequestFactory factory, NavClient client) {
        TokenExchangeResponse teResp = client.tokenExchange(factory.buildTokenExchange());
        byte[] decrypted = CryptoUtil.aesEcbDecrypt(
            teResp.getEncodedExchangeToken(), cfg.exchangeKey());
        return new String(decrypted, StandardCharsets.UTF_8).trim();
    }

    /** Fetch a fresh token, build+send one manageInvoice, print + return its transactionId. */
    private static String submit(Config cfg, RequestFactory factory, NavClient client,
                                 String label, String operation, byte[] invoiceXml) {
        String token = freshToken(cfg, factory, client);
        ManageInvoiceResponse resp = client.manageInvoice(
            factory.buildManageInvoice(token, operation, invoiceXml));
        printTx(label, resp);
        return resp.getTransactionId();
    }

    /** Poll queryTransactionStatus until the invoice is DONE (or give up after a few tries). */
    private static void waitForDone(Config cfg, String transactionId, String label)
            throws InterruptedException {
        RequestFactory factory = new RequestFactory(cfg);
        NavClient client = new NavClient(cfg);
        for (int attempt = 1; attempt <= 10; attempt++) {
            Thread.sleep(3000);
            QueryTransactionStatusResponse resp = client.queryTransactionStatus(
                factory.buildQueryTransactionStatus(transactionId, false));
            if (resp.getProcessingResults() != null
                    && !resp.getProcessingResults().getProcessingResult().isEmpty()) {
                ProcessingResultType pr = resp.getProcessingResults().getProcessingResult().get(0);
                String status = String.valueOf(pr.getInvoiceStatus());
                System.out.println("  waiting for " + label + ": " + status);
                if ("DONE".equals(status)) {
                    return;
                }
                if ("ABORTED".equals(status)) {
                    throw new IllegalStateException(label + " was ABORTED by NAV; cannot "
                        + "reference it. Run `status " + transactionId + "` for details.");
                }
            }
        }
        throw new IllegalStateException("Timed out waiting for " + label
            + " to reach DONE (transactionId " + transactionId + ").");
    }

    /** Query and print the async processing result + validation errors for a transaction. */
    private static void queryStatus(Config cfg, String transactionId) {
        RequestFactory factory = new RequestFactory(cfg);
        NavClient client = new NavClient(cfg);
        System.out.println("Querying status of transaction " + transactionId + " ...");
        QueryTransactionStatusResponse resp = client.queryTransactionStatus(
            factory.buildQueryTransactionStatus(transactionId, false));

        if (resp.getProcessingResults() == null
                || resp.getProcessingResults().getProcessingResult().isEmpty()) {
            System.out.println("  No processing results yet (still RECEIVED/PROCESSING?). "
                + "Try again shortly.");
            return;
        }
        for (ProcessingResultType pr : resp.getProcessingResults().getProcessingResult()) {
            System.out.println("  invoice index " + pr.getIndex()
                + " — status: " + pr.getInvoiceStatus());
            List<TechnicalValidationResultType> tech = pr.getTechnicalValidationMessages();
            if (tech != null) {
                for (TechnicalValidationResultType m : tech) {
                    System.out.println("    [TECHNICAL " + m.getValidationResultCode() + "] "
                        + m.getValidationErrorCode() + ": " + m.getMessage());
                }
            }
            List<BusinessValidationResultType> biz = pr.getBusinessValidationMessages();
            if (biz != null) {
                for (BusinessValidationResultType m : biz) {
                    String loc = m.getPointer() != null ? describePointer(m.getPointer()) : "";
                    System.out.println("    [BUSINESS " + m.getValidationResultCode() + "] "
                        + m.getValidationErrorCode() + ": " + m.getMessage() + loc);
                }
            }
        }
    }

    private static String describePointer(PointerType p) {
        StringBuilder sb = new StringBuilder();
        if (p.getTag() != null)   sb.append(" tag=").append(p.getTag());
        if (p.getValue() != null) sb.append(" value=").append(p.getValue());
        if (p.getLine() != null)  sb.append(" line=").append(p.getLine());
        return sb.length() == 0 ? "" : " (" + sb.toString().trim() + ")";
    }

    private static void printTx(String label, ManageInvoiceResponse resp) {
        System.out.println("  [" + label + "] transactionId = " + resp.getTransactionId());
    }
}
