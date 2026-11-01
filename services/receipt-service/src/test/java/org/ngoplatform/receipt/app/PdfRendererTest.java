package org.ngoplatform.receipt.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class PdfRendererTest {
    static Map<String, String> values(String donor) {
        Map<String, String> v = new HashMap<>();
        v.put("receiptNumber", "ASF/2026-27/000124");
        v.put("date", "01 Nov 2026");
        v.put("donorName", donor);
        v.put("donorAddress", "12 Sample Road, Pune, Maharashtra 411001");
        v.put("donorPan", "ABCDE1234F");
        v.put("amount", ReceiptRules.inr(new BigDecimal("12500")));
        v.put("amountInWords", AmountInWords.of(new BigDecimal("12500")));
        v.put("mode", "Online payment  ·  Ref. pay_123");
        v.put("towards", "School books for 120 children");
        v.put("ngoName", "Asha Seva Foundation");
        v.put("ngoAddress", "Plot 7, Shivaji Nagar, Pune 411005");
        v.put("ngoPan", "AAATA1234B");
        v.put("ngoRegistrationNumber", "F-12345 (PUNE)");
        v.put("ngo12a", "AAATA1234BE20214");
        v.put("thankYou", "Thank you for your generous support.");
        v.put("tax80gText", "This donation is eligible for deduction under section 80G of the Income-tax Act, 1961. "
                + "80G registration no. AAATA1234BF20214, valid till 31 Mar 2029.");
        v.put("verifyCode", "ABCD234567");
        return v;
    }

    @Test
    void standardReceiptIsA5LandscapeWithAllFacts() throws Exception {
        byte[] pdf = new PdfRenderer().standard(new PdfRenderer.Data(values("Meera Shah"), null, true,
                List.of(new PdfRenderer.Signatory("Asha Patil", "Chairman", null), new PdfRenderer.Signatory("Ravi Kulkarni", "Secretary", null)),
                "http://localhost:8080/api/v1/public/receipts/verify/ABCD234567"));
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
            assertThat(doc.getPage(0).getMediaBox().getWidth()).isGreaterThan(doc.getPage(0).getMediaBox().getHeight());
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("DONATION RECEIPT", "ASF/2026-27/000124", "Meera Shah", "₹ 12,500.00",
                    "Rupees Twelve Thousand Five Hundred Only", "80G", "ABCDE1234F", "Chairman", "Secretary", "12A AAATA1234BE20214",
                    "ABCD234567", "Generated with Seva Setu");
        }
        Files.write(Path.of("target", "sample-receipt.pdf"), pdf);
    }

    @Test
    void devanagariNamesArePrintedNotReplaced() throws Exception {
        byte[] pdf = new PdfRenderer().standard(new PdfRenderer.Data(values("मीरा शाह"), null, false, List.of(), "http://x/verify/ABCD234567"));
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).doesNotContain("?").contains("ASF/2026-27/000124");
            assertThat(text).doesNotContain("ABCDE1234F"); // no PAN without 80G
        }
        Files.write(Path.of("target", "sample-receipt-devanagari.pdf"), pdf);
    }

    @Test
    void customReceiptCarriesTheAppCreditUnlessTurnedOff() throws Exception {
        TemplateDefinition def = TemplateDefinition.defaults(new TemplateDefinition.Page(595.28f, 420.84f));
        PdfRenderer.Data d = new PdfRenderer.Data(values("Meera Shah"), null, true, List.of(), "http://x/verify/ABCD234567");
        Map<String, String> facts = Map.of("tax80g", "true", "channel", "ONLINE", "hasPan", "true");
        PdfRenderer pdf = new PdfRenderer();
        try (PDDocument doc = Loader.loadPDF(pdf.custom(def, null, d, facts))) {
            assertThat(new PDFTextStripper().getText(doc)).contains("Meera Shah", "Generated with Seva Setu");
        }
        pdf.branding = " ";
        try (PDDocument doc = Loader.loadPDF(pdf.custom(def, null, d, facts))) {
            assertThat(new PDFTextStripper().getText(doc)).contains("Meera Shah").doesNotContain("Seva Setu");
        }
    }

    @Test
    void qrCodeEncodesTheVerificationLink() {
        var img = PdfRenderer.qr("http://localhost:8080/api/v1/public/receipts/verify/ABCD234567", 4);
        assertThat(img.getWidth()).isEqualTo(img.getHeight()).isGreaterThan(80);
    }

    @Test
    void verificationPageEscapesAndHidesTheName() {
        String html = org.ngoplatform.receipt.api.ReceiptController.verifyPage(Map.of("found", true, "organization", "<b>Asha</b>",
                "receiptNumber", "ASF/2026-27/000124", "date", "2026-11-01", "amount", "12500.00", "donor", "M*** S***", "status", "VALID"));
        assertThat(html).contains("Genuine receipt", "&lt;b&gt;Asha&lt;/b&gt;", "₹ 12,500.00", "M*** S***").doesNotContain("<b>Asha");
        assertThat(org.ngoplatform.receipt.api.ReceiptController.verifyPage(Map.of("found", false))).contains("Receipt not found");
    }
}
