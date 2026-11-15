package com.nexus.banking.contract.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyTest {

    @Test
    void parsesVietnameseAndEnglishGrouping() {
        assertThat(Money.parse("1.500.000")).isEqualByComparingTo("1500000");
        assertThat(Money.parse("1,500,000")).isEqualByComparingTo("1500000");
        assertThat(Money.parse("1500000")).isEqualByComparingTo("1500000");
        assertThat(Money.parse("1.500.000,50")).isEqualByComparingTo("1500000.50");
        assertThat(Money.parse(" 200 000 ₫ ")).isEqualByComparingTo("200000");
        assertThat(Money.parse("12,5")).isEqualByComparingTo("12.5");
    }

    @Test
    void rejectsGarbage() {
        assertThat(Money.parse("")).isNull();
        assertThat(Money.parse("abc")).isNull();
        assertThat(Money.parse("1.2.3")).isNull();
        assertThat(Money.parse("-5")).isNull();
        assertThat(Money.parse("1.234567")).isNull();
    }

    @Test
    void validatesAmountRules() {
        assertThat(Money.validateAmount(new BigDecimal("100000.00"))).isNull();
        assertThat(Money.validateAmount(null)).isNotNull();
        assertThat(Money.validateAmount(BigDecimal.ZERO)).isNotNull();
        assertThat(Money.validateAmount(new BigDecimal("-1"))).isNotNull();
        assertThat(Money.validateAmount(new BigDecimal("1.005"))).isNotNull();
        assertThat(Money.validateAmount(new BigDecimal("1000000000.01"))).isNotNull();
    }

    @Test
    void formatsWholeAmountsWithoutDecimals() {
        assertThat(Money.format(new BigDecimal("50000000.00"))).isEqualTo("50.000.000 ₫");
        assertThat(Money.format(new BigDecimal("1234.50"))).isEqualTo("1.234,50 ₫");
        assertThat(Money.formatSigned(new BigDecimal("1000.00"))).isEqualTo("+1.000 ₫");
        assertThat(Money.formatSigned(new BigDecimal("-1000.00"))).isEqualTo("-1.000 ₫");
    }

    @Test
    void masksAccountNumbers() {
        assertThat(AccountDTO.mask("1001000001")).isEqualTo("1001 **** 0001");
    }
}
