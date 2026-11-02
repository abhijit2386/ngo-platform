package org.ngoplatform.campaign.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.ngoplatform.commons.error.ApiException;

class ProposalRulesTest {
    static final LocalDate TODAY = LocalDate.of(2026, 11, 2);

    @Test
    void inputsAreChecked() {
        assertThat(ProposalRules.ask(new BigDecimal("250000"))).isEqualByComparingTo("250000.00");
        assertThatThrownBy(() -> ProposalRules.ask(BigDecimal.ZERO)).isInstanceOf(ApiException.class);
        assertThat(ProposalRules.email(" CSR@Sahyadri.example ")).isEqualTo("csr@sahyadri.example");
        assertThat(ProposalRules.email("")).isNull();
        assertThatThrownBy(() -> ProposalRules.email("csr@")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ProposalRules.months(0)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ProposalRules.schedule("PM_FUND")).isInstanceOf(ApiException.class);
        assertThat(ProposalRules.schedule("EDUCATION")).isEqualTo("EDUCATION");
        assertThatThrownBy(() -> ProposalRules.section("x".repeat(4001), "NEED")).isInstanceOf(ApiException.class);
        assertThat(ProposalRules.number("2026-27", 3)).isEqualTo("PR/2026-27/003");
        assertThat(ProposalRules.scheduleFor("Education")).isEqualTo("EDUCATION");
        assertThat(ProposalRules.scheduleFor("Disaster relief")).isEqualTo("DISASTER");
        assertThat(ProposalRules.scheduleFor(null)).isEqualTo("HEALTH_HUNGER");
    }

    @Test
    void indianDigitGrouping() {
        assertThat(UcRules.group(new BigDecimal("250000"), false)).isEqualTo("2,50,000");
        assertThat(UcRules.group(new BigDecimal("12345678.5"), true)).isEqualTo("1,23,45,678.50");
        assertThat(UcRules.group(new BigDecimal("999"), false)).isEqualTo("999");
        assertThat(UcRules.group(new BigDecimal("1000"), true)).isEqualTo("1,000.00");
    }

    @Test
    void onlyOurOwnPackLinksArePrinted() {
        String base = "http://localhost:8080/";
        assertThat(ProposalRules.packUrl("http://localhost:8080/api/v1/public/csr/Ab3dEf6hIj9kLm2nOp", base))
                .isEqualTo("http://localhost:8080/api/v1/public/csr/Ab3dEf6hIj9kLm2nOp");
        assertThat(ProposalRules.packUrl(" ", base)).isNull();
        assertThatThrownBy(() -> ProposalRules.packUrl("https://evil.example/api/v1/public/csr/Ab3dEf6hIj9kLm2nOp", base))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ProposalRules.packUrl("http://localhost:8080/api/v1/public/csr/../x", base)).isInstanceOf(ApiException.class);
    }

    @Test
    void sendingNeedsTheStoryAndSensibleDates() {
        assertThat(ProposalRules.missingForSending("short", "", null, "x".repeat(25))).hasSize(3);
        assertThat(ProposalRules.missingForSending("x".repeat(30), "y".repeat(20), "z".repeat(20), "w".repeat(20))).isEmpty();
        ProposalRules.sentOn(TODAY, TODAY.plusDays(14), TODAY);
        assertThatThrownBy(() -> ProposalRules.sentOn(TODAY.plusDays(1), null, TODAY)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ProposalRules.sentOn(TODAY, TODAY.minusDays(1), TODAY)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ProposalRules.funded(new BigDecimal("100000"), TODAY.minusDays(5), TODAY.minusDays(1), TODAY))
                .isInstanceOf(ApiException.class).hasMessageContaining("before the proposal was sent");
    }

    @Test
    void statusChanges() {
        ProposalRules.canClose("SENT", "FUNDED");
        ProposalRules.canClose("DRAFT", "WITHDRAWN");
        assertThatThrownBy(() -> ProposalRules.canClose("DRAFT", "FUNDED")).isInstanceOf(ApiException.class).hasMessageContaining("sent first");
        assertThatThrownBy(() -> ProposalRules.canClose("FUNDED", "WITHDRAWN")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ProposalRules.canClose("SENT", "MAYBE")).isInstanceOf(ApiException.class);
    }

    @Test
    void pipelineSummary() {
        var s = ProposalRules.summary(List.of(
                new ProposalRules.Row("DRAFT", new BigDecimal("50000"), null, null),
                new ProposalRules.Row("SENT", new BigDecimal("200000"), null, TODAY),
                new ProposalRules.Row("SENT", new BigDecimal("100000"), null, TODAY.plusDays(3)),
                new ProposalRules.Row("FUNDED", new BigDecimal("150000"), new BigDecimal("120000"), null),
                new ProposalRules.Row("DECLINED", new BigDecimal("80000"), null, null),
                new ProposalRules.Row("DECLINED", new BigDecimal("80000"), null, null),
                new ProposalRules.Row("WITHDRAWN", new BigDecimal("1000"), null, null)), TODAY);
        assertThat(s.drafts()).isEqualTo(1);
        assertThat(s.open()).isEqualTo(2);
        assertThat(s.askedOpen()).isEqualByComparingTo("300000");
        assertThat(s.fundedTotal()).isEqualByComparingTo("120000");
        assertThat(s.successPercent()).isEqualTo(33);
        assertThat(s.followUpsDue()).isEqualTo(1);
        assertThat(ProposalRules.summary(List.of(), TODAY).successPercent()).isNull();
    }
}
