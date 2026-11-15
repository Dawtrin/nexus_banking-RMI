package com.nexus.banking.contract.dto;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregate view of one node's books, used by NEXUS CONTROL to prove (or disprove) convergence: two branches that
 * hold the same {@code balanceDigest} have identical balances on every account.
 */
public class LedgerSummaryDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String serverId;
    private final int accountCount;
    private final int lockedCount;
    private final BigDecimal totalBalance;
    private final long transactionCount;
    private final Map<String, Long> lastSequenceByOrigin;
    private final String balanceDigest;
    private final List<String> overdrawnAccounts;
    private final Instant generatedAt;

    public LedgerSummaryDTO(String serverId, int accountCount, int lockedCount, BigDecimal totalBalance,
                            long transactionCount, Map<String, Long> lastSequenceByOrigin, String balanceDigest,
                            List<String> overdrawnAccounts, Instant generatedAt) {
        this.serverId = serverId;
        this.accountCount = accountCount;
        this.lockedCount = lockedCount;
        this.totalBalance = totalBalance;
        this.transactionCount = transactionCount;
        this.lastSequenceByOrigin = new LinkedHashMap<>(lastSequenceByOrigin);
        this.balanceDigest = balanceDigest;
        this.overdrawnAccounts = new ArrayList<>(overdrawnAccounts);
        this.generatedAt = generatedAt;
    }

    public String getServerId() { return serverId; }
    public int getAccountCount() { return accountCount; }
    public int getLockedCount() { return lockedCount; }
    public BigDecimal getTotalBalance() { return totalBalance; }
    public long getTransactionCount() { return transactionCount; }
    public Map<String, Long> getLastSequenceByOrigin() { return Collections.unmodifiableMap(lastSequenceByOrigin); }
    public String getBalanceDigest() { return balanceDigest; }
    /** Accounts whose balance is negative after merging both branches' entries (needs manual reconciliation). */
    public List<String> getOverdrawnAccounts() { return Collections.unmodifiableList(overdrawnAccounts); }
    public Instant getGeneratedAt() { return generatedAt; }
}
