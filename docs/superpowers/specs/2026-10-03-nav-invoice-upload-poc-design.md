# NAV Online Számla — Invoice Upload PoC (Java)

**Date:** 2026-10-03
**Status:** Approved design

## Goal

A command-line Java application that generates a sample Hungarian invoice, correctly
signs and encrypts the request per the NAV Online Számla REST API specification
**v3.0** (2026-08-11), and performs a **full live submission** to the NAV test system
(`api-test.onlineszamla.nav.gov.hu`), returning a `transactionId`.

Source specification: `Online Számla interfész specifikáció HU v3.0 (2026-08-11)`.

## Scope

- **In scope:** full live round-trip — `tokenExchange` then `manageInvoice` against the
  NAV **test** endpoint; sample invoices generated in code; all required cryptography.
- The PoC exercises the **four invoice types the user actually uses**, as four separate
  `manageInvoice` submissions in one run:
  1. `CREATE` — **belföldi termékértékesítés** (domestic supply of goods), 27% VAT line.
  2. `CREATE` — **közösségen belüli termékbeszerzés** (intra-Community acquisition of
     goods), marked `vatOutOfScope` (out of scope of Hungarian VAT; buyer self-assesses).
  3. `MODIFY` — **módosító számla** referencing invoice #1 via `invoiceReference`
     (`originalInvoiceNumber`, `modifyWithoutMaster=false`, `modificationIndex`).
  4. `STORNO` — **érvénytelenítő számla** referencing invoice #2 via `invoiceReference`.
  - MODIFY and STORNO reference the invoices this same run created (self-contained chain).
- **Out of scope:** querying transaction status, technical annulment (`manageAnnulment`),
  production endpoint, batch submission within a single request, GUI.

## The NAV flow (two live calls)

```
1. POST /invoiceService/v3/tokenExchange
      → returns encodedExchangeToken (AES-128-ECB encrypted with the technical
        user's exchangeKey)
      → client decrypts it locally to obtain the plaintext exchangeToken

2. POST /invoiceService/v3/manageInvoice
      → carries the decrypted exchangeToken + base64(invoiceData XML) + requestSignature
      → returns transactionId (async processing; result queryable later via
        /queryTransactionStatus — not part of this PoC)
      → called once per invoice type (CREATE domestic, CREATE intra-Community,
        MODIFY, STORNO); each returns its own transactionId.
```

Base URL (test): `https://api-test.onlineszamla.nav.gov.hu`
Context root: `/invoiceService/v3`
HTTP method: `POST`, request & response bodies are XML.
Gateway timeout: 60 seconds.

## Cryptography (confirmed against spec)

| Element | Computation | Attribute |
|---|---|---|
| `passwordHash` | `UPPERCASE( SHA-512( password ) )` | `cryptoType="SHA-512"` |
| `requestSignature` (manageInvoice) | `UPPERCASE( SHA3-512( requestId + ts + signingKey + indexHash₁ + … ) )` | `cryptoType="SHA3-512"` |
| per-invoice `indexHash` | `UPPERCASE( SHA3-512( invoiceOperation + base64InvoiceData ) )` | — |
| `requestSignature` (tokenExchange) | `UPPERCASE( SHA3-512( requestId + ts + signingKey ) )` | `cryptoType="SHA3-512"` |
| `exchangeToken` decrypt | `AES-128-ECB decrypt( base64decode(encodedExchangeToken), exchangeKey )` | — |

Notes:
- `ts` in the signature is the request `timestamp` in UTC with separators and timezone
  stripped: mask `yyyyMMddHHmmss`.
- The XML `timestamp` tag uses `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` (UTC), tolerance ±1 day
  vs. server time.
- Hashes are hex strings, uppercased.
- AES mode is ECB with no padding (token length is a block multiple); key is the
  technical user's exchange key (literal, 16 bytes → AES-128).

Worked example from the spec (used as the unit-test oracle):
- `requestId = TSTKFT1222564`, `timestamp = 2017-12-30T18:25:45.000Z`,
  `signingKey = ce-8f5e-215119fa7dd621DLMRHRLH2S`
