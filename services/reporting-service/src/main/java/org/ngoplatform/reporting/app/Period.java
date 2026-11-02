package org.ngoplatform.reporting.app;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.ngoplatform.commons.error.ApiException;

/**
 * A report period of whole days in India time, both ends included. Without dates it is the current financial year
 * (1 April - 31 March), which is also how 80G / Form 10BD figures are filed.
 */
public record Period(LocalDate from, LocalDate to) {
    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    public static Period of(LocalDate from, LocalDate to, LocalDate today) {
        if (from == null && to == null) from = financialYearStart(today);
        if (from == null) from = financialYearStart(to);
        if (to == null) to = financialYearStart(from).plusYears(1).minusDays(1);
        if (to.isBefore(from)) throw ApiException.badRequest("BAD_PERIOD", "The end date is before the start date");
        if (to.isAfter(from.plusYears(5))) throw ApiException.badRequest("PERIOD_TOO_LONG", "Choose a period of at most 5 years");
        return new Period(from, to);
    }

    public static Period currentFinancialYear(LocalDate today) { return of(null, null, today); }

    public static LocalDate financialYearStart(LocalDate d) {
        return LocalDate.of(d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1, 4, 1);
    }

    /** First instant of the period (00:00 India time on the first day). */
    public Instant start() { return from.atStartOfDay(IST).toInstant(); }

    /** First instant after the period (00:00 India time on the day after the last day). */
    public Instant end() { return to.plusDays(1).atStartOfDay(IST).toInstant(); }

    /** "FY 2026-27" for a whole financial year, otherwise "01 Apr 2026 - 30 Jun 2026". */
    public String label() {
        LocalDate fy = financialYearStart(from);
        if (from.equals(fy) && to.equals(fy.plusYears(1).minusDays(1))) {
            return "FY " + fy.getYear() + "-" + String.format("%02d", (fy.getYear() + 1) % 100);
        }
        return LABEL.format(from) + " - " + LABEL.format(to);
    }
}
