package poc.nav;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;

import hu.gov.nav.schemas.osa._3_0.data.*;
import hu.gov.nav.schemas.osa._3_0.base.AddressType;
import hu.gov.nav.schemas.osa._3_0.base.SimpleAddressType;
import hu.gov.nav.schemas.osa._3_0.base.TaxNumberType;
import hu.gov.nav.schemas.osa._3_0.base.InvoiceCategoryType;
import hu.gov.nav.schemas.osa._3_0.base.PaymentMethodType;
import hu.gov.nav.schemas.osa._3_0.base.InvoiceAppearanceType;

/**
 * Builds minimal, schema-valid {@code InvoiceData} XML for the four invoice variants
 * the user actually issues, and marshals each to XML bytes.
 *
 * <p>Only the invoice BODY is built here; the {@code invoiceOperation}
 * (CREATE / MODIFY / STORNO) is chosen by the caller when wrapping the request.
 */
public final class SampleInvoiceFactory {

    // Single-line amounts shared by the domestic/modify/storno variants.
    private static final BigDecimal NET = new BigDecimal("1000");
    private static final BigDecimal VAT_PERCENTAGE = new BigDecimal("0.27");
    private static final BigDecimal VAT = new BigDecimal("270");
    private static final BigDecimal GROSS = new BigDecimal("1270");

    private SampleInvoiceFactory() {}

    /** Belfoldi termekertekesites: one line, vatPercentage 27%, HUF. */
    public static byte[] createDomesticGoods(Config cfg, String invoiceNumber) {
        try {
            ObjectFactory of = new ObjectFactory();
            InvoiceData data = buildData(of, cfg, invoiceNumber);
            InvoiceType invoice = data.getInvoiceMain().getInvoice();
            invoice.setInvoiceLines(domesticLines(of, false));
            invoice.setInvoiceSummary(domesticSummary(of, false));
            return marshal(data);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build domestic invoice", e);
        }
    }

    /**
     * Kozossegen beluli termekbeszerzes: one line marked vatOutOfScope
     * (self-assessed acquisition). VAT amounts are 0; net = gross.
     */
    public static byte[] createIntraCommunityAcquisition(Config cfg, String invoiceNumber) {
        try {
            ObjectFactory of = new ObjectFactory();
            InvoiceData data = buildData(of, cfg, invoiceNumber);
            InvoiceType invoice = data.getInvoiceMain().getInvoice();
            invoice.setInvoiceLines(outOfScopeLines(of));
            invoice.setInvoiceSummary(outOfScopeSummary(of));
            return marshal(data);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build intra-Community acquisition invoice", e);
        }
    }

    /** Modosito szamla: domestic shape plus an invoiceReference to the original. */
    public static byte[] createModify(Config cfg, String invoiceNumber,
                                      String originalInvoiceNumber, BigInteger modificationIndex) {
        try {
            ObjectFactory of = new ObjectFactory();
            InvoiceData data = buildData(of, cfg, invoiceNumber);
            InvoiceType invoice = data.getInvoiceMain().getInvoice();
            invoice.setInvoiceReference(invoiceReference(of, originalInvoiceNumber, modificationIndex));
            invoice.setInvoiceLines(domesticLines(of, false));
            invoice.setInvoiceSummary(domesticSummary(of, false));
            return marshal(data);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build modify invoice", e);
        }
    }

    /** Ervenytelenito (storno) szamla: invoiceReference to the original, amounts negated. */
    public static byte[] createStorno(Config cfg, String invoiceNumber,
                                      String originalInvoiceNumber, BigInteger modificationIndex) {
        try {
            ObjectFactory of = new ObjectFactory();
            InvoiceData data = buildData(of, cfg, invoiceNumber);
            InvoiceType invoice = data.getInvoiceMain().getInvoice();
            invoice.setInvoiceReference(invoiceReference(of, originalInvoiceNumber, modificationIndex));
            invoice.setInvoiceLines(domesticLines(of, true));
            invoice.setInvoiceSummary(domesticSummary(of, true));
            return marshal(data);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build storno invoice", e);
        }
    }

    // --- shared construction -------------------------------------------------

    /** Builds InvoiceData with head (supplier, customer, detail); lines & summary added by caller. */
    private static InvoiceData buildData(ObjectFactory of, Config cfg, String invoiceNumber)
            throws Exception {
        InvoiceData data = of.createInvoiceData();
        data.setInvoiceNumber(invoiceNumber);
        data.setInvoiceIssueDate(today());
        data.setCompletenessIndicator(false);

        InvoiceMainType main = of.createInvoiceMainType();
        InvoiceType invoice = of.createInvoiceType();
        invoice.setInvoiceHead(buildHead(of, cfg));
        main.setInvoice(invoice);
        data.setInvoiceMain(main);
        return data;
    }

