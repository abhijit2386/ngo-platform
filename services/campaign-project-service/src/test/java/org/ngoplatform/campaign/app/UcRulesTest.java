package org.ngoplatform.campaign.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.ngoplatform.commons.error.ApiException;

class UcRulesTest {
    static final LocalDate TODAY = LocalDate.of(2026, 11, 2);

    static UcRules.Line line(String head, String amount, int bills) {
        return new UcRules.Line("EXP/2026-27/0001", TODAY.minusDays(3), head, "Text books", "Vendor", new BigDecimal(amount), bills);
    }

    @Test
    void figuresGroupedByBudgetHeadWithUnspentBalance() {
        Map<String, BigDecimal> budget = new LinkedHashMap<>();
        budget.put("Books", new BigDecimal("30000"));
        budget.put("Transport", new BigDecimal("5000"));
        budget.put("Printing", new BigDecimal("2000"));
        var f = UcRules.figures(new BigDecimal("50000"), budget,
                List.of(line("Books", "12000", 1), line("books", "8000", 1), line("Transport", "1500", 0), line("Snacks", "500", 1)),
                new BigDecimal("52000"));
        assertThat(f.spent()).isEqualByComparingTo("22000");
        assertThat(f.utilised()).isEqualByComparingTo("22000");
        assertThat(f.unspent()).isEqualByComparingTo("28000");
        assertThat(f.ownFunds()).isEqualByComparingTo("0");
        assertThat(f.withoutBill()).isEqualTo(1);
        assertThat(f.heads()).extracting(UcRules.Head::head).containsExactly("Books", "Transport", "Printing", "Snacks");
        assertThat(f.heads().getFirst().spent()).isEqualByComparingTo("20000");
        assertThat(f.heads().getFirst().count()).isEqualTo(2);
        assertThat(f.heads().get(3).budget()).isNull();
    }

    @Test
    void spendingAboveTheGrantComesFromOwnFunds() {
        var f = UcRules.figures(new BigDecimal("10000"), Map.of(), List.of(line("Books", "12500.50", 1)), BigDecimal.ZERO);
        assertThat(f.utilised()).isEqualByComparingTo("10000");
        assertThat(f.unspent()).isEqualByComparingTo("0");
        assertThat(f.ownFunds()).isEqualByComparingTo("2500.50");
        String s = UcRules.statement("Asha Seva Foundation", "Sahyadri CSR Foundation", "SCF/2026/14", "School books", TODAY.minusMonths(3),
                TODAY, TODAY.minusMonths(3), f);
        assertThat(s).contains("Rs 10,000.00", "Sahyadri CSR Foundation", "SCF/2026/14", "own funds", "Rs 2,500.50").doesNotContain("unutilised");
    }

    @Test
    void caIdentifiersAreChecked() {
        var ca = UcRules.ca(" Meena Joshi ", "123 456", "Joshi & Co", "012345w", "26123456abcdefghij", "Pune", TODAY);
        assertThat(ca.membershipNo()).isEqualTo("123456");
        assertThat(ca.firmRegNo()).isEqualTo("012345W");
        assertThat(ca.udin()).isEqualTo("26123456ABCDEFGHIJ");
        assertThat(UcRules.ca("Meena Joshi", "123456", null, "", "25123456ABCDEFGHIJ", null, TODAY).firmRegNo()).isNull();
        assertThatThrownBy(() -> UcRules.ca("Meena Joshi", "12345", null, null, "26123456ABCDEFGHIJ", null, TODAY))
                .isInstanceOf(ApiException.class).hasMessageContaining("6 digits");
        assertThatThrownBy(() -> UcRules.ca("Meena Joshi", "123456", null, null, "26654321ABCDEFGHIJ", null, TODAY))
                .isInstanceOf(ApiException.class).hasMessageContaining("does not belong");
        assertThatThrownBy(() -> UcRules.ca("Meena Joshi", "123456", null, null, "26123456ABC", null, TODAY))
                .isInstanceOf(ApiException.class).hasMessageContaining("18 characters");
        assertThatThrownBy(() -> UcRules.ca("Meena Joshi", "123456", null, null, "21123456ABCDEFGHIJ", null, TODAY))
                .isInstanceOf(ApiException.class).hasMessageContaining("this year or last year");
        assertThatThrownBy(() -> UcRules.ca("Meena Joshi", "123456", null, "12345", "26123456ABCDEFGHIJ", null, TODAY))
                .isInstanceOf(ApiException.class).hasMessageContaining("firm registration");
    }

    @Test
    void periodAndGrantChecks() {
        assertThat(UcRules.grant(new BigDecimal("50000"))).isEqualByComparingTo("50000.00");
        assertThatThrownBy(() -> UcRules.grant(BigDecimal.ZERO)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> UcRules.grant(new BigDecimal("10.555"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> UcRules.period(TODAY, TODAY.minusDays(1), TODAY, null, TODAY)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> UcRules.period(TODAY.minusDays(5), TODAY.plusDays(1), TODAY, null, TODAY)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> UcRules.period(TODAY.minusDays(5), TODAY.minusDays(4), TODAY, TODAY.minusDays(1), TODAY))
                .isInstanceOf(ApiException.class).hasMessageContaining("before the campaign");
        UcRules.period(TODAY.minusDays(30), TODAY, TODAY.minusDays(30), TODAY.minusDays(60), TODAY);
    }

    @Test
    void numberingAndCodes() {
        assertThat(UcRules.fiscalYear(LocalDate.of(2026, 3, 31))).isEqualTo("2025-26");
        assertThat(UcRules.fiscalYear(LocalDate.of(2026, 4, 1))).isEqualTo("2026-27");
        assertThat(UcRules.number("2026-27", 7)).isEqualTo("UC/2026-27/007");
        String c = UcRules.newCode();
        assertThat(UcRules.validCode(c)).isTrue();
        assertThat(UcRules.validCode("../x")).isFalse();
    }
}
