package com.nexus.banking.server.store;

import com.nexus.banking.contract.dto.AccountDTO;
import com.nexus.banking.contract.dto.TransactionDTO;
import com.nexus.banking.contract.enums.AccountStatus;
import com.nexus.banking.contract.enums.TransactionSyncState;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure rules shared by both store implementations, so they can never disagree on a business decision. */
final class LedgerMath {
    private LedgerMath() {
    }

    /** Result of the pre-debit checks, or null when the debit is allowed. */
    static MoneyResult.Outcome debitProblem(AccountDTO account, BigDecimal amount, BigDecimal spentToday) {
        if (account.getStatus() == AccountStatus.LOCKED) return MoneyResult.Outcome.ACCOUNT_LOCKED;
        if (account.getBalance().compareTo(amount) < 0) return MoneyResult.Outcome.INSUFFICIENT_FUNDS;
        if (spentToday.add(amount).compareTo(account.getDailyLimit()) > 0) return MoneyResult.Outcome.LIMIT_EXCEEDED;
        return null;
    }

    static String limitDetail(AccountDTO account, BigDecimal spentToday) {
        BigDecimal left = account.getDailyLimit().subtract(spentToday).max(BigDecimal.ZERO);
        return "Hạn mức còn lại hôm nay: " + left.toPlainString();
    }

    /** SHA-256 over "account:balance" lines sorted by account: equal digests mean identical balances everywhere. */
    static String balanceDigest(List<AccountDTO> accounts) {
        List<AccountDTO> sorted = new ArrayList<>(accounts);
        sorted.sort(Comparator.comparing(AccountDTO::getAccountNumber));
        StringBuilder sb = new StringBuilder();
        for (AccountDTO a : sorted) {
            sb.append(a.getAccountNumber()).append(':').append(a.getBalance().setScale(2).toPlainString()).append('\n');
        }
        return AuditChain.sha256(sb.toString());
    }

    static TransactionSyncState syncStateFor(boolean localOrigin, String outboxStatus, boolean hasPeer) {
        if (!localOrigin) return TransactionSyncState.FROM_PEER;
        if (!hasPeer || "DELIVERED".equals(outboxStatus)) return TransactionSyncState.SYNCED;
        if ("DEAD".equals(outboxStatus)) return TransactionSyncState.FAILED;
        return TransactionSyncState.PENDING;
    }

    static TransactionDTO tagged(TransactionDTO t, TransactionSyncState s) {
        return t.withSyncState(s);
    }
}
