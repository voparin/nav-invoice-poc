# NAV Online Számla — Invoice Upload PoC

A small Java command-line proof-of-concept that submits sample invoices to the
Hungarian tax authority's **NAV Online Számla** system (the *test* environment),
exercising the full `tokenExchange` → `manageInvoice` flow with correct request
signing and token decryption per the Online Számla REST API **v3.0** specification.

It submits **four invoice types** in one run:

1. **CREATE** — domestic supply of goods (*belföldi termékértékesítés*), 27% VAT
2. **CREATE** — intra-Community acquisition of goods (*közösségen belüli termékbeszerzés*), out-of-scope VAT
3. **MODIFY** — a modifying invoice (*módosító számla*) referencing invoice #1
4. **STORNO** — an invalidating invoice (*érvénytelenítő számla*) referencing invoice #2

Each submission prints the `transactionId` returned by NAV.

> ⚠️ **Test environment only.** This talks to `https://api-test.onlineszamla.nav.gov.hu`.
> Nothing here reaches NAV's production system; invoices submitted here are not real
> filings.

---

## 1. Prerequisites

- **Java 17 (JRE or JDK).** Nothing else — no Maven, no source build needed to run.
  - Install from [Adoptium Temurin 17](https://adoptium.net/temurin/releases/?version=17)
    or, on macOS, `brew install openjdk@17`.
  - Check: `java -version` should report `17.x`.

## 2. Get your own NAV test credentials

The tool authenticates as **your** NAV test *technical user*. You cannot use someone
else's — the signing/exchange keys are per-technical-user and the data belongs to a
specific taxpayer. Steps (all in a web browser):

1. Go to **https://onlineszamla-test.nav.gov.hu** and **register as a taxpayer
   (adózó)**. You need an entity that *issues invoices* — e.g. a company or an
   **egyéni vállalkozás (sole proprietorship)** with an **adószám** (tax number of the
   form `12345678-1-42`). A private individual's personal tax identifier
   (*adóazonosító jel*) is **not** an invoice-issuing taxpayer and will be rejected with
   `NOT_REGISTERED_CUSTOMER`.
2. Log in as the **primary user (elsődleges felhasználó)** you just created.
3. **Felhasználók** (Users) → **Új felhasználó** → create a **Technikai felhasználó**.
   The portal generates a 15-character **login**; you set a **password**.
4. Open that technical user and **generate keys** (*Kulcsgenerálás*): copy the
   **aláírókulcs** (signing key) and **cserekulcs** (exchange key) — they may be shown
   only once.
5. Make sure the technical user has the **invoice data submission** permission
   (*Számla kezelése / adatszolgáltatás*), or you'll get `INVALID_USER_RELATION`.

You now have five values, **all belonging to the same taxpayer**:

| Value          | What it is                                              |
| -------------- | ------------------------------------------------------- |
| `login`        | technical user login (15 alphanumeric chars)            |
| `password`     | the password you set for the technical user             |
| `signingKey`   | the generated signature key (*aláírókulcs*)             |
| `exchangeKey`  | the generated exchange key (*cserekulcs*), 16 chars     |
| `taxNumber`    | first **8 digits** of the taxpayer's adószám            |

## 3. Configure

Copy the template and fill in your five values:

```bash
cp config.properties.example config.properties
```

Edit `config.properties`:

```properties
nav.baseUrl=https://api-test.onlineszamla.nav.gov.hu
nav.login=YOUR_LOGIN
nav.password=YOUR_PASSWORD
nav.signingKey=YOUR_SIGNING_KEY
nav.exchangeKey=YOUR_EXCHANGE_KEY
nav.taxNumber=12345678
# 18-char software id ([0-9A-Z-]{18}); the placeholder below is fine for the test env
nav.softwareId=HU00000000POC00001
nav.softwareDevContact=you@example.com
```

> 🔒 Keep `config.properties` private — it holds credentials. It is git-ignored; never
> commit it or share it.

## 4. Run

```bash
java -jar nav-invoice-poc-1.3.0.jar config.properties
```

(If you omit the argument it defaults to `config.properties` in the current directory.)

### What success looks like

```
Step 1: Requesting exchange token...
  token valid to: 2026-10-03T13:05:00.000Z
Step 2: CREATE domestic goods invoice POC-D-1759...
  [CREATE domestic] transactionId = 4ABC123DEF456...
Step 3: CREATE intra-Community acquisition invoice POC-I-1759...
  [CREATE intra-Community] transactionId = 4ABC123DEF457...
Step 4: MODIFY invoice POC-M-1759... (references POC-D-1759...)
  [MODIFY] transactionId = 4ABC123DEF458...
Step 5: STORNO invoice POC-S-1759... (references POC-I-1759...)
  [STORNO] transactionId = 4ABC123DEF459...
DONE. Query each transactionId via /queryTransactionStatus.
```

A `transactionId` means NAV **received** the submission and it passed synchronous
validation. Processing is then asynchronous: NAV validates the invoice content in the
background and the final verdict is `DONE` (accepted) or `ABORTED` (rejected). Check it
with the built-in status command:

```bash
java -jar nav-invoice-poc-1.3.0.jar status <transactionId> config.properties
```

This prints each invoice's status plus any technical/business validation messages — the
exact error codes and locations NAV reports, which is how you diagnose an `ABORTED`
result. (The main run already waits for the base invoices behind MODIFY/STORNO to reach
`DONE` before referencing them.)

### Listing invoices stored at NAV

To confirm invoices are actually stored on NAV's side — independent of the (unreliable)
test web UI — list the outbound invoices NAV has for your taxpayer:

```bash
java -jar nav-invoice-poc-1.3.0.jar digest [days] config.properties
```

`days` defaults to 1 (today). This calls `queryInvoiceDigest` and prints invoice number,
operation, category, issue date, and supplier tax number for each invoice NAV returns —
proof the data is persisted. Note: the NAV **test** portal's browse view does not reliably
show submitted invoices even when they are accepted (`DONE`) and queryable here.

## 5. Troubleshooting

| Message | Meaning / fix |
| --- | --- |
| `Configuration problem: Missing required config: X` | Fill in `nav.X` in `config.properties`. |
| `NAV rejected a request: ... NOT_REGISTERED_CUSTOMER` | The `taxNumber` isn't a registered test taxpayer. Register an invoice-issuing entity (company / egyéni vállalkozás), use its adószám's first 8 digits. |
| `NAV rejected a request: ... INVALID_USER_RELATION` | The technical user isn't linked to that taxpayer, or lacks the invoice-submission permission. Create the technical user *under* the taxpayer and grant the permission. |
| `NAV rejected a request: ... INVALID_SECURITY_USER` / signature errors | Check `login`, `password`, `signingKey` are exactly the values from the portal. |
| `NAV rejected a request: ... INVALID_EXCHANGE_TOKEN` | A NAV exchange token is single-use — request a fresh `tokenExchange` before each `manageInvoice`. (This tool already does so.) |
| `unable to find valid certification path` (TLS) | Your Java trust store is missing the Microsec e‑Szigno root CA that NAV uses. Import it into the JRE's `cacerts`, or use a JRE whose trust store includes it. |
| `HTTP connect timed out` | NAV's test endpoint is unreachable from your network at the moment (or a VPN/firewall is blocking outbound 443 to it). Retry later / off the restricted network. |
| Submitted invoices don't appear in the web portal | Two causes: (1) the NAV **test** portal does not reliably display submitted invoices even when accepted — use `digest` to confirm they're stored; (2) you may be viewing a different taxpayer than the one the technical user belongs to (invoices appear under the **supplier** taxpayer, as **outgoing/issued**, filtered by issue date). Old `ABORTED` submissions also generate failure emails that remain in your inbox — check the transaction ID against current runs. |

## 6. Example invoices

The exact `invoiceData` XML the tool generates for each of the four types is checked in
under [`examples/`](examples/) so you can see the structure that gets base64-encoded and
submitted:

| File | Invoice type | Distinguishing feature |
| --- | --- | --- |
| [`01-domestic-goods.xml`](examples/01-domestic-goods.xml) | belföldi termékértékesítés (domestic goods) | `vatPercentage` 0.27 |
| [`02-intra-community-supply.xml`](examples/02-intra-community-supply.xml) | közösségen belüli termékértékesítés (intra-Community supply) | `vatExemption` case `KBAET`, customer `OTHER` |
| [`03-modify.xml`](examples/03-modify.xml) | módosító számla (modify) | `invoiceReference` + `lineModificationReference` |
| [`04-storno.xml`](examples/04-storno.xml) | érvénytelenítő számla (storno) | `invoiceReference` + negated amounts |

These use a placeholder supplier tax number (`12345678`); the running tool substitutes
your own `nav.taxNumber`. All four validate against the official NAV `invoiceData.xsd`,
and all four have been verified to reach **`DONE`** (fully accepted) in NAV's test system.

## 7. Building from source (optional)

If you have the source and want to rebuild the jar:

```bash
mvn -q package              # runs tests, produces target/nav-invoice-poc-1.3.0.jar
mvn -q exec:java            # or run directly against config.properties
```

Requires JDK 17 and Maven. JAXB classes are generated from the official NAV XSDs
(`src/main/resources/xsd/`) at build time. The test suite validates the crypto, config,
request signing, and that each generated invoice is schema-valid against the NAV XSDs —
all offline, no network needed.
