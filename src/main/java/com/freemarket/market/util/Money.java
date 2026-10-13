package com.freemarket.market.util;

import java.math.*;

/** 所有账务以百分之一为整数单位，浮点数仅用于图表。 */
public final class Money {
    public static final long MAX = 1_000_000_000_000_000L;
    private Money() {}
    public static long parse(String value) {
        try {
            String clean=value.trim();
            if(!clean.matches("[0-9]{1,18}(\\.[0-9]{1,2})?")) throw new IllegalArgumentException("msg.number");
            long cents = new BigDecimal(clean).movePointRight(2).longValueExact();
            if (cents < 0 || cents > MAX) throw new IllegalArgumentException("msg.number");
            return cents;
        } catch (ArithmeticException | NumberFormatException e) { throw new IllegalArgumentException("msg.number", e); }
    }
    public static String format(long cents) { return BigDecimal.valueOf(cents, 2).toPlainString(); }
    public static long total(long unit, int amount) {
        long result = Math.multiplyExact(unit, amount);
        if (unit <= 0 || amount <= 0 || result > MAX) throw new IllegalArgumentException("msg.number");
        return result;
    }
    public static long tax(long total, int bps) {
        if (bps < 0 || bps > 10000) throw new IllegalArgumentException("msg.number");
        return BigDecimal.valueOf(total).multiply(BigDecimal.valueOf(bps))
                .divide(BigDecimal.valueOf(10000), 0, RoundingMode.HALF_UP).longValueExact();
    }
}
