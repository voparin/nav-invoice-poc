package poc.nav;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Loads and validates NAV Online Szamla credentials and software metadata.
 */
final class Config {

    private static final Pattern LOGIN = Pattern.compile("[a-zA-Z0-9]{6,15}");
    private static final Pattern TAX_NUMBER = Pattern.compile("[0-9]{8}");
    // NAV spec SoftwareIdType pattern: exactly 18 chars of [0-9A-Z-].
    private static final Pattern SOFTWARE_ID = Pattern.compile("[0-9A-Z\\-]{18}");

    private final String baseUrl;
    private final String login;
    private final String password;
    private final String signingKey;
    private final String exchangeKey;
    private final String taxNumber;
    private final String softwareId;
    private final String softwareDevContact;

    private Config(String baseUrl, String login, String password, String signingKey,
                   String exchangeKey, String taxNumber, String softwareId,
                   String softwareDevContact) {
        this.baseUrl = baseUrl;
        this.login = login;
        this.password = password;
        this.signingKey = signingKey;
        this.exchangeKey = exchangeKey;
        this.taxNumber = taxNumber;
        this.softwareId = softwareId;
        this.softwareDevContact = softwareDevContact;
    }

    static Config fromFile(String path) {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            p.load(in);
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to read config file: " + path, e);
        }
        return from(p);
    }

    static Config from(Properties p) {
        String baseUrl = required(p, "nav.baseUrl");
        String login = required(p, "nav.login");
        String password = required(p, "nav.password");
        String signingKey = required(p, "nav.signingKey");
        String exchangeKey = required(p, "nav.exchangeKey");
        String taxNumber = required(p, "nav.taxNumber");
        String softwareId = required(p, "nav.softwareId");
        String softwareDevContact = required(p, "nav.softwareDevContact");

        if (!LOGIN.matcher(login).matches()) {
            throw new IllegalArgumentException(
                "Invalid login: must match [a-zA-Z0-9]{6,15}");
        }
        if (!TAX_NUMBER.matcher(taxNumber).matches()) {
            throw new IllegalArgumentException(
                "Invalid taxNumber: must match [0-9]{8}");
        }
        if (exchangeKey.length() != 16) {
            throw new IllegalArgumentException(
                "Invalid exchangeKey: must be exactly 16 characters");
        }
        if (!SOFTWARE_ID.matcher(softwareId).matches()) {
            throw new IllegalArgumentException(
                "Invalid softwareId: must match [0-9A-Z\\-]{18}");
        }

        return new Config(baseUrl, login, password, signingKey, exchangeKey,
                taxNumber, softwareId, softwareDevContact);
    }

    private static String required(Properties p, String key) {
        String shortName = key.startsWith("nav.") ? key.substring(4) : key;
        String value = p.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing required config: " + shortName);
        }
        return value.trim();
    }

    String baseUrl() { return baseUrl; }
    String login() { return login; }
    String password() { return password; }
    String signingKey() { return signingKey; }
    String exchangeKey() { return exchangeKey; }
    String taxNumber() { return taxNumber; }
    String softwareId() { return softwareId; }
    String softwareDevContact() { return softwareDevContact; }
}