    private static InvoiceHeadType buildHead(ObjectFactory of, Config cfg) throws Exception {
        InvoiceHeadType head = of.createInvoiceHeadType();

        SupplierInfoType supplier = of.createSupplierInfoType();
        TaxNumberType supTax = new TaxNumberType();
        supTax.setTaxpayerId(cfg.taxNumber());
        supplier.setSupplierTaxNumber(supTax);
        supplier.setSupplierName("PoC Supplier Kft.");
        supplier.setSupplierAddress(buildAddress());
        head.setSupplierInfo(supplier);

        CustomerInfoType customer = of.createCustomerInfoType();
        customer.setCustomerVatStatus(CustomerVatStatusType.DOMESTIC);
        CustomerTaxNumberType custTax = of.createCustomerTaxNumberType();
        custTax.setTaxpayerId("11111111");
        customer.setCustomerVatData(wrapCustomerTaxNumber(of, custTax));
        customer.setCustomerName("PoC Customer Kft.");
        customer.setCustomerAddress(buildAddress());
        head.setCustomerInfo(customer);

        InvoiceDetailType detail = of.createInvoiceDetailType();
        detail.setInvoiceCategory(InvoiceCategoryType.NORMAL);
        detail.setInvoiceDeliveryDate(today());
        detail.setCurrencyCode("HUF");
        detail.setExchangeRate(BigDecimal.ONE);
        detail.setPaymentMethod(PaymentMethodType.TRANSFER);
        detail.setInvoiceAppearance(InvoiceAppearanceType.ELECTRONIC);
        head.setInvoiceDetail(detail);

        return head;
    }

    private static CustomerVatDataType wrapCustomerTaxNumber(ObjectFactory of,
                                                             CustomerTaxNumberType custTax) {
        CustomerVatDataType vatData = of.createCustomerVatDataType();
        vatData.setCustomerTaxNumber(custTax);
        return vatData;
    }

    private static InvoiceReferenceType invoiceReference(ObjectFactory of,
                                                         String originalInvoiceNumber,
                                                         BigInteger modificationIndex) {
        InvoiceReferenceType ref = of.createInvoiceReferenceType();
        ref.setOriginalInvoiceNumber(originalInvoiceNumber);
        ref.setModifyWithoutMaster(false);
        ref.setModificationIndex(modificationIndex.intValueExact());
        return ref;
    }

    // --- domestic / storno line + summary (27% VAT, optionally negated) ------

    private static LinesType domesticLines(ObjectFactory of, boolean negate) {
        BigDecimal net = sign(NET, negate);
        BigDecimal vat = sign(VAT, negate);
        BigDecimal gross = sign(GROSS, negate);

        LinesType lines = of.createLinesType();
        lines.setMergedItemIndicator(false);
        LineType line = of.createLineType();
        line.setLineNumber(BigInteger.ONE);
        line.setLineExpressionIndicator(true);
        line.setLineDescription("PoC item");
        line.setQuantity(sign(BigDecimal.ONE, negate));
        line.setUnitPrice(NET);

        LineAmountsNormalType amt = of.createLineAmountsNormalType();
        LineNetAmountDataType netData = of.createLineNetAmountDataType();
        netData.setLineNetAmount(net);
        netData.setLineNetAmountHUF(net);
        amt.setLineNetAmountData(netData);

        VatRateType rate = of.createVatRateType();
        rate.setVatPercentage(VAT_PERCENTAGE);
        amt.setLineVatRate(rate);

        LineVatDataType vatData = of.createLineVatDataType();
        vatData.setLineVatAmount(vat);
        vatData.setLineVatAmountHUF(vat);
        amt.setLineVatData(vatData);

        LineGrossAmountDataType grossData = of.createLineGrossAmountDataType();
        grossData.setLineGrossAmountNormal(gross);
        grossData.setLineGrossAmountNormalHUF(gross);
        amt.setLineGrossAmountData(grossData);

        line.setLineAmountsNormal(amt);
        lines.getLine().add(line);
        return lines;
    }

