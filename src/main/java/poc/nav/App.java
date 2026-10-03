package poc.nav;

import java.nio.charset.StandardCharsets;
import java.math.BigInteger;

import hu.gov.nav.schemas.osa._3_0.api.ManageInvoiceResponse;
import hu.gov.nav.schemas.osa._3_0.api.TokenExchangeResponse;
import hu.gov.nav.schemas.osa._3_0.api.ManageInvoiceRequest;

/**
 * CLI entry point. Flow: tokenExchange -> decrypt token -> submit four invoice variants
 * (CREATE domestic, CREATE intra-Community, MODIFY, STORNO) -> print each transactionId.
 */
public final class App {

    public static void main(String[] args) {
        String configPath = args.length > 0 ? args[0] : "config.properties";
        try {
            Config cfg = Config.fromFile(configPath);
            RequestFactory factory = new RequestFactory(cfg);
            NavClient client = new NavClient(cfg);

            long stamp = System.currentTimeMillis();
            String domesticNo = "POC-D-" + stamp;
            String intraNo    = "POC-I-" + stamp;
            String modifyNo   = "POC-M-" + stamp;
            String stornoNo   = "POC-S-" + stamp;

            // Each manageInvoice call needs its OWN exchange token: a NAV token is
            // single-use (reusing one yields INVALID_EXCHANGE_TOKEN).

            // 1) CREATE domestic goods (belföldi termékértékesítés)
            System.out.println("CREATE domestic goods invoice " + domesticNo);
            submit(cfg, factory, client, "CREATE domestic", "CREATE",
                SampleInvoiceFactory.createDomesticGoods(cfg, domesticNo));

            // 2) CREATE intra-Community acquisition (közösségen belüli termékbeszerzés)
            System.out.println("CREATE intra-Community acquisition invoice " + intraNo);
            submit(cfg, factory, client, "CREATE intra-Community", "CREATE",
                SampleInvoiceFactory.createIntraCommunityAcquisition(cfg, intraNo));

            // 3) MODIFY (módosító) referencing the domestic invoice, modificationIndex = 1
            System.out.println("MODIFY invoice " + modifyNo + " (references " + domesticNo + ")");
            submit(cfg, factory, client, "MODIFY", "MODIFY",
                SampleInvoiceFactory.createModify(cfg, modifyNo, domesticNo, BigInteger.ONE));

            // 4) STORNO (érvénytelenítő) referencing the intra-Community invoice, index = 1
            System.out.println("STORNO invoice " + stornoNo + " (references " + intraNo + ")");
            submit(cfg, factory, client, "STORNO", "STORNO",
                SampleInvoiceFactory.createStorno(cfg, stornoNo, intraNo, BigInteger.ONE));

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

    /** Fetch a fresh token, build+send one manageInvoice, print its transactionId. */
    private static void submit(Config cfg, RequestFactory factory, NavClient client,
                               String label, String operation, byte[] invoiceXml) {
        String token = freshToken(cfg, factory, client);
        ManageInvoiceResponse resp = client.manageInvoice(
            factory.buildManageInvoice(token, operation, invoiceXml));
        printTx(label, resp);
    }

    private static void printTx(String label, ManageInvoiceResponse resp) {
        System.out.println("  [" + label + "] transactionId = " + resp.getTransactionId());
    }
}
