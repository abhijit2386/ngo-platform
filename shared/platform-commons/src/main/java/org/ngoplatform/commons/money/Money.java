package org.ngoplatform.commons.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** INR money helpers. All amounts are BigDecimal with scale 2; never floating point. */
public final class Money {
    public static final int SCALE = 2;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE);

    private Money() {}

    public static BigDecimal of(String v) { return new BigDecimal(v).setScale(SCALE, RoundingMode.UNNECESSARY); }
    public static BigDecimal of(long rupees) { return BigDecimal.valueOf(rupees).setScale(SCALE); }
    public static BigDecimal round(BigDecimal v) { return v.setScale(SCALE, RoundingMode.HALF_UP); }

    /** Validates a user-entered amount: positive, at most 2 decimals, within bounds. */
    public static boolean isValidAmount(BigDecimal v, BigDecimal min, BigDecimal max) {
        if (v == null || v.signum() <= 0) return false;
        if (v.stripTrailingZeros().scale() > SCALE) return false;
        return v.compareTo(min) >= 0 && v.compareTo(max) <= 0;
    }
}
