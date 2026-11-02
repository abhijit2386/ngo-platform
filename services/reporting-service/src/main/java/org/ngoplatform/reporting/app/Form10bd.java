package org.ngoplatform.reporting.app;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Builds the 80G register (one line per donation) and Form 10BD data (Rule 18AB: one line per donor, donation type
 * and mode of receipt). Yogdaan does not file the form: the NGO's accountant checks the lines and uploads them on the
 * income-tax portal. Lines that need attention carry a note instead of being dropped.
 */
public final class Form10bd {
    private Form10bd() {}

    public static final String SECTION = "Section 80G";
    public static final String TYPE_OTHERS = "Others";
    public static final String MODE_CASH = "Cash", MODE_KIND = "Kind", MODE_ELECTRONIC = "Electronic modes including account payee cheque/draft";
    private static final Pattern PAN = Pattern.compile("[A-Z]{5}[0-9]{4}[A-Z]");
    private static final BigDecimal CASH_LIMIT = new BigDecimal("2000");

    public record RegisterLine(String donationId, java.time.Instant paidAt, String receiptNumber, String donorName, String pan,
                               String address, String mode, BigDecimal amount, String campaignTitle) {}

    public record Line(int sr, String idCode, String idNumber, String section, String name, String address,
                       String donationType, String mode, BigDecimal amount, int donations, String receiptNumbers, String note) {}

    /** The 10BD mode of receipt for a donation channel. */
    public static String mode(String channel) {
        if (channel == null) return MODE_ELECTRONIC;
        return switch (channel) {
            case "CASH" -> MODE_CASH;
            case "IN_KIND_VALUE" -> MODE_KIND;
            default -> MODE_ELECTRONIC; // ONLINE, CHEQUE, BANK_TRANSFER, UPI_DIRECT
        };
    }

    public static String normalisePan(String pan) {
        return pan == null ? null : pan.trim().toUpperCase(Locale.ROOT);
    }

    public static boolean validPan(String pan) { return pan != null && PAN.matcher(pan).matches(); }

    /**
     * Donations with an 80G receipt, plus those whose receipt is not issued yet (flagged); a donation whose receipt is
     * not an 80G receipt (the NGO had no valid 80G on that date) is left out. The receipt decides, not a donor choice.
     */
    public static List<RegisterLine> register(List<Data.TaxRow> rows, Data.ReceiptFacts receipts) {
        java.util.Set<String> plain = new java.util.HashSet<>(receipts.plain() == null ? List.of() : receipts.plain());
        Map<String, String> numbers = receipts.eightyG() == null ? Map.of() : receipts.eightyG();
        List<RegisterLine> out = new ArrayList<>();
        for (Data.TaxRow r : rows) {
            if (plain.contains(r.donationId())) continue;
            out.add(new RegisterLine(r.donationId(), r.paidAt(), numbers.get(r.donationId()), r.donorName(),
                    normalisePan(r.pan()), r.address(), mode(r.channel()), r.amount(), r.campaignTitle()));
        }
        return out;
    }

    /** Groups the register by donor (PAN, else name) and mode, in first-donation order. */
    public static List<Line> lines(List<RegisterLine> register) {
        Map<String, List<RegisterLine>> groups = new LinkedHashMap<>();
        for (RegisterLine r : register) {
            if (r.amount() == null || r.amount().signum() <= 0) continue;
            String who = validPan(r.pan()) ? r.pan() : "NAME:" + (r.donorName() == null ? "" : r.donorName().trim().toLowerCase(Locale.ROOT));
            groups.computeIfAbsent(who + "|" + r.mode(), k -> new ArrayList<>()).add(r);
        }
        List<Line> out = new ArrayList<>();
        int sr = 0;
        for (List<RegisterLine> g : groups.values()) {
            RegisterLine first = g.get(0);
            BigDecimal total = g.stream().map(RegisterLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            List<String> receipts = g.stream().map(RegisterLine::receiptNumber).filter(n -> n != null && !n.isBlank()).distinct().toList();
            boolean pan = validPan(first.pan());
            out.add(new Line(++sr, pan ? "PAN" : "", pan ? first.pan() : "", SECTION, first.donorName(), first.address(),
                    TYPE_OTHERS, first.mode(), total, g.size(), String.join(", ", receipts), note(g, pan, receipts.size() < g.size())));
        }
        return out;
    }

    private static String note(List<RegisterLine> g, boolean pan, boolean receiptMissing) {
        RegisterLine r = g.get(0);
        List<String> n = new ArrayList<>();
        if (!pan) n.add("PAN missing or invalid: add it before filing");
        // Section 80G(5D): a cash donation above Rs. 2,000 gets no deduction (judged per donation, not per donor).
        long bigCash = MODE_CASH.equals(r.mode()) ? g.stream().filter(x -> x.amount().compareTo(CASH_LIMIT) > 0).count() : 0;
        if (bigCash > 0) n.add(bigCash + " cash donation(s) above Rs. 2,000: not eligible for 80G");
        if (MODE_KIND.equals(r.mode())) n.add("Donations in kind are not eligible for 80G");
        if (receiptMissing) n.add("Receipt not issued yet");
        return String.join("; ", n);
    }
}
