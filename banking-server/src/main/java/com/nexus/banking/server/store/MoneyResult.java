package com.nexus.banking.server.store;

import com.nexus.banking.contract.dto.TransactionDTO;

import java.math.BigDecimal;

/**
 * Outcome of a money movement decided INSIDE the atomic store operation (balance check, limit check, idempotency).
 * For a transfer, {@code transaction} is the debit leg and {@code creditLeg} the credit leg.
 */
public record MoneyResult(Outcome outcome, TransactionDTO transaction, TransactionDTO creditLeg, BigDecimal balance,
                          String detail) {

    public enum Outcome {
        OK,
        /** The same request id was already executed: the ORIGINAL transaction is returned, no money moved. */
        DUPLICATE,
        ACCOUNT_NOT_FOUND,
        ACCOUNT_LOCKED,
        COUNTERPARTY_NOT_FOUND,
        COUNTERPARTY_LOCKED,
        INSUFFICIENT_FUNDS,
        LIMIT_EXCEEDED,
        REQUEST_ID_REUSED,
        BALANCE_OVERFLOW
    }

    public static MoneyResult ok(TransactionDTO tx, TransactionDTO credit, BigDecimal balance) {
        return new MoneyResult(Outcome.OK, tx, credit, balance, "");
    }

    public static MoneyResult duplicate(TransactionDTO original) {
        return new MoneyResult(Outcome.DUPLICATE, original, null, original.getBalanceAfter(), "");
    }

    public static MoneyResult rejected(Outcome outcome, String detail) {
        return new MoneyResult(outcome, null, null, null, detail == null ? "" : detail);
    }

    public boolean isSuccess() {
        return outcome == Outcome.OK || outcome == Outcome.DUPLICATE;
    }
}
