package com.nexus.banking.contract.dto;

import com.nexus.banking.contract.enums.TransactionSyncState;
import com.nexus.banking.contract.enums.TransactionType;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;

/**
 * One immutable ledger entry (bút toán). Crosses RMI both to clients and between branches (replication).
 * {@code originServerId + originSequence} identify the entry cluster-wide; {@code clientRequestId} is the
 * idempotency key supplied by the customer's device. {@code balanceAfter} is the balance of the account on the
 * node that reports the entry (replicated entries carry the receiving node's running balance).
 */
public class TransactionDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String transactionId;
    private final String accountNumber;
    private final TransactionType type;
    private final BigDecimal amount;
    private final BigDecimal balanceAfter;
    private final String referenceAccount;
    private final String note;
    private final Instant createdAt;
    private final String originServerId;
    private final long originSequence;
    private final long localSequence;
    private final String clientRequestId;
    private final TransactionSyncState syncState;

    public TransactionDTO(String transactionId, String accountNumber, TransactionType type, BigDecimal amount,
                          BigDecimal balanceAfter, String referenceAccount, String note, Instant createdAt,
                          String originServerId, long originSequence, long localSequence, String clientRequestId,
                          TransactionSyncState syncState) {
        this.transactionId = Objects.requireNonNull(transactionId, "transactionId");
        this.accountNumber = Objects.requireNonNull(accountNumber, "accountNumber");
        this.type = Objects.requireNonNull(type, "type");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.balanceAfter = balanceAfter == null ? BigDecimal.ZERO.setScale(2) : balanceAfter;
        this.referenceAccount = referenceAccount == null ? "" : referenceAccount;
        this.note = note == null ? "" : note;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.originServerId = Objects.requireNonNull(originServerId, "originServerId");
        this.originSequence = originSequence;
        this.localSequence = localSequence;
        this.clientRequestId = clientRequestId;
        this.syncState = syncState == null ? TransactionSyncState.PENDING : syncState;
    }

    public String getTransactionId() { return transactionId; }
    public String getAccountNumber() { return accountNumber; }
    public TransactionType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public String getReferenceAccount() { return referenceAccount; }
    public String getNote() { return note; }
    public Instant getCreatedAt() { return createdAt; }
    public String getOriginServerId() { return originServerId; }
    public long getOriginSequence() { return originSequence; }
    public long getLocalSequence() { return localSequence; }
    /** May be null for the credit leg of a transfer and for entries created by an administrator. */
    public String getClientRequestId() { return clientRequestId; }
    public TransactionSyncState getSyncState() { return syncState; }

    /** +amount for credits, -amount for debits. */
    public BigDecimal getSignedAmount() { return type.isCredit() ? amount : amount.negate(); }

    public TransactionDTO withSyncState(TransactionSyncState s) {
        return new TransactionDTO(transactionId, accountNumber, type, amount, balanceAfter, referenceAccount, note,
                createdAt, originServerId, originSequence, localSequence, clientRequestId, s);
    }

    public TransactionDTO withLocalState(BigDecimal newBalanceAfter, long newLocalSequence) {
        return new TransactionDTO(transactionId, accountNumber, type, amount, newBalanceAfter, referenceAccount, note,
                createdAt, originServerId, originSequence, newLocalSequence, clientRequestId, syncState);
    }

    /** Display order, newest last: createdAt, then origin server, then origin sequence. */
    public static Comparator<TransactionDTO> displayOrder() {
        return Comparator.comparing(TransactionDTO::getCreatedAt)
                .thenComparing(TransactionDTO::getOriginServerId)
                .thenComparingLong(TransactionDTO::getOriginSequence);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof TransactionDTO t && transactionId.equals(t.transactionId));
    }

    @Override
    public int hashCode() { return transactionId.hashCode(); }

    @Override
    public String toString() {
        return "TransactionDTO{" + transactionId + ", " + accountNumber + ", " + type + " " + amount
                + ", origin=" + originServerId + "#" + originSequence + "}";
    }
}