- invoice 1: `invoiceOperation=CREATE`, `invoiceData=QWJjZDEyMzQ=`
- invoice 2: `invoiceOperation=MODIFY`, `invoiceData=RGNiYTQzMjE=`
- expected `requestSignature` =
  `60BC80609EE3B8F42FE904200A49A1921A1DADA08D55319ACD40C59F626514B74EEA49011D372600A10DBCF8199D590DA9C2841D987308F2D83DAE17C2470C42`

## Components

Each has one clear purpose, a well-defined interface, and is independently testable.

| Component | Responsibility | Depends on |
|---|---|---|
| `Config` | Load credentials (`login`, `password`, `signingKey`, `exchangeKey`, `taxNumber`) and base URL from env vars / `config.properties`. Validate presence/format up front. No secrets in code. | — |
| `CryptoUtil` | Pure functions: `sha512Upper`, `sha3_512Upper`, `aes128EcbDecrypt`. | JDK crypto |
| `SampleInvoiceFactory` | Build the four invoice variants as `InvoiceData` JAXB objects and marshal each to XML bytes: domestic-goods CREATE, intra-Community-acquisition CREATE, MODIFY (with `invoiceReference` to a prior number), STORNO (with `invoiceReference`). | JAXB classes |
| `RequestFactory` | Build `TokenExchangeRequest` and `ManageInvoiceRequest` JAXB objects: header (`requestId`, `timestamp`, `requestVersion`, `headerVersion`), `user` (login, passwordHash, taxNumber), `software` block, and the computed `requestSignature`. | `CryptoUtil`, `Config`, JAXB |
| `NavClient` | HTTP POST via Java 11 `HttpClient`; marshal request / unmarshal response XML; detect NAV error responses. 60s timeout. | JAXB, `Config` |
| `App` (`main`) | Orchestrate: build+send tokenExchange → decrypt token → then for each of the four invoice variants build+send manageInvoice → print each `transactionId`. MODIFY/STORNO reuse the invoice numbers created earlier in the run. | all above |

## Build & XML binding

- **Maven** project, **JDK 17**.
- `jaxb2-maven-plugin` runs `xjc` on the official NAV XSDs (`invoiceApi.xsd`,
  `invoiceData.xsd`, `invoiceBase.xsd`, `common.xsd`) at build time → generates
  type-safe JAXB classes. XSDs stored under `src/main/resources/xsd/`.
- Runtime: `jakarta.xml.bind-api` + `org.glassfish.jaxb:jaxb-runtime`.
- If an official XSD cannot be obtained, flag it to the user rather than hand-faking it.

## Error handling

- **Config validation** up front: fail fast with a clear message naming the missing or
  malformed credential (e.g. `taxNumber` must be 8 digits, `login` 6–15 alphanumerics).
- **NAV business errors:** a response may be a `GeneralErrorResponse` /
  `GeneralExceptionResponse` with `funcCode=ERROR`. `NavClient` detects this and prints
  `errorCode` + `message` instead of crashing.
- **Network/timeout:** 60s timeout (NAV gateway limit); surface a clear error. The PoC
  does not implement the lost-transaction recovery flow (documented but out of scope).

## Testing

- **Unit test `CryptoUtil`** against the spec's worked example above — proves the
  signature algorithm is correct before any live call. Also test `passwordHash` shape
  and AES decrypt round-trip (encrypt a known value with the key, decrypt, compare).
- `App` is exercised manually with real NAV **test** credentials for the live submission.

## Runtime inputs

Five NAV test-account values provided via env vars or `config.properties`
(not committed):

- `NAV_LOGIN` — technical user login (6–15 alphanumeric)
- `NAV_PASSWORD` — technical user password (plaintext; hashed by the app)
- `NAV_SIGNING_KEY` — technical user signature key
- `NAV_EXCHANGE_KEY` — technical user exchange key (AES key)
- `NAV_TAX_NUMBER` — first 8 digits of the taxpayer's Hungarian tax number

These originate from a registered **technical user** in a NAV test account at
`onlineszamla-test.nav.gov.hu`.

## Software block (identifies the billing program to NAV)

Required `software` fields (`softwareId`, `softwareName`, `softwareOperation=LOCAL_SOFTWARE`,
`softwareMainVersion`, `softwareDevName`, `softwareDevContact`, …) populated with
PoC-identifying constant values.
