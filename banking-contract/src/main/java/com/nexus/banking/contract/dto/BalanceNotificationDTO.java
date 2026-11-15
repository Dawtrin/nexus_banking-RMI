package com.nexus.banking.contract.dto;

import com.nexus.banking.contract.enums.TransactionType;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

/** Real-time push sent over the RMI callback when money enters or leaves a logged-in customer's account. */
public class BalanceNotificationDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String accountNumber;
    private final BigDecimal delta;
    private final BigDecimal newBalance;
    private final String transactionId;
    private final TransactionType type;
    private final String counterparty;
    private final String note;
    private final String servedByServerId;
    private final boolean fromPeerBranch;
    private final Instant at;

    public BalanceNotificationDTO(String accountNumber, BigDecimal delta, BigDecimal newBalance, String transactionId,
                                  TransactionType type, String counterparty, String note, String servedByServerId,
                                  boolean fromPeerBranch, Instant at) {
        this.accountNumber = accountNumber;
        this.delta = delta;
        this.newBalance = newBalance;
        this.transactionId = transactionId;
        this.type = type;
        this.counterparty = counterparty == null ? "" : counterparty;
        this.note = note == null ? "" : note;
        this.servedByServerId = servedByServerId;
        this.fromPeerBranch = fromPeerBranch;
        this.at = at == null ? Instant.now() : at;
    }

    public String getAccountNumber() { return accountNumber; }
    /** Signed change: positive for money in, negative for money out. */
    public BigDecimal getDelta() { return delta; }
    public BigDecimal getNewBalance() { return newBalance; }
    public String getTransactionId() { return transactionId; }
    public TransactionType getType() { return type; }
    public String getCounterparty() { return counterparty; }
    public String getNote() { return note; }
    public String getServedByServerId() { return servedByServerId; }
    /** True when the entry was applied here by replication (it happened at the other branch). */
    public boolean isFromPeerBranch() { return fromPeerBranch; }
    public Instant getAt() { return at; }
}
