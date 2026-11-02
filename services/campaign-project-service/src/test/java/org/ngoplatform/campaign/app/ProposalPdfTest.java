package org.ngoplatform.campaign.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class ProposalPdfTest {
    static ProposalService.Proposal proposal(boolean sent) {
        Instant now = Instant.parse("2026-11-02T06:00:00Z");
        String need = "Two hundred children in Kothrud's municipal schools start the year without text books. Families earn "
                + "less than Rs 12,000 a month and books cost Rs 1,800 per child.\n\nTeachers report that children without books "
                + "fall behind within the first term. " + "This is a long paragraph to check page breaks. ".repeat(25);
        return new ProposalService.Proposal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "PR/2026-27/001", "2026-27",
                "School books for 200 children", "Sahyadri Industries CSR Foundation", "Ms. Kavita Rao", "csr@sahyadri.example",
                "EDUCATION", new BigDecimal("250000.00"), 12, 200, "Kothrud, Pune", need,
                "Every child in classes 1-8 of two schools gets a full set of text books and notebooks.",
                "Assess needs with head teachers; buy from local stationers; distribute in school.",
                "200 children with books from day one; attendance up by 10%.", "", "Asha Patil, Chairperson",
                "asha@ashaseva.example · +91 98200 00000", UUID.randomUUID(), "Compliance documents FY 2026-27",
                "http://localhost:8080/api/v1/public/csr/Ab3dEf6hIj9kLm2nOpQr", now.plusSeconds(86400 * 30), sent ? "SENT" : "DRAFT",
                sent ? LocalDate.of(2026, 11, 2) : null, null, null, null, null, null, null, null, null, UUID.randomUUID(), now, 1);
    }

    static ProposalService.Progress progress() {
        return new ProposalService.Progress(new BigDecimal("360000"), new BigDecimal("52000"), 14, new BigDecimal("25300"), 4,
                List.of(new ProposalService.BudgetLine("Textbooks", new BigDecimal("18000"), new BigDecimal("8500")),
                        new ProposalService.BudgetLine("Notebooks", new BigDecimal("20000"), new BigDecimal("9600"))),
                new BigDecimal("38000"), LocalDate.of(2026, 10, 24), null, "PUBLISHED");
    }

    static final Map<String, Object> NGO = Map.of("legalName", "Asha Seva Foundation", "address", "12 Test Lane, Kothrud, Pune 411038",
            "registrationNumber", "TEST/0001/2015", "pan", "AAATA1234T", "tax80gNumber", "AAATA0000A25PN01", "officialEmail", "info@ashaseva.example");

    @Test
    void sentProposalCarriesTheWholeCase() throws Exception {
        byte[] pdf = ProposalPdf.render(proposal(true), "School books for 200 children", NGO, progress(), false);
        try (PDDocument d = Loader.loadPDF(pdf)) {
            assertThat(d.getNumberOfPages()).isGreaterThanOrEqualTo(2);
            String t = new PDFTextStripper().getText(d);
            assertThat(t).contains("CSR PROJECT PROPOSAL", "PR/2026-27/001", "Submitted on 2 Nov 2026", "Sahyadri Industries CSR Foundation",
                    "(ii) Education", "1. The need", "4. Expected outcomes", "Progress so far", "Raised", "Textbooks", "Compliance documents",
                    "Asha Patil, Chairperson", "Generated with Yogdaan").doesNotContain("DRAFT");
            assertThat(t.replaceAll("\\s+", " ")).contains("utilisation certificate certified by a chartered accountant");
        }
        Files.write(Path.of("target", "sample-proposal.pdf"), pdf);
    }

    @Test
    void draftIsMarked() throws Exception {
        byte[] pdf = ProposalPdf.render(proposal(false), "School books for 200 children", NGO, progress(), true);
        try (PDDocument d = Loader.loadPDF(pdf)) {
            assertThat(new PDFTextStripper().getText(d)).contains("DRAFT", "PR/2026-27/001");
        }
    }
}
