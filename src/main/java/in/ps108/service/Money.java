package in.ps108.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {
    public static final BigDecimal ZERO = new BigDecimal("0.00");
    private Money() {}
    public static BigDecimal round(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP); }
    public static BigDecimal pct(BigDecimal base, String percent) {
        return round(base.multiply(new BigDecimal(percent)).divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP));
    }
    public static String fmt(BigDecimal v) { return "Rs " + round(v).toPlainString(); }
}
