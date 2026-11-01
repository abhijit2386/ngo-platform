package org.ngoplatform.receipt.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.ngoplatform.commons.error.ApiException;

class ReceiptRulesTest {

    @Test
    void numbersRestartEveryIndianFinancialYear() {
        assertThat(ReceiptRules.fiscalYear(LocalDate.of(2026, 3, 31))).isEqualTo("2025-26");
        assertThat(ReceiptRules.fiscalYear(LocalDate.of(2026, 4, 1))).isEqualTo("2026-27");
        assertThat(ReceiptRules.fiscalYear(LocalDate.of(2099, 12, 1))).isEqualTo("2099-00");
        assertThat(ReceiptRules.number("ASF", "2026-27", 124)).isEqualTo("ASF/2026-27/000124");
    }

    @Test
    void prefixFromTheNgoNameAndValidated() {
        assertThat(ReceiptRules.defaultPrefix("Asha Seva Foundation")).isEqualTo("ASF");
        assertThat(ReceiptRules.defaultPrefix("Sneh-Seva Trust (Pune)")).isEqualTo("SSTP");
        assertThat(ReceiptRules.defaultPrefix("X")).isEqualTo("RCPT");
        assertThat(ReceiptRules.defaultPrefix(null)).isEqualTo("RCPT");
        assertThat(ReceiptRules.prefix(" asf ")).isEqualTo("ASF");
        assertThatThrownBy(() -> ReceiptRules.prefix("A/B")).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("INVALID_PREFIX"));
        assertThatThrownBy(() -> ReceiptRules.prefix("TOOLONGPREFIX")).isInstanceOf(ApiException.class);
    }

    @Test
    void indianDigitGrouping() {
        assertThat(ReceiptRules.inr(new BigDecimal("0"))).isEqualTo("0.00");
        assertThat(ReceiptRules.inr(new BigDecimal("999"))).isEqualTo("999.00");
        assertThat(ReceiptRules.inr(new BigDecimal("1000"))).isEqualTo("1,000.00");
        assertThat(ReceiptRules.inr(new BigDecimal("12500"))).isEqualTo("12,500.00");
        assertThat(ReceiptRules.inr(new BigDecimal("123456.5"))).isEqualTo("1,23,456.50");
        assertThat(ReceiptRules.inr(new BigDecimal("12345678.999"))).isEqualTo("1,23,45,679.00");
    }

    @Test
    void amountInWordsUsesLakhAndCrore() {
        assertThat(AmountInWords.of(new BigDecimal("12500"))).isEqualTo("Rupees Twelve Thousand Five Hundred Only");
        assertThat(AmountInWords.of(new BigDecimal("150000.50"))).isEqualTo("Rupees One Lakh Fifty Thousand and Fifty Paise Only");
        assertThat(AmountInWords.of(new BigDecimal("21000000"))).isEqualTo("Rupees Two Crore Ten Lakh Only");
    }

    @Test
    void verificationShowsInitialsOnly() {
        assertThat(ReceiptRules.maskName("Meera Shah")).isEqualTo("M*** S***");
        assertThat(ReceiptRules.maskName("मीरा शाह")).isEqualTo("म*** श***");
        assertThat(ReceiptRules.verifyCode()).matches("[A-HJKMNP-Z2-9]{10}");
    }

    @Test
    void eightyGOnlyWhenVerifiedValidAskedAndPanGiven() {
        LocalDate d = LocalDate.of(2026, 11, 1);
        assertThat(ReceiptRules.eligible80g(true, d, true, "ABCDE1234F", d)).isTrue();
        assertThat(ReceiptRules.eligible80g(true, d.minusDays(1), true, "ABCDE1234F", d)).isFalse();
        assertThat(ReceiptRules.eligible80g(false, d, true, "ABCDE1234F", d)).isFalse();
        assertThat(ReceiptRules.eligible80g(true, d, false, "ABCDE1234F", d)).isFalse();
        assertThat(ReceiptRules.eligible80g(true, d, true, null, d)).isFalse();
    }

    @Test
    void reasonsAndSignatoryNamesValidated() {
        assertThatThrownBy(() -> ReceiptRules.reason("typo")).isInstanceOf(ApiException.class);
        assertThat(ReceiptRules.reason("  Donor  name was misspelt ")).isEqualTo("Donor name was misspelt");
        assertThatThrownBy(() -> ReceiptSettings.designation("")).isInstanceOf(ApiException.class);
        assertThat(ReceiptSettings.name("Asha Patil")).isEqualTo("Asha Patil");
    }
}
