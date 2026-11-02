package org.ngoplatform.campaign.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class UcPdfTest {
    static UcService.Uc uc(boolean certified) {
        Instant now = Instant.parse("2026-11-02T06:00:00Z");
        return new UcService.Uc(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "UC/2026-27/001", "2026-27",
                "Sahyadri Industries CSR Foundation", "SCF/2026/14", "CSR00012345", new BigDecimal("50000.00"), LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 11, 1), certified ? "CA_CERTIFIED" : "NGO_SIGNED", null, "Asha Patil", "Chairman",
                UUID.randomUUID(), now, "code", now.plusSeconds(86400 * 14), certified ? "Meena Joshi" : null, certified ? "123456" : null,
                certified ? "Joshi & Co" : null, certified ? "012345W" : null, certified ? "26123456ABCDEFGHIJ" : null, certified ? "Pune" : null,
                certified ? now : null, null, null, null, null, UUID.randomUUID(), now, 1);
    }

    static UcRules.Figures figures() {
        Map<String, BigDecimal> budget = new LinkedHashMap<>();
        budget.put("Books", new BigDecimal("30000"));
        budget.put("Transport", new BigDecimal("5000"));
        List<UcRules.Line> lines = new ArrayList<>();
        for (int i = 1; i <= 60; i++) { // long annexure: page breaks
            lines.add(new UcRules.Line(String.format("EXP/2026-27/%04d", i), LocalDate.of(2026, 9, 1).plusDays(i % 30), i % 3 == 0 ? "Transport" : "Books",
                    "Text books for class " + (i % 10 + 1) + " (set of 40)", "Pune Book Depot", new BigDecimal("350.00"), i % 7 == 0 ? 0 : 1));
        }
        return UcRules.figures(new BigDecimal("50000"), budget, lines, new BigDecimal("52000"));
    }

    static final Map<String, Object> NGO = Map.of("legalName", "Asha Seva Foundation", "address", "12 Test Lane, Kothrud, Pune 411038",
            "registrationNumber", "TEST/0001/2015", "pan", "AAATA1234T", "tax80gNumber", "AAATA0000A25PN01");

    @Test
    void certifiedUcCarriesBothSignOffsAndTheAnnexure() throws Exception {
        byte[] pdf = UcPdf.render(uc(true), "School books for 200 children", NGO, figures(), false);
        try (PDDocument d = Loader.loadPDF(pdf)) {
            assertThat(d.getNumberOfPages()).isGreaterThanOrEqualTo(3);
            String t = new PDFTextStripper().getText(d);
            assertThat(t).contains("UTILISATION CERTIFICATE", "UC/2026-27/001", "Asha Seva Foundation", "Sahyadri Industries CSR Foundation",
                    "SCF/2026/14", "CSR-1 CSR00012345", "Books", "Transport", "Asha Patil", "Chairman", "Meena Joshi", "M. No. 123456",
                    "26123456ABCDEFGHIJ", "FRN 012345W", "Annexure", "EXP/2026-27/0060", "Generated with Yogdaan")
                    .doesNotContain("DRAFT - NOT CERTIFIED");
        }
        Files.write(Path.of("target", "sample-uc.pdf"), pdf);
    }

    @Test
    void draftIsMarkedAndWaitsForTheCa() throws Exception {
        byte[] pdf = UcPdf.render(uc(false), "School books for 200 children", NGO, figures(), true);
        try (PDDocument d = Loader.loadPDF(pdf)) {
            String t = new PDFTextStripper().getText(d);
            assertThat(t).contains("DRAFT - NOT CERTIFIED", "awaiting certification by the chartered accountant");
        }
        Files.write(Path.of("target", "sample-uc-draft.pdf"), pdf);
    }
}
