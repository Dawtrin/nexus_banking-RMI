package com.nexus.banking.contract.dto;

import com.nexus.banking.contract.enums.AccountStatus;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** A bank account as seen by ONE node. The balance converges between nodes (eventual consistency). */
public class AccountDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String accountNumber;
    private final String ownerName;
    private final BigDecimal balance;
    private final String branchId;
    private final AccountStatus status;
    private final Instant createdAt;
    private final BigDecimal dailyLimit;

    public AccountDTO(String accountNumber, String ownerName, BigDecimal balance, String branchId,
                      AccountStatus status, Instant createdAt, BigDecimal dailyLimit) {
        this.accountNumber = Objects.requireNonNull(accountNumber, "accountNumber");
        this.ownerName = Objects.requireNonNull(ownerName, "ownerName");
        this.balance = Objects.requireNonNull(balance, "balance");
        this.branchId = branchId == null ? "" : branchId;
        this.status = status == null ? AccountStatus.ACTIVE : status;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.dailyLimit = dailyLimit == null ? new BigDecimal("20000000.00") : dailyLimit;
    }

    public String getAccountNumber() { return accountNumber; }
    public String getOwnerName() { return ownerName; }
    public BigDecimal getBalance() { return balance; }
    public String getBranchId() { return branchId; }
    public AccountStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    /** Maximum total of outgoing money (withdrawals + transfers) per calendar day. */
    public BigDecimal getDailyLimit() { return dailyLimit; }
    public boolean isActive() { return status == AccountStatus.ACTIVE; }

    /** "1001 **** 0001" style mask for screens that must not show the full number. */
    public static String mask(String accountNumber) {
        if (accountNumber == null || accountNumber.length() < 8) return accountNumber == null ? "" : accountNumber;
        return accountNumber.substring(0, 4) + " **** " + accountNumber.substring(accountNumber.length() - 4);
    }

    public AccountDTO withBalance(BigDecimal newBalance) {
        return new AccountDTO(accountNumber, ownerName, newBalance, branchId, status, createdAt, dailyLimit);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof AccountDTO a && accountNumber.equals(a.accountNumber));
    }

    @Override
    public int hashCode() { return accountNumber.hashCode(); }

    @Override
    public String toString() {
        return "AccountDTO{" + accountNumber + ", " + ownerName + ", balance=" + balance + ", " + status + "}";
    }
}
