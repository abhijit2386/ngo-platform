package org.ngoplatform.subscription.app;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.ngoplatform.commons.money.Money;

/**
 * Pure pricing rules (spec §6, §7). All prices include GST. BigDecimal only; rounding HALF_UP to paise at the end
 * of each line so the sum of lines equals the charged amount.
 */
public final class Pricing {
    public enum Cycle { QUARTERLY, ANNUAL }

    public record Settings(BigDecimal annualDiscountPercent, BigDecimal blockMonthlyPrice, int blockSize,
                           BigDecimal gstPercent) {
        public static Settings defaults() {
            return new Settings(new BigDecimal("10"), Money.of(990), 10, new BigDecimal("18"));
        }
    }

    public record Line(String code, String description, BigDecimal amount) {}

    public record Quote(List<Line> lines, BigDecimal total, BigDecimal taxable, BigDecimal gst) {}

    private final Settings settings;

    public Pricing(Settings settings) { this.settings = settings; }

    public static int months(Cycle c) { return c == Cycle.ANNUAL ? 12 : 3; }

    /** Full price of one billing period for a plan, given its monthly-equivalent price. */
    public BigDecimal periodPrice(BigDecimal monthly, Cycle cycle) {
        BigDecimal gross = monthly.multiply(BigDecimal.valueOf(months(cycle)));
        if (cycle == Cycle.ANNUAL) {
            BigDecimal factor = BigDecimal.ONE.subtract(settings.annualDiscountPercent().movePointLeft(2));
            gross = gross.multiply(factor);
        }
        return Money.round(gross);
    }

    public BigDecimal blockPeriodPrice(Cycle cycle) { return periodPrice(settings.blockMonthlyPrice(), cycle); }

    /** Remaining fraction of the period measured in whole days, from 'today' (inclusive) to 'end' (exclusive). */
    public static BigDecimal remainingFraction(LocalDate start, LocalDate end, LocalDate today) {
        long total = ChronoUnit.DAYS.between(start, end);
        if (total <= 0) throw new IllegalArgumentException("Empty billing period");
        long remaining = ChronoUnit.DAYS.between(today.isBefore(start) ? start : today, end);
        if (remaining <= 0) return BigDecimal.ZERO;
        if (remaining > total) remaining = total;
        return BigDecimal.valueOf(remaining).divide(BigDecimal.valueOf(total), 10, RoundingMode.HALF_UP);
    }

    /**
     * Upgrade charge = (new plan period price - contracted current period price) x remaining fraction.
     * Never negative: a higher plan never costs less than the contracted one, and downgrades are not refunded.
     */
    public BigDecimal upgradeDifference(BigDecimal contractedCurrent, BigDecimal newPeriodPrice, BigDecimal remaining) {
        BigDecimal diff = newPeriodPrice.subtract(contractedCurrent);
        if (diff.signum() <= 0) return Money.ZERO;
        return Money.round(diff.multiply(remaining));
    }

    public BigDecimal proratedBlocks(int blocks, Cycle cycle, BigDecimal remaining) {
        if (blocks <= 0) return Money.ZERO;
        return Money.round(blockPeriodPrice(cycle).multiply(BigDecimal.valueOf(blocks)).multiply(remaining));
    }

    public Quote quote(List<Line> lines) {
        BigDecimal total = lines.stream().map(Line::amount).reduce(Money.ZERO, BigDecimal::add);
        return new Quote(List.copyOf(lines), total, taxable(total), total.subtract(taxable(total)));
    }

    /** GST-inclusive split: taxable = total / (1 + rate). */
    public BigDecimal taxable(BigDecimal total) {
        BigDecimal divisor = BigDecimal.ONE.add(settings.gstPercent().movePointLeft(2));
        return total.divide(divisor, Money.SCALE, RoundingMode.HALF_UP);
    }

    public Settings settings() { return settings; }

    public static List<Line> lines() { return new ArrayList<>(); }
}
