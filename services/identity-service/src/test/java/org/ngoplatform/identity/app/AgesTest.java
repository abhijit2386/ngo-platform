package org.ngoplatform.identity.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AgesTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 29);

    @Test
    void eighteenthBirthdayTodayIsAdult() {
        assertThat(Ages.isAdult(LocalDate.of(2008, 10, 29), TODAY)).isTrue();
    }

    @Test
    void dayBeforeEighteenthBirthdayIsMinor() {
        assertThat(Ages.isAdult(LocalDate.of(2008, 10, 30), TODAY)).isFalse();
    }

    @Test
    void seventeenIsMinor() {
        assertThat(Ages.years(LocalDate.of(2009, 1, 1), TODAY)).isEqualTo(17);
        assertThat(Ages.isAdult(LocalDate.of(2009, 1, 1), TODAY)).isFalse();
    }

    @Test
    void leapDayBirthdayBecomesAdultOnFirstMarchInNonLeapYear() {
        LocalDate born = LocalDate.of(2008, 2, 29);
        assertThat(Ages.isAdult(born, LocalDate.of(2026, 2, 28))).isFalse();
        assertThat(Ages.isAdult(born, LocalDate.of(2026, 3, 1))).isTrue();
    }

    @Test
    void elderlyDonorIsAdult() {
        assertThat(Ages.isAdult(LocalDate.of(1935, 6, 1), TODAY)).isTrue();
    }

    @Test
    void todayUsesIndianDateNotUtc() {
        // 20:00 UTC on 28 Oct is 01:30 IST on 29 Oct.
        Clock c = Clock.fixed(Instant.parse("2026-10-28T20:00:00Z"), ZoneOffset.UTC);
        assertThat(Ages.today(c)).isEqualTo(LocalDate.of(2026, 10, 29));
    }

    @Test
    void futureDateIsImplausible() {
        assertThat(Ages.isPlausible(TODAY.plusDays(1), TODAY)).isFalse();
    }

    @Test
    void before1900IsImplausible() {
        assertThat(Ages.isPlausible(LocalDate.of(1899, 12, 31), TODAY)).isFalse();
        assertThat(Ages.isPlausible(LocalDate.of(1900, 1, 1), TODAY)).isTrue();
    }

    @Test
    void nullIsImplausible() {
        assertThat(Ages.isPlausible(null, TODAY)).isFalse();
    }

    @Test
    void bornTodayIsPlausibleButMinor() {
        assertThat(Ages.isPlausible(TODAY, TODAY)).isTrue();
        assertThat(Ages.isAdult(TODAY, TODAY)).isFalse();
    }
}
