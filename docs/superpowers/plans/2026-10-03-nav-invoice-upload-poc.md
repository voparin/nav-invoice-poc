# NAV Invoice Upload PoC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Java CLI that generates a sample Hungarian invoice, signs/encrypts the request per NAV Online Számla REST API v3.0, and performs a live `tokenExchange` → `manageInvoice` round-trip against the NAV test endpoint, printing the returned `transactionId`.

**Architecture:** Maven project, JDK 17. JAXB classes generated from the official NAV XSDs at build time. Pure-function `CryptoUtil` (SHA-512, SHA3-512, AES-128-ECB) unit-tested against the spec's worked example. A `NavClient` posts XML via Java 11 `HttpClient`. `App` orchestrates the two-call flow. Credentials load from a git-ignored `config.properties`.

**Tech Stack:** Java 17, Maven, `jaxb2-maven-plugin` (xjc), `jakarta.xml.bind` + `glassfish jaxb-runtime`, JUnit 5, Java built-in `java.security.MessageDigest` / `javax.crypto.Cipher` / `java.net.http.HttpClient`.

**Spec reference:** `docs/superpowers/specs/2026-10-03-nav-invoice-upload-poc-design.md`

---

## Build Notes (established during Tasks 0–1 — AUTHORITATIVE)

- **Toolchain is not on PATH.** Every Bash command running `java`/`mvn` must first:
  `export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"; export PATH="$JAVA_HOME/bin:/opt/homebrew/opt/maven/bin:$PATH"`
  (JDK 17 pinned; openjdk@27 is also installed as a Maven dep — do not use it.)
- **JAXB plugin:** `org.jvnet.jaxb:jaxb-maven-plugin:4.0.16` (not the plan's original
  `jaxb2-maven-plugin`, which cannot drive an OASIS catalog on JDK 17). Catalog uses
  `<public publicId="<namespace>" uri="<file>">` entries with `prefer="public"`, because
  NAV's namespace-only `<xs:import>` makes Xerces resolve by publicId, not systemId.
- **Generated packages (use these exact names in all imports):**
  - API roots + `SoftwareType`: `hu.gov.nav.schemas.osa._3_0.api`
    (`ManageInvoiceRequest`, `ManageInvoiceResponse`, `TokenExchangeRequest`,
    `TokenExchangeResponse`, `SoftwareType`)
  - Invoice data: `hu.gov.nav.schemas.osa._3_0.data` (`InvoiceData`, `InvoiceReferenceType`,
    `VatRateType`, `DetailedReasonType`, line/summary types)
  - Base: `hu.gov.nav.schemas.osa._3_0.base`
  - Common header/auth/crypto: `hu.gov.nav.schemas.ntca._1_0.common`
    (`BasicHeaderType`, `UserHeaderType`, `CryptoType`)
  - Each package has its own `ObjectFactory`.
- **common.xsd deviation (known, bounded):** the only published NTCA/1.0 `common.xsd`
  (Common-1.0.RC3) defines `PageType`, but OSA 3.0 `invoiceApi.xsd` references
  `common:RequestPageType` / `common:ResponsePageType`. Task 1 restored those two
  simpleTypes (int ≥1 and int ≥0) so codegen resolves. These types are used ONLY by
  query operations, which this PoC does not call — so this does not affect tokenExchange
  or manageInvoice correctness. Do not revert it; it is required for generation.

---

## File Structure

```
apeh/
├── pom.xml                               # Maven build + jaxb codegen + deps
├── .gitignore                            # ignore target/, config.properties
├── config.properties.example            # template credentials (committed)
├── config.properties                     # real creds (git-ignored, user fills)
├── src/main/resources/xsd/
│   ├── common.xsd                        # official NAV XSDs
│   ├── catalog.xml                       # OASIS catalog (namespace -> local file)
│   ├── invoiceBase.xsd
│   ├── invoiceApi.xsd
│   └── invoiceData.xsd
├── src/main/java/poc/nav/
│   ├── Config.java                       # load + validate 5 creds + base URL
│   ├── CryptoUtil.java                   # sha512Upper, sha3_512Upper, aesEcbDecrypt
│   ├── SampleInvoiceFactory.java         # build minimal InvoiceData -> XML bytes
│   ├── RequestFactory.java               # build TokenExchangeRequest + ManageInvoiceRequest
│   ├── NavClient.java                    # HTTP POST + marshal/unmarshal + error detect
│   └── App.java                          # main(): orchestrate the 2-call flow
└── src/test/java/poc/nav/
    ├── CryptoUtilTest.java               # spec worked-example oracle
    └── ConfigTest.java                   # validation behaviour
```

JAXB generates classes into `target/generated-sources/jaxb/` under packages derived from the XSD target namespaces (e.g. `hu.gov.nav.schemas.osa.*`). Exact generated package names are discovered in Task 2 and referenced thereafter via a small set of imports.

---

### Task 0: Obtain the official NAV XSDs

**Files:**
- Create: `src/main/resources/xsd/common.xsd`, `invoiceBase.xsd`, `invoiceApi.xsd`, `invoiceData.xsd`

- [ ] **Step 1: Download the four v3.0 XSDs**

The XSDs are published by NAV. Try, in order:

```bash
cd /Users/I778468/src/apeh
mkdir -p src/main/resources/xsd
BASE="https://raw.githubusercontent.com/nav-gov-hu/Online-Invoice/master/src/schemas"
for f in common invoiceBase invoiceApi invoiceData; do
  curl -fsSL "$BASE/$f.xsd" -o "src/main/resources/xsd/$f.xsd" && echo "got $f" || echo "MISSING $f"
done
ls -l src/main/resources/xsd/
```

Expected: four `.xsd` files present.

- [ ] **Step 2: If any download fails, STOP and ask the user**

If any file shows `MISSING`, do not hand-fake schemas. Report to the user which files are missing and ask them to supply the v3.0 XSD bundle (downloadable from the NAV Online Számla portal → Dokumentumok, or the official GitHub). The spec design doc explicitly forbids fabricating the schemas.

- [ ] **Step 3: Verify the XSDs declare the v3.0 namespaces and expected roots**

```bash
grep -h "targetNamespace" src/main/resources/xsd/*.xsd
grep -l "ManageInvoiceRequest\|TokenExchangeRequest" src/main/resources/xsd/*.xsd
grep -l "name=\"InvoiceData\"" src/main/resources/xsd/*.xsd
```

