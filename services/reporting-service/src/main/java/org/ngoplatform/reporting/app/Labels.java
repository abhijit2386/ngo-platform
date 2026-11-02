package org.ngoplatform.reporting.app;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** English labels for exports (the app shows its own translated labels). */
public final class Labels {
    private Labels() {}

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    public static String month(String key) {
        try { return MONTH.format(YearMonth.parse(key)); } catch (RuntimeException e) { return key; }
    }

    public static String channel(String key) {
        if (key == null) return "";
        return switch (key) {
            case "ONLINE" -> "Online";
            case "CASH" -> "Cash";
            case "CHEQUE" -> "Cheque";
            case "BANK_TRANSFER" -> "Bank transfer";
            case "UPI_DIRECT" -> "UPI (direct)";
            case "IN_KIND_VALUE" -> "In kind";
            default -> key;
        };
    }

    public static String campaignStatus(String key) {
        if (key == null) return "";
        return switch (key) {
            case "PUBLISHED" -> "Live";
            case "CLOSED" -> "Closed";
            case "DRAFT" -> "Draft";
            case "PENDING_APPROVAL" -> "Awaiting approval";
            case "ARCHIVED" -> "Archived";
            default -> key;
        };
    }
}
