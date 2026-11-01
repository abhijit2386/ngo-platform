package org.ngoplatform.campaign.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class AccountSheetExportTest {
    static AccountSheet.Sheet sample(int expenses) {
        List<AccountSheet.ExpenseRow> rows = new ArrayList<>();
        for (int i = 1; i <= expenses; i++) {
            rows.add(new AccountSheet.ExpenseRow(UUID.randomUUID(), AccountRules.voucherNo("2026-27", i), LocalDate.of(2026, 10, 20),
                    "Books", "Notebooks for class " + i + " — a long description that must be shortened to fit the column", "Sharma Stationers",
                    "B-" + i, "UPI", i % 2 == 0 ? "Sunil More" : "NGO", new BigDecimal("1250.50"), "APPROVED", "Sunil More", "Asha Kulkarni",
                    false, i % 3 == 0 ? 0 : 1, i % 3 == 0 ? "Auto fare" : null, null));
        }
        BigDecimal spent = new BigDecimal("1250.50").multiply(BigDecimal.valueOf(expenses));
        return new AccountSheet.Sheet("Asha Seva", new AccountSheet.CampaignInfo(UUID.randomUUID(), "School books for 200 children", "CLOSED",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 8), new BigDecimal("50000.00")), Instant.now(), "Asha Kulkarni", false,
                new AccountSheet.Income(new BigDecimal("1000.00"), 1, new BigDecimal("19600.00"), 3, BigDecimal.ZERO, new BigDecimal("20600.00")),
                List.of(new AccountSheet.BudgetRow(UUID.randomUUID(), "Books", new BigDecimal("30000.00"), spent,
                                new BigDecimal("30000.00").subtract(spent), expenses, true),
                        new AccountSheet.BudgetRow(null, "Transport", BigDecimal.ZERO, new BigDecimal("300.00"), BigDecimal.ZERO, 1, false)),
                new AccountSheet.Totals(new BigDecimal("30000.00"), spent, new BigDecimal("30000.00").subtract(spent),
                        new BigDecimal("20600.00").subtract(spent), new BigDecimal("800.00"), 1, expenses, expenses / 3),
                rows, List.of(), List.of(), List.of(new AccountSheet.StaffRow(UUID.randomUUID(), "Sunil More", new BigDecimal("5000.00"),
                new BigDecimal("2501.00"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("-2499.00"))));
    }

    @Test
    void pdfHasTotalsAndPaginates() throws Exception {
        byte[] pdf = AccountSheetPdf.render(sample(60));
        try (var doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(1);
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Campaign account sheet").contains("School books for 200 children").contains("EXP/2026-27/0060")
                    .contains("PROVISIONAL").contains("Approved by").contains("Page 1 of");
            assertThat(text).containsAnyOf("75,030.00");
        }
    }

    @Test
    void workbookHasFourSheets() throws Exception {
        byte[] xlsx = AccountSheetXlsx.render(sample(5));
        // Read via the central directory (as Excel does); fastexcel streams entries with data descriptors.
        var tmp = java.nio.file.Files.createTempFile("sheet", ".xlsx");
        java.nio.file.Files.write(tmp, xlsx);
        List<String> entries = new ArrayList<>();
        try (var zip = new java.util.zip.ZipFile(tmp.toFile())) {
            zip.stream().forEach(e -> entries.add(e.getName()));
        } finally {
            java.nio.file.Files.deleteIfExists(tmp);
        }
        assertThat(entries).contains("xl/workbook.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet4.xml");
    }
}