Expected: namespaces contain `OSA/3.0`; `invoiceApi.xsd` contains the two request roots; `invoiceData.xsd` contains `InvoiceData`.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/xsd/
git commit -m "chore: add official NAV v3.0 XSD schemas"
```

---

### Task 1: Maven project skeleton + .gitignore

**Files:**
- Create: `pom.xml`, `.gitignore`, `config.properties.example`

- [ ] **Step 1: Write `.gitignore`**

```
target/
config.properties
*.class
.idea/
*.iml
```

- [ ] **Step 2: Write `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>poc.nav</groupId>
  <artifactId>nav-invoice-poc</artifactId>
  <version>1.0.0</version>
  <packaging>jar</packaging>

  <properties>
    <maven.compiler.release>17</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <jaxb.version>4.0.5</jaxb.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>jakarta.xml.bind</groupId>
      <artifactId>jakarta.xml.bind-api</artifactId>
      <version>4.0.2</version>
    </dependency>
    <dependency>
      <groupId>org.glassfish.jaxb</groupId>
      <artifactId>jaxb-runtime</artifactId>
      <version>${jaxb.version}</version>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>5.10.2</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>jaxb2-maven-plugin</artifactId>
        <version>3.1.0</version>
        <executions>
          <execution>
            <id>xjc</id>
            <goals><goal>xjc</goal></goals>
          </execution>
        </executions>
        <configuration>
          <sources>
            <source>src/main/resources/xsd</source>
          </sources>
          <outputDirectory>${project.build.directory}/generated-sources/jaxb</outputDirectory>
          <clearOutputDir>false</clearOutputDir>
        </configuration>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.2.5</version>
      </plugin>
      <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>exec-maven-plugin</artifactId>
        <version>3.2.0</version>
        <configuration>
          <mainClass>poc.nav.App</mainClass>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 3: Write `config.properties.example`**

```properties
# NAV test technical-user credentials. Copy to config.properties and fill in.
nav.baseUrl=https://api-test.onlineszamla.nav.gov.hu
nav.login=
nav.password=
nav.signingKey=
nav.exchangeKey=
nav.taxNumber=
# Software identification (18-char softwareId: 2-letter country + unique chars)
nav.softwareId=HU00000000POC00001
nav.softwareDevContact=dev@example.com
```

- [ ] **Step 4: Verify codegen runs (depends on Task 0 XSDs present)**

Run: `cd /Users/I778468/src/apeh && mvn -q generate-sources`
Expected: BUILD SUCCESS; `find target/generated-sources/jaxb -name '*.java' | head` lists generated classes.

- [ ] **Step 5: Record the generated package names**

```bash
grep -rh "^package" target/generated-sources/jaxb --include=*.java | sort -u
```

Expected: one or more `package hu.gov.nav.schemas.osa...;` lines. Note the package that contains `ManageInvoiceRequest`, `TokenExchangeRequest`, and `InvoiceData` — later tasks import from it. If the names differ from `hu.gov.nav.schemas.osa.*`, use the actual names shown here in all later imports.

- [ ] **Step 6: Commit**

```bash
git add pom.xml .gitignore config.properties.example
git commit -m "chore: Maven skeleton with JAXB codegen"
```

---

### Task 2: CryptoUtil (TDD against the spec oracle)

**Files:**
- Create: `src/main/java/poc/nav/CryptoUtil.java`
- Test: `src/test/java/poc/nav/CryptoUtilTest.java`

- [ ] **Step 1: Write the failing test using the spec's exact worked example**

```java
package poc.nav;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;

class CryptoUtilTest {

    // From NAV spec v3.0 §1.5.1 worked example.
    private static final String REQUEST_ID = "TSTKFT1222564";
    private static final String TS_MASK = "20171230182545"; // 2017-12-30T18:25:45Z stripped
    private static final String SIGNING_KEY = "ce-8f5e-215119fa7dd621DLMRHRLH2S";

    @Test
    void sha3_512Upper_matchesIndexHashExample() {
        // hash base = invoiceOperation + base64 content
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
        assertEquals(
          "60BC80609EE3B8F42FE904200A49A1921A1DADA08D55319ACD40C59F626514B74EEA"
          + "49011D3726000A10DBCF8199D590DA9C2841D987308F2D83DAE17C2470C42", sig);
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
        byte[] plain = "0123456789ABCDEF".getBytes(StandardCharsets.UTF_8); // 16-byte block
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding");
        c.init(javax.crypto.Cipher.ENCRYPT_MODE,
            new javax.crypto.spec.SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"));
        byte[] enc = c.doFinal(plain);
        byte[] dec = CryptoUtil.aesEcbDecrypt(enc, key);
        assertArrayEquals(plain, dec);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/I778468/src/apeh && mvn -q test -Dtest=CryptoUtilTest`
Expected: FAIL/compile error — `CryptoUtil` does not exist.

- [ ] **Step 3: Implement `CryptoUtil`**

```java
package poc.nav;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** Pure cryptographic helpers for NAV request signing and token decryption. */
public final class CryptoUtil {

    private CryptoUtil() {}

    public static String sha512Upper(String input) {
        return hashUpper("SHA-512", input);
    }

    public static String sha3_512Upper(String input) {
        return hashUpper("SHA3-512", input);
    }

    private static String hashUpper(String algo, String input) {
        try {
            MessageDigest md = MessageDigest.getInstance(algo);
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02X", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Hash failed: " + algo, e);
        }
    }

    /** AES-128 ECB decrypt (no padding) using the technical user's exchange key. */
    public static byte[] aesEcbDecrypt(byte[] cipherText, String keyLiteral) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            SecretKeySpec key =
                new SecretKeySpec(keyLiteral.getBytes(StandardCharsets.UTF_8), "AES");
            cipher.init(Cipher.DECRYPT_MODE, key);
            return cipher.doFinal(cipherText);
        } catch (Exception e) {
            throw new IllegalStateException("AES decrypt failed", e);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/I778468/src/apeh && mvn -q test -Dtest=CryptoUtilTest`
