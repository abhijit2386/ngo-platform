package org.ngoplatform.reporting.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.ngoplatform.commons.error.ApiException;

class PeriodTest {
    @Test
    void defaultsToTheCurrentFinancialYear() {
        Period nov = Period.of(null, null, LocalDate.of(2026, 11, 2));
        assertThat(nov.from()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(nov.to()).isEqualTo(LocalDate.of(2027, 3, 31));
        assertThat(nov.label()).isEqualTo("FY 2026-27");
        Period feb = Period.of(null, null, LocalDate.of(2027, 2, 10));
        assertThat(feb.from()).isEqualTo(LocalDate.of(2026, 4, 1));
    }

    @Test
    void daysAreIndiaTimeWithBothEndsIncluded() {
        Period p = Period.of(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), LocalDate.of(2026, 11, 2));
        assertThat(p.start()).isEqualTo(Instant.parse("2026-03-31T18:30:00Z"));
        assertThat(p.end()).isEqualTo(Instant.parse("2026-04-30T18:30:00Z"));
        assertThat(p.label()).isEqualTo("01 Apr 2026 - 30 Apr 2026");
    }

    @Test
    void rejectsReversedOrVeryLongPeriods() {
        LocalDate today = LocalDate.of(2026, 11, 2);
        assertThatThrownBy(() -> Period.of(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 4, 1), today))
                .isInstanceOf(ApiException.class).hasMessageContaining("before");
        assertThatThrownBy(() -> Period.of(LocalDate.of(2010, 4, 1), LocalDate.of(2026, 4, 1), today))
                .isInstanceOf(ApiException.class).hasMessageContaining("5 years");
    }
}