    private static SummaryType domesticSummary(ObjectFactory of, boolean negate) {
        BigDecimal net = sign(NET, negate);
        BigDecimal vat = sign(VAT, negate);
        BigDecimal gross = sign(GROSS, negate);

        SummaryType summary = of.createSummaryType();
        SummaryNormalType sn = of.createSummaryNormalType();

        SummaryByVatRateType byRate = of.createSummaryByVatRateType();
        VatRateType sRate = of.createVatRateType();
        sRate.setVatPercentage(VAT_PERCENTAGE);
        byRate.setVatRate(sRate);
        VatRateNetDataType snet = of.createVatRateNetDataType();
        snet.setVatRateNetAmount(net);
        snet.setVatRateNetAmountHUF(net);
        byRate.setVatRateNetData(snet);
        VatRateVatDataType svat = of.createVatRateVatDataType();
        svat.setVatRateVatAmount(vat);
        svat.setVatRateVatAmountHUF(vat);
        byRate.setVatRateVatData(svat);
        sn.getSummaryByVatRate().add(byRate);

        sn.setInvoiceNetAmount(net);
        sn.setInvoiceNetAmountHUF(net);
        sn.setInvoiceVatAmount(vat);
        sn.setInvoiceVatAmountHUF(vat);
        summary.setSummaryNormal(sn);

        SummaryGrossDataType sg = of.createSummaryGrossDataType();
        sg.setInvoiceGrossAmount(gross);
        sg.setInvoiceGrossAmountHUF(gross);
        summary.setSummaryGrossData(sg);
        return summary;
    }

    // --- intra-Community (vatOutOfScope, VAT = 0, net = gross) ---------------

    private static VatRateType outOfScopeRate(ObjectFactory of) {
        VatRateType rate = of.createVatRateType();
        DetailedReasonType reason = of.createDetailedReasonType();
        reason.setCase("K");
        reason.setReason("Kozossegen beluli beszerzes");
        rate.setVatOutOfScope(reason);
        return rate;
    }

    private static LinesType outOfScopeLines(ObjectFactory of) {
        LinesType lines = of.createLinesType();
        lines.setMergedItemIndicator(false);
        LineType line = of.createLineType();
        line.setLineNumber(BigInteger.ONE);
        line.setLineExpressionIndicator(true);
        line.setLineDescription("PoC intra-Community item");
        line.setQuantity(BigDecimal.ONE);
        line.setUnitPrice(NET);

        LineAmountsNormalType amt = of.createLineAmountsNormalType();
        LineNetAmountDataType netData = of.createLineNetAmountDataType();
        netData.setLineNetAmount(NET);
        netData.setLineNetAmountHUF(NET);
        amt.setLineNetAmountData(netData);

        amt.setLineVatRate(outOfScopeRate(of));

        LineVatDataType vatData = of.createLineVatDataType();
        vatData.setLineVatAmount(BigDecimal.ZERO);
        vatData.setLineVatAmountHUF(BigDecimal.ZERO);
        amt.setLineVatData(vatData);

        LineGrossAmountDataType grossData = of.createLineGrossAmountDataType();
        grossData.setLineGrossAmountNormal(NET);
        grossData.setLineGrossAmountNormalHUF(NET);
        amt.setLineGrossAmountData(grossData);

        line.setLineAmountsNormal(amt);
        lines.getLine().add(line);
        return lines;
    }

    private static SummaryType outOfScopeSummary(ObjectFactory of) {
        SummaryType summary = of.createSummaryType();
        SummaryNormalType sn = of.createSummaryNormalType();

        SummaryByVatRateType byRate = of.createSummaryByVatRateType();
        byRate.setVatRate(outOfScopeRate(of));
        VatRateNetDataType snet = of.createVatRateNetDataType();
        snet.setVatRateNetAmount(NET);
        snet.setVatRateNetAmountHUF(NET);
        byRate.setVatRateNetData(snet);
        VatRateVatDataType svat = of.createVatRateVatDataType();
        svat.setVatRateVatAmount(BigDecimal.ZERO);
        svat.setVatRateVatAmountHUF(BigDecimal.ZERO);
        byRate.setVatRateVatData(svat);
        sn.getSummaryByVatRate().add(byRate);

        sn.setInvoiceNetAmount(NET);
        sn.setInvoiceNetAmountHUF(NET);
        sn.setInvoiceVatAmount(BigDecimal.ZERO);
        sn.setInvoiceVatAmountHUF(BigDecimal.ZERO);
        summary.setSummaryNormal(sn);

        SummaryGrossDataType sg = of.createSummaryGrossDataType();
        sg.setInvoiceGrossAmount(NET);
        sg.setInvoiceGrossAmountHUF(NET);
        summary.setSummaryGrossData(sg);
        return summary;
    }

    // --- helpers -------------------------------------------------------------

    private static BigDecimal sign(BigDecimal v, boolean negate) {
        return negate ? v.negate() : v;
    }

    private static AddressType buildAddress() {
        AddressType addr = new AddressType();
        SimpleAddressType simple = new SimpleAddressType();
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
                d.getDayOfMonth(), DatatypeConstants.FIELD_UNDEFINED);
    }

    private static byte[] marshal(InvoiceData data) throws Exception {
        JAXBContext ctx = JAXBContext.newInstance(InvoiceData.class);
        Marshaller m = ctx.createMarshaller();
        m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, false);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        m.marshal(data, out);
        return out.toByteArray();
    }
}