Expected: PASS (4 tests). If the signature test fails, re-check that the timestamp mask strips separators/timezone and that hex is uppercase — the oracle values are authoritative.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/poc/nav/CryptoUtil.java src/test/java/poc/nav/CryptoUtilTest.java
git commit -m "feat: CryptoUtil with SHA-512/SHA3-512/AES-128-ECB, verified vs spec example"
```

---

### Task 3: Config loader + validation

**Files:**
- Create: `src/main/java/poc/nav/Config.java`
- Test: `src/test/java/poc/nav/ConfigTest.java`

- [ ] **Step 1: Write the failing test**

```java
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
        p.setProperty("nav.taxNumber", "123"); // not 8 digits
        IllegalArgumentException ex =
            assertThrows(IllegalArgumentException.class, () -> Config.from(p));
        assertTrue(ex.getMessage().contains("taxNumber"));
    }

    @Test
    void rejectsBadSoftwareId() {
        Properties p = valid();
        p.setProperty("nav.softwareId", "too-short"); // not 18 chars [0-9A-Z-]
        assertThrows(IllegalArgumentException.class, () -> Config.from(p));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/I778468/src/apeh && mvn -q test -Dtest=ConfigTest`
Expected: FAIL/compile error — `Config` does not exist.

- [ ] **Step 3: Implement `Config`**

```java
package poc.nav;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.regex.Pattern;

/** Loads and validates NAV credentials and software identification. */
public final class Config {

    private static final Pattern LOGIN = Pattern.compile("[a-zA-Z0-9]{6,15}");
    private static final Pattern TAX = Pattern.compile("[0-9]{8}");
    private static final Pattern SOFTWARE_ID = Pattern.compile("[0-9A-Z\\-]{18}");

    private final String baseUrl, login, password, signingKey, exchangeKey,
            taxNumber, softwareId, softwareDevContact;

    private Config(String baseUrl, String login, String password, String signingKey,
                   String exchangeKey, String taxNumber, String softwareId,
                   String softwareDevContact) {
        this.baseUrl = baseUrl; this.login = login; this.password = password;
        this.signingKey = signingKey; this.exchangeKey = exchangeKey;
        this.taxNumber = taxNumber; this.softwareId = softwareId;
        this.softwareDevContact = softwareDevContact;
    }

    public static Config fromFile(String path) {
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(path)) {
            p.load(in);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read config file: " + path, e);
        }
        return from(p);
    }

    public static Config from(Properties p) {
        String baseUrl = required(p, "nav.baseUrl");
        String login = required(p, "nav.login");
        String password = required(p, "nav.password");
        String signingKey = required(p, "nav.signingKey");
        String exchangeKey = required(p, "nav.exchangeKey");
        String taxNumber = required(p, "nav.taxNumber");
        String softwareId = required(p, "nav.softwareId");
        String devContact = required(p, "nav.softwareDevContact");

        check(LOGIN.matcher(login).matches(), "login must be 6-15 alphanumeric chars");
        check(TAX.matcher(taxNumber).matches(), "taxNumber must be exactly 8 digits");
        check(exchangeKey.length() == 16, "exchangeKey must be 16 chars (AES-128)");
        check(SOFTWARE_ID.matcher(softwareId).matches(),
              "softwareId must be 18 chars of [0-9A-Z-]");

        return new Config(baseUrl, login, password, signingKey, exchangeKey,
                          taxNumber, softwareId, devContact);
    }

    private static String required(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Missing required config: " + key);
        }
        return v.trim();
    }

    private static void check(boolean ok, String msg) {
        if (!ok) throw new IllegalArgumentException("Invalid config: " + msg);
    }

    public String baseUrl() { return baseUrl; }
    public String login() { return login; }
    public String password() { return password; }
    public String signingKey() { return signingKey; }
    public String exchangeKey() { return exchangeKey; }
    public String taxNumber() { return taxNumber; }
    public String softwareId() { return softwareId; }
    public String softwareDevContact() { return softwareDevContact; }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/I778468/src/apeh && mvn -q test -Dtest=ConfigTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/poc/nav/Config.java src/test/java/poc/nav/ConfigTest.java
git commit -m "feat: Config loader with credential validation"
```

---

### Task 4: SampleInvoiceFactory (four invoice variants)

**Files:**
- Create: `src/main/java/poc/nav/SampleInvoiceFactory.java`

> Uses JAXB classes generated in Task 1. Confirm the exact generated type/field names first. Generated names are AUTHORITATIVE over any assumed name in this task.

This factory produces the **four invoice types the user actually uses**, each as a
separate marshalled `InvoiceData` XML:

1. `createDomesticGoods(cfg, invoiceNumber)` — **belföldi termékértékesítés**: one line,
   `vatPercentage = 0.27`, `InvoiceCategory=NORMAL`, currency HUF.
2. `createIntraCommunityAcquisition(cfg, invoiceNumber)` — **közösségen belüli
   termékbeszerzés**: one line whose `lineVatRate` is marked **`vatOutOfScope`** (a
   `DetailedReasonType` with a `case` code + `reason`, e.g. case `"K"` /
   "Közösségen belüli beszerzés") because the acquirer self-assesses — NOT a
   `vatPercentage`. VAT amounts are 0 in the invoice currency; net = gross.
3. `createModify(cfg, invoiceNumber, originalInvoiceNumber, modificationIndex)` —
   **módosító számla**: same shape as the domestic invoice PLUS an `invoiceReference`
   block (`originalInvoiceNumber`, `modifyWithoutMaster=false`, `modificationIndex`).
4. `createStorno(cfg, invoiceNumber, originalInvoiceNumber, modificationIndex)` —
   **érvénytelenítő számla**: an `invoiceReference` to the original and the invoice
   restated with negated amounts (storno reverses the original). `modifyWithoutMaster=false`.

The `invoiceOperation` sent to NAV (CREATE / MODIFY / STORNO) is chosen by `App`
(Task 7), NOT encoded here — this factory only builds the invoice BODY. MODIFY and
STORNO bodies differ from CREATE only by carrying the `invoiceReference` block.

- [ ] **Step 1: Inspect the generated InvoiceData API**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"; export PATH="$JAVA_HOME/bin:/opt/homebrew/opt/maven/bin:$PATH"
cd /Users/I778468/src/apeh && mvn -q generate-sources
GEN=target/generated-sources/jaxb
grep -rl "class InvoiceData" $GEN
grep -rl "class InvoiceReferenceType\|class VatRateType\|class DetailedReasonType\|class LineType\|class SupplierInfo\|class CustomerInfo" $GEN
# Read the key types' getters/setters and the generated ObjectFactory
grep -rh "public .* get[A-Z]" $(grep -rl "class InvoiceData" $GEN) | head -40
grep -rh "public .* get[A-Z]\|public void set[A-Z]" $(grep -rl "class VatRateType" $GEN)
grep -rh "public .* get[A-Z]\|public void set[A-Z]" $(grep -rl "class InvoiceReferenceType" $GEN)
```

