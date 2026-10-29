package org.ngoplatform.identity.app;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;

/** Age rules. Ages are counted on the Indian calendar date, so someone turning 18 today is an adult today. */
public final class Ages {
    public static final int ADULT = 18;
    public static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    static final LocalDate EARLIEST = LocalDate.of(1900, 1, 1);

    private Ages() {}

    public static LocalDate today(Clock clock) { return LocalDate.now(clock.withZone(INDIA)); }

    public static int years(LocalDate birthDate, LocalDate today) { return Period.between(birthDate, today).getYears(); }

    public static boolean isAdult(LocalDate birthDate, LocalDate today) { return years(birthDate, today) >= ADULT; }

    /** Plausible date of birth: not in the future and not before 1900. */
    public static boolean isPlausible(LocalDate birthDate, LocalDate today) {
        return birthDate != null && !birthDate.isAfter(today) && !birthDate.isBefore(EARLIEST);
    }
}
