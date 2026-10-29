package org.ngoplatform.receipt.app;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Indian-system amount in words: "Rupees One Lakh Twenty Thousand and Fifty Paise Only". */
public final class AmountInWords {
    private static final String[] ONES = {"", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
            "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen"};
    private static final String[] TENS = {"", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"};

    private AmountInWords() {}

    public static String of(BigDecimal amount) {
        BigDecimal a = amount.setScale(2, RoundingMode.HALF_UP);
        long rupees = a.longValue();
        int paise = a.remainder(BigDecimal.ONE).movePointRight(2).intValue();
        StringBuilder sb = new StringBuilder("Rupees ");
        sb.append(rupees == 0 ? "Zero" : words(rupees));
        if (paise > 0) sb.append(" and ").append(below100(paise)).append(" Paise");
        return sb.append(" Only").toString();
    }

    static String words(long n) {
        StringBuilder sb = new StringBuilder();
        long crore = n / 10_000_000; n %= 10_000_000;
        long lakh = n / 100_000; n %= 100_000;
        long thousand = n / 1000; n %= 1000;
        long hundred = n / 100; n %= 100;
        if (crore > 0) sb.append(words(crore)).append(" Crore ");
        if (lakh > 0) sb.append(below100((int) lakh)).append(" Lakh ");
        if (thousand > 0) sb.append(below100((int) thousand)).append(" Thousand ");
        if (hundred > 0) sb.append(ONES[(int) hundred]).append(" Hundred ");
        if (n > 0) sb.append(below100((int) n));
        return sb.toString().trim();
    }

    static String below100(int n) {
        if (n < 20) return ONES[n];
        return (TENS[n / 10] + " " + ONES[n % 10]).trim();
    }
}