Expected: you can see getters/setters and the generated `ObjectFactory`. Note the fully qualified package (from Task 1), the marshal root for `InvoiceData`, the shape of `VatRateType` (the choice between `vatPercentage` / `vatOutOfScope` / `vatExemption` / `vatDomesticReverseCharge` …), the `DetailedReasonType` fields (`caseValue`/`getCase` + `reason`), and `InvoiceReferenceType` (`originalInvoiceNumber`, `modifyWithoutMaster`, `modificationIndex`). JAXB may rename `case` (a Java keyword) to `caseValue` or similar — use whatever was generated.

- [ ] **Step 2: Implement `SampleInvoiceFactory`**

Build the minimum invoiceData each variant requires using the generated setters from
Step 1. The reference code below shows the **domestic** variant's shape; the subagent
builds all four public methods, factoring shared head/line/summary construction into
private helpers (DRY). Adjust every type/method name to match Step 1 output where it
differs — generated names win.

Key per-variant differences to implement:
- **Domestic:** `lineVatRate.setVatPercentage(0.27)`; vat amounts = net * 0.27.
- **Intra-Community acquisition:** `lineVatRate.setVatOutOfScope(detailedReason)` where
  `detailedReason` has a case code + reason text; line & summary VAT amounts = 0;
  summary must use the `vatOutOfScope` branch consistently (the `summaryByVatRate` entry's
  `vatRate` mirrors the line's `vatOutOfScope`).
- **Modify / Storno:** set `invoice.setInvoiceReference(ref)` with the generated
  `InvoiceReferenceType`; everything else as domestic (storno negates amounts).

```java
package poc.nav;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;

// Imports from the package recorded in Task 1 Step 5, e.g.:
import hu.gov.nav.schemas.osa._3_0.data.*;

/** Builds a minimal, schema-valid domestic single-line invoice and marshals it to XML bytes. */
public final class SampleInvoiceFactory {

    private SampleInvoiceFactory() {}

    public static byte[] buildInvoiceXml(Config cfg, String invoiceNumber) {
        try {
            ObjectFactory of = new ObjectFactory();

            InvoiceData data = of.createInvoiceData();
            data.setInvoiceNumber(invoiceNumber);
            data.setInvoiceIssueDate(today());
            data.setCompletenessIndicator(false);

            InvoiceMainType main = of.createInvoiceMainType();
            InvoiceType invoice = of.createInvoiceType();

            // --- invoiceHead ---
            InvoiceHeadType head = of.createInvoiceHeadType();

            SupplierInfoType supplier = of.createSupplierInfoType();
            TaxNumberType supTax = of.createTaxNumberType();
            supTax.setTaxpayerId(cfg.taxNumber());
            supplier.setSupplierTaxNumber(supTax);
            supplier.setSupplierName("PoC Supplier Kft.");
            AddressType supAddr = buildAddress(of);
            supplier.setSupplierAddress(supAddr);
            head.setSupplierInfo(supplier);

            CustomerInfoType customer = of.createCustomerInfoType();
            customer.setCustomerVatStatus(CustomerVatStatusType.DOMESTIC);
            CustomerTaxNumberType custTax = of.createCustomerTaxNumberType();
            custTax.setTaxpayerId("11111111");
            customer.setCustomerTaxNumber(custTax);
            customer.setCustomerName("PoC Customer Kft.");
            customer.setCustomerAddress(buildAddress(of));
            head.setCustomerInfo(customer);

            InvoiceDetailType detail = of.createInvoiceDetailType();
            detail.setInvoiceCategory(InvoiceCategoryType.NORMAL);
            detail.setInvoiceDeliveryDate(today());
            detail.setCurrencyCode("HUF");
            detail.setExchangeRate(BigDecimal.ONE);
            detail.setPaymentMethod(PaymentMethodType.TRANSFER);
            detail.setInvoiceAppearance(InvoiceAppearanceType.ELECTRONIC);
            head.setInvoiceDetail(detail);

            invoice.setInvoiceHead(head);

            // --- one line ---
            LinesType lines = of.createLinesType();
            lines.setMergedItemIndicator(false);
            LineType line = of.createLineType();
            line.setLineNumber(BigDecimal.ONE.toBigInteger());
            line.setLineExpressionIndicator(true);
            line.setLineDescription("PoC item");
            line.setQuantity(BigDecimal.ONE);
            line.setUnitPrice(new BigDecimal("1000"));
            LineAmountsNormalType amt = of.createLineAmountsNormalType();
            LineNetAmountDataType net = of.createLineNetAmountDataType();
            net.setLineNetAmount(new BigDecimal("1000"));
            net.setLineNetAmountHUF(new BigDecimal("1000"));
            amt.setLineNetAmountData(net);
            VatRateType vat = of.createVatRateType();
            vat.setVatPercentage(new BigDecimal("0.27"));
            amt.setLineVatRate(vat);
            LineVatDataType vatData = of.createLineVatDataType();
            vatData.setLineVatAmount(new BigDecimal("270"));
            vatData.setLineVatAmountHUF(new BigDecimal("270"));
            amt.setLineVatData(vatData);
            LineGrossAmountDataType gross = of.createLineGrossAmountDataType();
            gross.setLineGrossAmountNormal(new BigDecimal("1270"));
            gross.setLineGrossAmountNormalHUF(new BigDecimal("1270"));
            amt.setLineGrossAmountData(gross);
            line.setLineAmountsNormal(amt);
            lines.getLine().add(line);
            invoice.setInvoiceLines(lines);

            // --- summary ---
            SummaryType summary = of.createSummaryType();
            SummaryNormalType sn = of.createSummaryNormalType();
            SummaryByVatRateType byRate = of.createSummaryByVatRateType();
            VatRateType sRate = of.createVatRateType();
            sRate.setVatPercentage(new BigDecimal("0.27"));
            byRate.setVatRate(sRate);
            VatRateNetDataType snet = of.createVatRateNetDataType();
            snet.setVatRateNetAmount(new BigDecimal("1000"));
            snet.setVatRateNetAmountHUF(new BigDecimal("1000"));
            byRate.setVatRateNetData(snet);
            VatRateVatDataType svat = of.createVatRateVatDataType();
            svat.setVatRateVatAmount(new BigDecimal("270"));
            svat.setVatRateVatAmountHUF(new BigDecimal("270"));
            byRate.setVatRateVatData(svat);
            sn.getSummaryByVatRate().add(byRate);
            sn.setInvoiceNetAmount(new BigDecimal("1000"));
            sn.setInvoiceNetAmountHUF(new BigDecimal("1000"));
            sn.setInvoiceVatAmount(new BigDecimal("270"));
            sn.setInvoiceVatAmountHUF(new BigDecimal("270"));
            summary.setSummaryNormal(sn);
            SummaryGrossDataType sg = of.createSummaryGrossDataType();
            sg.setInvoiceGrossAmount(new BigDecimal("1270"));
            sg.setInvoiceGrossAmountHUF(new BigDecimal("1270"));
            summary.setSummaryGrossData(sg);
            invoice.setInvoiceSummary(summary);

            main.setInvoice(invoice);
            data.setInvoiceMain(main);

            return marshal(of.createInvoiceData(data));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build sample invoice", e);
        }
    }

    private static AddressType buildAddress(ObjectFactory of) {
        AddressType addr = of.createAddressType();
        SimpleAddressType simple = of.createSimpleAddressType();
        simple.setCountryCode("HU");
        simple.setPostalCode("1011");
        simple.setCity("Budapest");
        simple.setAdditionalAddressDetail("Fo utca 1.");
        addr.setSimpleAddress(simple);
        return addr;
    }

    private static XMLGregorianCalendar today() throws Exception {
        LocalDate d = LocalDate.now();
        return DatatypeFactory.newInstance()
            .newXMLGregorianCalendarDate(d.getYear(), d.getMonthValue(),
                d.getDayOfMonth(), javax.xml.datatype.DatatypeConstants.FIELD_UNDEFINED);
    }

    private static byte[] marshal(Object jaxbElement) throws Exception {
        JAXBContext ctx = JAXBContext.newInstance(InvoiceData.class);
        Marshaller m = ctx.createMarshaller();
        m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, false);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        m.marshal(jaxbElement, out);
        return out.toByteArray();
    }
}
```

- [ ] **Step 3: Write an XSD-validation test for all four variants (TDD — write this test, watch it fail, then make Step 2 satisfy it)**

Create `src/test/java/poc/nav/SampleInvoiceFactoryTest.java`. The test marshals each
variant and validates the bytes against the real `invoiceData.xsd` (resolving its
`common`/`base` imports through the OASIS catalog), plus asserts the distinguishing
field of each variant. This catches schema/structure mistakes offline — no NAV call.

```java
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
    void intraCommunity_isSchemaValid_andOutOfScope() throws Exception {
        byte[] xml = SampleInvoiceFactory.createIntraCommunityAcquisition(cfg(), "POC-I-1");
        assertValid(xml);
        assertTrue(new String(xml, StandardCharsets.UTF_8).contains("vatOutOfScope"));
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
```

You must also create the small helper `src/test/java/poc/nav/LocalXsdResolver.java` — an
`LSResourceResolver` that maps the NAV namespaces to the local xsd files so JAXP resolves
the namespace-only imports during validation (same problem the xjc catalog solved):

```java
package poc.nav;

import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/** Resolves NAV namespace-only schema imports to local xsd files for JAXP validation. */
public final class LocalXsdResolver implements LSResourceResolver {
    private final File xsdDir;
    public LocalXsdResolver(File xsdDir) { this.xsdDir = xsdDir; }

    @Override
    public LSInput resolveResource(String type, String namespaceURI,
                                   String publicId, String systemId, String baseURI) {
        String file = switch (namespaceURI == null ? "" : namespaceURI) {
            case "http://schemas.nav.gov.hu/NTCA/1.0/common" -> "common.xsd";
            case "http://schemas.nav.gov.hu/OSA/3.0/base"    -> "invoiceBase.xsd";
            case "http://schemas.nav.gov.hu/OSA/3.0/data"    -> "invoiceData.xsd";
            case "http://schemas.nav.gov.hu/OSA/3.0/api"     -> "invoiceApi.xsd";
            default -> null;
        };
        if (file == null) return null;
        return new DomLSInput(new File(xsdDir, file));
    }

    /** Minimal LSInput backed by a file stream. */
    private static final class DomLSInput implements LSInput {
        private final File f;
        DomLSInput(File f) { this.f = f; }
        @Override public InputStream getByteStream() {
            try { return new FileInputStream(f); } catch (Exception e) { throw new RuntimeException(e); }
        }
        @Override public String getSystemId() { return f.toURI().toString(); }
        // Unused LSInput members:
        @Override public java.io.Reader getCharacterStream() { return null; }
        @Override public void setCharacterStream(java.io.Reader r) {}
        @Override public void setByteStream(InputStream b) {}
        @Override public String getStringData() { return null; }
        @Override public void setStringData(String s) {}
        @Override public void setSystemId(String s) {}
        @Override public String getPublicId() { return null; }
        @Override public void setPublicId(String s) {}
        @Override public String getBaseURI() { return null; }
        @Override public void setBaseURI(String s) {}
        @Override public String getEncoding() { return null; }
        @Override public void setEncoding(String e) {}
        @Override public boolean getCertifiedText() { return false; }
        @Override public void setCertifiedText(boolean c) {}
    }
}
```

Run (write test, confirm it FAILS first because methods/validity aren't right yet, then
implement Step 2 until green):
`export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"; export PATH="$JAVA_HOME/bin:/opt/homebrew/opt/maven/bin:$PATH"; cd /Users/I778468/src/apeh && mvn -q test -Dtest=SampleInvoiceFactoryTest`
Expected final: `Tests run: 4, Failures: 0, Errors: 0`. If validation reports a schema
error, fix `SampleInvoiceFactory` (generated names/fields are authoritative) until every
variant is schema-valid. Do NOT weaken the test to pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/poc/nav/SampleInvoiceFactory.java src/test/java/poc/nav/SampleInvoiceFactoryTest.java src/test/java/poc/nav/LocalXsdResolver.java
git commit -m "feat: SampleInvoiceFactory builds four invoice variants, each XSD-validated by tests"
```

---

### Task 5: RequestFactory (headers, user, software, signature)

**Files:**
- Create: `src/main/java/poc/nav/RequestFactory.java`

- [ ] **Step 1: Inspect generated request/header/user/crypto types**

```bash
cd /Users/I778468/src/apeh && mvn -q generate-sources
GEN=target/generated-sources/jaxb
grep -rl "class TokenExchangeRequest\|class ManageInvoiceRequest" $GEN
grep -rl "class BasicHeaderType\|class UserHeaderType\|class CryptoType\|class SoftwareType" $GEN
grep -rh "public .* get[A-Z]" $(grep -rl "class CryptoType" $GEN) | head
```

Expected: identify setters on `BasicHeaderType` (requestId, timestamp, requestVersion, headerVersion), `UserHeaderType` (login, passwordHash, taxNumber), `CryptoType` (value + cryptoType attribute), `SoftwareType`, and the two request roots with `invoiceOperation`/`invoiceData`/`exchangeToken`/`compressedContent`/`index`.

- [ ] **Step 2: Implement `RequestFactory`**

Adjust type/method names to match Step 1 if they differ.

```java
package poc.nav;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.util.GregorianCalendar;

import hu.gov.nav.schemas.osa._3_0.api.*;      // request/response roots + SoftwareType
import hu.gov.nav.schemas.ntca._1_0.common.*;  // BasicHeaderType, UserHeaderType, CryptoType

/** Assembles signed TokenExchangeRequest and ManageInvoiceRequest objects. */
public final class RequestFactory {

    private static final DateTimeFormatter XML_TS =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    private static final DateTimeFormatter SIG_TS =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String REQUEST_VERSION = "3.0";
    private static final String HEADER_VERSION = "1.0";

    private final Config cfg;
    private final ObjectFactory api = new ObjectFactory();

    public RequestFactory(Config cfg) { this.cfg = cfg; }

    public TokenExchangeRequest buildTokenExchange() {
        String requestId = newRequestId();
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);

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

    private static XMLGregorianCalendar toXmlCal(ZonedDateTime t) {
        try {
            GregorianCalendar gc = GregorianCalendar.from(t);
            XMLGregorianCalendar x = DatatypeFactory.newInstance().newXMLGregorianCalendar(gc);
            return x;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 3: Write a test for RequestFactory (TDD — write, fail, implement to green)**

Create `src/test/java/poc/nav/RequestFactoryTest.java`. Because `requestId`/`timestamp`
vary per call, the test does not hardcode a signature; instead it marshals the built
request to XML and asserts the structural + signing invariants, and independently
recomputes the manageInvoice signature from the request's own requestId/timestamp to
prove the signature is correct for THIS request.

```java
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
        String requestId = between(xml, "<requestId>", "</requestId>");
        String timestamp = between(xml, "<timestamp>", "</timestamp>");
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
```

- [ ] **Step 4: Implement/adjust `RequestFactory` until the test is green**

Run: `export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"; export PATH="$JAVA_HOME/bin:/opt/homebrew/opt/maven/bin:$PATH"; cd /Users/I778468/src/apeh && mvn -q test -Dtest=RequestFactoryTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0`. Fix generated-name mismatches against Step 1. Watch the `timestamp` format: NAV requires `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` (UTC, millis). If the generated `XMLGregorianCalendar` omits millis or emits an offset other than `Z`, format/normalize it so both the XML tag and the `tsMask` used for signing are UTC — the test parses the tag and recomputes, so a mismatch fails loudly.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/poc/nav/RequestFactory.java src/test/java/poc/nav/RequestFactoryTest.java
git commit -m "feat: RequestFactory assembles signed token/invoice requests (signature verified by test)"
```

---

### Task 6: NavClient (HTTP + marshal/unmarshal + error detection)

**Files:**
- Create: `src/main/java/poc/nav/NavClient.java`

- [ ] **Step 1: Inspect response + error generated types**

```bash
cd /Users/I778468/src/apeh && mvn -q generate-sources
GEN=target/generated-sources/jaxb
grep -rl "class TokenExchangeResponse\|class ManageInvoiceResponse" $GEN
grep -rl "class GeneralErrorResponse\|class GeneralExceptionResponse\|class BasicResultType" $GEN
grep -rh "public .* get[A-Z]" $(grep -rl "class TokenExchangeResponse" $GEN)
```

Expected: identify `TokenExchangeResponse` (encodedExchangeToken as `byte[]`, validity fields), `ManageInvoiceResponse` (transactionId), and the error/result shapes (funcCode, errorCode, message).

- [ ] **Step 2: Implement `NavClient`**

```java
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
import java.time.Duration;

import hu.gov.nav.schemas.osa._3_0.api.*;      // request/response roots + SoftwareType

/** Posts XML to the NAV REST API and unmarshals the response. */
public final class NavClient {

    private final Config cfg;
    private final HttpClient http;
    private final JAXBContext ctx;

    public NavClient(Config cfg) {
        this.cfg = cfg;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();
        try {
            // context package from Task 1 Step 5
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

            Object parsed = unmarshal(resp.body());
            if (responseType.isInstance(parsed)) {
                return responseType.cast(parsed);
            }
            // Error path: GeneralErrorResponse / GeneralExceptionResponse
            throw new NavApiException(op, resp.statusCode(), describe(parsed, resp.body()));
        } catch (NavApiException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("HTTP call to " + op + " failed", e);
        }
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

    private Object unmarshal(byte[] xml) throws Exception {
        Unmarshaller u = ctx.createUnmarshaller();
        Object o = u.unmarshal(new ByteArrayInputStream(xml));
        return (o instanceof JAXBElement<?> je) ? je.getValue() : o;
    }

    private String describe(Object parsed, byte[] raw) {
        if (parsed == null) return new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        return parsed.getClass().getSimpleName() + ": "
            + new String(raw, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Raised when NAV returns an error/exception response instead of the business result. */
    public static final class NavApiException extends RuntimeException {
        public NavApiException(String op, int status, String detail) {
            super("NAV " + op + " error (HTTP " + status + "): " + detail);
        }
    }
}
```

- [ ] **Step 3: Make the response-parsing seam testable, then write its test (TDD)**

Pure HTTP is awkward to unit-test, so expose the parse/route logic as a package-visible
method `Object parseResponse(byte[] xml)` (used by `post`) that unmarshals and returns the
business object, OR throws `NavApiException` when the body is a NAV error/exception
response. Then test it with canned XML — no network. Create
`src/test/java/poc/nav/NavClientTest.java`:

```java
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

    // A well-formed ManageInvoiceResponse (namespaces/field names must match the
    // generated schema; adjust the sample during Step 1 inspection if needed).
    private static final String OK_RESPONSE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <ManageInvoiceResponse xmlns="http://schemas.nav.gov.hu/OSA/3.0/api"
                               xmlns:common="http://schemas.nav.gov.hu/NTCA/1.0/common">
          <common:header>
            <common:requestId>RID1</common:requestId>
            <common:timestamp>2026-10-03T10:00:00.000Z</common:timestamp>
            <common:requestVersion>3.0</common:requestVersion>
          </common:header>
          <common:result><common:funcCode>OK</common:funcCode></common:result>
          <transactionId>ABC123</transactionId>
        </ManageInvoiceResponse>
        """;

    private static final String ERROR_RESPONSE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <GeneralErrorResponse xmlns="http://schemas.nav.gov.hu/OSA/3.0/api"
                              xmlns:common="http://schemas.nav.gov.hu/NTCA/1.0/common">
          <common:result>
            <common:funcCode>ERROR</common:funcCode>
            <common:errorCode>INVALID_SECURITY_USER</common:errorCode>
            <common:message>Invalid user</common:message>
          </common:result>
        </GeneralErrorResponse>
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
```

The exact sample XML (root/namespace/element names) must match the generated classes —
during Step 1 you inspected `TokenExchangeResponse`/`ManageInvoiceResponse` and the error
root; adjust the two sample strings so the OK one unmarshals to `ManageInvoiceResponse`
and the error one is recognized as an error. If NAV's error type is actually
`GeneralExceptionResponse` or the business responses themselves carry
`result/funcCode=ERROR`, make `parseResponse` inspect `funcCode` and throw accordingly,
and point the ERROR sample at the real error shape.

- [ ] **Step 4: Implement/adjust `NavClient` until the test is green**

Run: `export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"; export PATH="$JAVA_HOME/bin:/opt/homebrew/opt/maven/bin:$PATH"; cd /Users/I778468/src/apeh && mvn -q test -Dtest=NavClientTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/poc/nav/NavClient.java src/test/java/poc/nav/NavClientTest.java
git commit -m "feat: NavClient posts XML and routes NAV error responses (parse logic tested)"
```

---

### Task 7: App orchestration

**Files:**
- Create: `src/main/java/poc/nav/App.java`

- [ ] **Step 1: Implement `App`**

Orchestrates one `tokenExchange`, then FOUR `manageInvoice` submissions covering the
invoice types the user uses: domestic-goods CREATE, intra-Community-acquisition CREATE,
MODIFY (references the domestic invoice created above), STORNO (references the
intra-Community invoice created above). Each prints its own transactionId. A single
exchange token is reused for all four (valid within its window); if NAV rejects reuse,
request a fresh token per call — but reuse is expected to work.

```java
package poc.nav;

import java.nio.charset.StandardCharsets;
import java.math.BigInteger;

import hu.gov.nav.schemas.osa._3_0.api.ManageInvoiceResponse;
import hu.gov.nav.schemas.osa._3_0.api.TokenExchangeResponse;
import hu.gov.nav.schemas.osa._3_0.api.TokenExchangeRequest;
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

            System.out.println("Step 1: Requesting exchange token...");
            TokenExchangeRequest teReq = factory.buildTokenExchange();
            TokenExchangeResponse teResp = client.tokenExchange(teReq);
            byte[] decrypted = CryptoUtil.aesEcbDecrypt(
                teResp.getEncodedExchangeToken(), cfg.exchangeKey());
            String token = new String(decrypted, StandardCharsets.UTF_8).trim();
            System.out.println("  token valid to: " + teResp.getTokenValidityTo());

            long stamp = System.currentTimeMillis();
            String domesticNo = "POC-D-" + stamp;
            String intraNo    = "POC-I-" + stamp;
            String modifyNo   = "POC-M-" + stamp;
            String stornoNo   = "POC-S-" + stamp;

            // 1) CREATE domestic goods
            System.out.println("Step 2: CREATE domestic goods invoice " + domesticNo);
            byte[] domesticXml = SampleInvoiceFactory.createDomesticGoods(cfg, domesticNo);
            printTx("CREATE domestic", client.manageInvoice(
                factory.buildManageInvoice(token, "CREATE", domesticXml)));

            // 2) CREATE intra-Community acquisition
            System.out.println("Step 3: CREATE intra-Community acquisition invoice " + intraNo);
            byte[] intraXml = SampleInvoiceFactory.createIntraCommunityAcquisition(cfg, intraNo);
            printTx("CREATE intra-Community", client.manageInvoice(
                factory.buildManageInvoice(token, "CREATE", intraXml)));

            // 3) MODIFY referencing the domestic invoice (modificationIndex = 1)
            System.out.println("Step 4: MODIFY invoice " + modifyNo
                + " (references " + domesticNo + ")");
            byte[] modifyXml = SampleInvoiceFactory.createModify(
                cfg, modifyNo, domesticNo, BigInteger.ONE);
            printTx("MODIFY", client.manageInvoice(
                factory.buildManageInvoice(token, "MODIFY", modifyXml)));

            // 4) STORNO referencing the intra-Community invoice (modificationIndex = 1)
            System.out.println("Step 5: STORNO invoice " + stornoNo
                + " (references " + intraNo + ")");
            byte[] stornoXml = SampleInvoiceFactory.createStorno(
                cfg, stornoNo, intraNo, BigInteger.ONE);
            printTx("STORNO", client.manageInvoice(
                factory.buildManageInvoice(token, "STORNO", stornoXml)));

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

    private static void printTx(String label, ManageInvoiceResponse resp) {
        System.out.println("  [" + label + "] transactionId = " + resp.getTransactionId());
    }
}
```

- [ ] **Step 2: Compile and run full test suite**

Run: `export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"; export PATH="$JAVA_HOME/bin:/opt/homebrew/opt/maven/bin:$PATH"; cd /Users/I778468/src/apeh && mvn -q test`
Expected: BUILD SUCCESS, all unit tests pass.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/poc/nav/App.java
git commit -m "feat: App submits four invoice variants (domestic, intra-Community, modify, storno)"
```

---

### Task 8: Live run against the NAV test endpoint

**Files:**
- Create: `config.properties` (git-ignored; from the example)

- [ ] **Step 1: Create `config.properties` from the template with the user's test creds**

```properties
nav.baseUrl=https://api-test.onlineszamla.nav.gov.hu
nav.login=testlogin123456
nav.password=TestPass123
nav.signingKey=aa-0000-00000000000000AAAAAAAAAA
nav.exchangeKey=TESTEXCHANGEKEY0
nav.taxNumber=84072436
nav.softwareId=HU00000000POC00001
nav.softwareDevContact=dev@example.com
```

Confirm it is git-ignored: `git check-ignore config.properties` → prints the path.

- [ ] **Step 2: Run the PoC**

Run: `cd /Users/I778468/src/apeh && mvn -q compile exec:java`
Expected (happy path): three progress lines then `SUCCESS. transactionId = <id>`.

- [ ] **Step 3: Interpret the result**

- `SUCCESS` + transactionId → the full round-trip works. Done.
- `NAV rejected the request` with an `errorCode` → read it against spec §3.2. Likely cases and fixes:
  - `INVALID_SECURITY_USER` / signature errors → re-verify `signingKey`, timestamp UTC masking, and uppercase hashing (Task 2 oracle must still pass).
  - schema validation errors on `invoiceData` → adjust `SampleInvoiceFactory` fields to satisfy the exact v3.0 invoiceData.xsd (names from Task 4 Step 1).
  - token decrypt producing garbage → confirm `exchangeKey` is exactly 16 chars and AES/ECB/NoPadding.
- Fix, re-run. Do not claim success without the printed transactionId.

- [ ] **Step 4: Final commit (no secrets)**

```bash
git status   # verify config.properties is NOT staged
git add -A
git commit -m "docs: PoC complete" || echo "nothing else to commit"
```

---

### Task 9: Package as a shareable fat JAR + README

**Goal:** let a friend run the PoC with only a JRE 17 and their own NAV test
credentials — no Maven, no source.

**Files:**
- Modify: `pom.xml` (add Maven Shade plugin)
- Create: `README.md`

- [ ] **Step 1: Add the Maven Shade plugin to `pom.xml`**

Inside `<build><plugins>`, add (version 3.5.1), binding to the `package` phase, producing
an executable jar with `Main-Class: poc.nav.App`:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-shade-plugin</artifactId>
  <version>3.5.1</version>
  <executions>
    <execution>
      <phase>package</phase>
      <goals><goal>shade</goal></goals>
      <configuration>
        <transformers>
          <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
            <mainClass>poc.nav.App</mainClass>
          </transformer>
          <!-- JAXB uses ServiceLoader; merge service files so the shaded jar finds the runtime -->
          <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
        </transformers>
      </configuration>
    </execution>
  </executions>
</plugin>
```

- [ ] **Step 2: Build the jar and verify it is runnable**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"; export PATH="$JAVA_HOME/bin:/opt/homebrew/opt/maven/bin:$PATH"
cd /Users/I778468/src/apeh && mvn -q -DskipTests package
ls -lh target/nav-invoice-poc-1.0.0.jar
# Run with no config to confirm it starts and gives the clear config error (proves the jar is wired, needs no Maven):
java -jar target/nav-invoice-poc-1.0.0.jar /tmp/does-not-exist.properties; echo "exit=$?"
```
Expected: the jar exists (several MB, deps inside); running it prints a clear
"Configuration problem: Cannot read config file ..." and exits non-zero. This proves the
jar runs standalone without Maven and fails cleanly without config (it does NOT prove a
live NAV call — that needs real creds).

- [ ] **Step 3: Write `README.md`**

Cover, in plain language: what the tool does (submits 4 sample invoices to the NAV test
system); prerequisites (JRE 17 only — link to Temurin); that the friend needs THEIR OWN
NAV **test** technical-user credentials and how to get them (register a primary user at
onlineszamla-test.nav.gov.hu → create a technical user → generate signing + exchange keys
→ grant invoice-submission permission → use the first 8 digits of the test tax number);
how to configure (`cp config.properties.example config.properties`, fill the 5 values +
an 18-char softwareId); how to run (`java -jar nav-invoice-poc-1.0.0.jar config.properties`);
what success looks like (four `transactionId`s printed); and a security note (test
endpoint only, credentials stay local, never commit `config.properties`).

- [ ] **Step 4: Ensure the jar is NOT committed; commit pom + README**

```bash
cd /Users/I778468/src/apeh
grep -q "target/" .gitignore && echo "target/ already ignored"   # it is (Task 1)
git add pom.xml README.md
git commit -m "chore: package as runnable fat JAR (shade) + add README for sharing"
```
Hand-off to the friend = the built `target/nav-invoice-poc-1.0.0.jar` (sent directly /
via a release artifact, NOT via git) + `config.properties.example` + `README.md`.

---

## Self-Review Notes

- **Spec coverage:** tokenExchange (Task 5/6/7), manageInvoice (Task 5/6/7), passwordHash SHA-512 (Task 2/5), requestSignature SHA3-512 for both operation classes (Task 2/5), AES-128-ECB token decrypt (Task 2/7), base64 invoiceData (Task 5), software/header/user blocks (Task 5), four invoice variants — domestic / intra-Community / modify / storno (Task 4/7), config/validation (Task 3), error handling (Task 6/7), live submission (Task 8). All design-doc sections map to a task.
- **Test coverage (tests are part of the dev process — every component has an offline automated test, written test-first):** CryptoUtil vs spec oracle (Task 2); Config validation (Task 3); SampleInvoiceFactory — all four variants validated against the real invoiceData.xsd + distinguishing-field asserts (Task 4); RequestFactory — passwordHash/signature invariants, signature independently recomputed from the request's own header (Task 5); NavClient — success parse + error-response routing from canned XML (Task 6); full suite green before the live run (Task 7).
- **Generated-name risk:** Tasks 4, 5, 6 each begin with an inspection step because exact JAXB class/method names depend on the XSDs; the plan flags that generated names are authoritative where they differ from assumed names.
- **No fabricated schemas:** Task 0 stops and asks the user if any XSD cannot be downloaded.
- **Secrets:** `config.properties` is git-ignored from Task 1; Task 8 verifies it is never staged.
