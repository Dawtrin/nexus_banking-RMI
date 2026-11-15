package com.nexus.banking.contract.enums;

import java.io.Serializable;

/**
 * Ledger entry type. A transfer is recorded as TWO entries (double entry): {@link #TRANSFER} is the debit leg on
 * the source account and {@link #TRANSFER_IN} is the credit leg on the destination account.
 */
public enum TransactionType implements Serializable {
    DEPOSIT(true, "Nạp tiền"),
    WITHDRAW(false, "Rút tiền"),
    TRANSFER(false, "Chuyển khoản đi"),
    TRANSFER_IN(true, "Nhận chuyển khoản");

    private final boolean credit;
    private final String label;

    TransactionType(boolean credit, String label) {
        this.credit = credit;
        this.label = label;
    }

    /** True when the entry increases the account balance. */
    public boolean isCredit() { return credit; }

    /** Entries that count against the daily outgoing limit. */
    public boolean isOutgoing() { return !credit; }

    public String getLabel() { return label; }
}
