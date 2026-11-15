package com.nexus.banking.contract.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Money rules shared by server validation and the UIs: scale 2, strictly positive, bounded. */
public final class Money {
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000.00");      // 1 billion per operation
    public static final BigDecimal MAX_BALANCE = new BigDecimal("9999999999999.99");

    private Money() {
    }

    public static BigDecimal normalize(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.UNNECESSARY);
    }

    /** Null when valid, otherwise a short reason (Vietnamese, shown to the customer). */
    public static String validateAmount(BigDecimal amount) {
        if (amount == null) return "Số tiền không hợp lệ";
        if (amount.signum() <= 0) return "Số tiền phải lớn hơn 0";
        if (amount.stripTrailingZeros().scale() > 2) return "Số tiền chỉ được có tối đa 2 chữ số thập phân";
        if (amount.compareTo(MAX_AMOUNT) > 0) return "Số tiền mỗi giao dịch tối đa " + format(MAX_AMOUNT);
        return null;
    }

    /** Parses user input such as "1.500.000", "1,500,000" or "1500000"; returns null when it is not a number. */
    public static BigDecimal parse(String text) {
        if (text == null) return null;
        String t = text.trim().replace("₫", "").replace("VND", "").replace("vnd", "").replace(" ", "");
        if (t.isEmpty()) return null;
        // Vietnamese grouping uses '.', so a trailing ",dd" is the decimal part; otherwise separators are grouping.
        if (t.matches("^\\d{1,3}(\\.\\d{3})+(,\\d{1,2})?$")) t = t.replace(".", "").replace(',', '.');
        else if (t.matches("^\\d{1,3}(,\\d{3})+(\\.\\d{1,2})?$")) t = t.replace(",", "");
        else if (t.matches("^\\d+,\\d{1,2}$")) t = t.replace(',', '.');
        if (!t.matches("^\\d+(\\.\\d{1,2})?$")) return null;
        try {
            return new BigDecimal(t).setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }

    /** "50.000.000 ₫" (decimals only when present). */
    public static String format(BigDecimal v) {
        if (v == null) return "-";
        DecimalFormatSymbols sy = new DecimalFormatSymbols(Locale.ROOT);
        sy.setGroupingSeparator('.');
        sy.setDecimalSeparator(',');
        boolean whole = v.stripTrailingZeros().scale() <= 0;
        DecimalFormat f = new DecimalFormat(whole ? "#,##0" : "#,##0.00", sy);
        return f.format(v) + " ₫";
    }

    /** "+1.000.000 ₫" / "-500.000 ₫". */
    public static String formatSigned(BigDecimal v) {
        if (v == null) return "-";
        return (v.signum() > 0 ? "+" : "") + format(v);
    }
}
