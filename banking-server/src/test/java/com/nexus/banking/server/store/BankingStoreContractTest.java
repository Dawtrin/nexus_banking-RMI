package com.nexus.banking.server.store;

import com.nexus.banking.contract.dto.AccountDTO;
import com.nexus.banking.contract.dto.AuditVerificationDTO;
import com.nexus.banking.contract.dto.LedgerSummaryDTO;
import com.nexus.banking.contract.dto.TransactionDTO;
import com.nexus.banking.contract.enums.AccountStatus;
import com.nexus.banking.contract.enums.TransactionSyncState;
import com.nexus.banking.contract.enums.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The business rules every {@link BankingStore} must honour, run against RAM and (when configured) MySQL. */
public abstract class BankingStoreContractTest {
    static final String A = "1001000001";     // 50,000,000  limit 20,000,000
    static final String C = "2002000001";     // 80,000,000
    static final String EM = "3003000001";    // 5,000,000   limit 5,000,000
    static final String LOCKED = "4004000001";
    static final Instant DAY = Instant.now().truncatedTo(ChronoUnit.DAYS).minus(1, ChronoUnit.HOURS);

    protected abstract BankingStore store() throws Exception;

    static BigDecimal m(long v) {
        return BigDecimal.valueOf(v).setScale(2);
    }

    static String req() {
        return UUID.randomUUID().toString();
    }

    @Test
    void seedAccountsExistWithIdenticalOpeningBooks() throws Exception {
        BankingStore s = store();
        assertThat(s.listAccounts()).hasSize(DefaultAccounts.list().size());
        assertThat(s.findAccount(A).orElseThrow().getBalance()).isEqualByComparingTo("50000000");
        assertThat(s.findAccount(LOCKED).orElseThrow().getStatus()).isEqualTo(AccountStatus.LOCKED);
        StoredCredential c = s.findCredential(A).orElseThrow();
        assertThat(PinHasher.verify("123456", c.pinHash())).isTrue();
        assertThat(PinHasher.verify("654321", c.pinHash())).isFalse();
        assertThat(c.pinHash()).doesNotContain("123456");
    }

    @Test
    void withdrawIsAtomicAndChecksBalance() throws Exception {
        BankingStore s = store();
        MoneyResult ok = s.withdraw("n-a", "n-b", EM, req(), m(4_000_000), "x", DAY);
        assertThat(ok.outcome()).isEqualTo(MoneyResult.Outcome.OK);
        assertThat(ok.transaction().getBalanceAfter()).isEqualByComparingTo("1000000");
        MoneyResult no = s.withdraw("n-a", "n-b", EM, req(), m(2_000_000), "x", DAY);
        assertThat(no.outcome()).isEqualTo(MoneyResult.Outcome.INSUFFICIENT_FUNDS);
        assertThat(s.findAccount(EM).orElseThrow().getBalance()).isEqualByComparingTo("1000000");
        // nothing half-written: the refused attempt left no ledger entry and no outbox row
        assertThat(s.recentTransactions(EM, 10, "n-a", "n-b")).hasSize(1);
        assertThat(s.outboxStats("n-b").pending()).isEqualTo(1);
    }

    @Test
    void dailyLimitCountsOnlyTodaysOutgoingMoney() throws Exception {
        BankingStore s = store();
        assertThat(s.withdraw("n-a", null, A, req(), m(15_000_000), "", DAY).outcome()).isEqualTo(MoneyResult.Outcome.OK);
        assertThat(s.withdraw("n-a", null, A, req(), m(6_000_000), "", DAY).outcome()).isEqualTo(MoneyResult.Outcome.LIMIT_EXCEEDED);
        Instant tomorrow = Instant.now().plus(1, ChronoUnit.DAYS);   // a new day: yesterday's spending no longer counts
        assertThat(s.withdraw("n-a", null, A, req(), m(6_000_000), "", tomorrow).outcome()).isEqualTo(MoneyResult.Outcome.OK);
    }

    @Test
    void idempotencyReturnsTheOriginalAndNeverMovesMoneyTwice() throws Exception {
        BankingStore s = store();
        String id = req();
        MoneyResult first = s.withdraw("n-a", "n-b", A, id, m(1_000_000), "", DAY);
        MoneyResult again = s.withdraw("n-a", "n-b", A, id, m(1_000_000), "", DAY);
        assertThat(again.outcome()).isEqualTo(MoneyResult.Outcome.DUPLICATE);
        assertThat(again.transaction().getTransactionId()).isEqualTo(first.transaction().getTransactionId());
        assertThat(s.findAccount(A).orElseThrow().getBalance()).isEqualByComparingTo("49000000");
        assertThat(s.withdraw("n-a", "n-b", A, id, m(2_000_000), "", DAY).outcome()).isEqualTo(MoneyResult.Outcome.REQUEST_ID_REUSED);
        assertThat(s.deposit("n-a", "n-b", A, id, m(1_000_000), "").outcome()).isEqualTo(MoneyResult.Outcome.REQUEST_ID_REUSED);
        assertThat(s.outboxStats("n-b").pending()).isEqualTo(1);
    }

    @Test
    void transferWritesTwoLegsWithConsecutiveOriginSequences() throws Exception {
        BankingStore s = store();
        MoneyResult r = s.transfer("n-a", "n-b", A, C, req(), m(3_000_000), "rent", DAY);
        assertThat(r.outcome()).isEqualTo(MoneyResult.Outcome.OK);
        assertThat(r.transaction().getType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(r.creditLeg().getType()).isEqualTo(TransactionType.TRANSFER_IN);
        assertThat(r.creditLeg().getOriginSequence()).isEqualTo(r.transaction().getOriginSequence() + 1);
        assertThat(s.findAccount(A).orElseThrow().getBalance()).isEqualByComparingTo("47000000");
        assertThat(s.findAccount(C).orElseThrow().getBalance()).isEqualByComparingTo("83000000");
        assertThat(s.outboxStats("n-b").pending()).isEqualTo(2);
        assertThat(s.transfer("n-a", "n-b", A, LOCKED, req(), m(1), "", DAY).outcome()).isEqualTo(MoneyResult.Outcome.COUNTERPARTY_LOCKED);
        assertThat(s.transfer("n-a", "n-b", A, "5555555555", req(), m(1), "", DAY).outcome()).isEqualTo(MoneyResult.Outcome.COUNTERPARTY_NOT_FOUND);
        assertThat(s.transfer("n-a", "n-b", LOCKED, A, req(), m(1), "", DAY).outcome()).isEqualTo(MoneyResult.Outcome.ACCOUNT_LOCKED);
    }

    @Test
    void lockedAccountsRejectEveryMovement() throws Exception {
        BankingStore s = store();
        assertThat(s.deposit("n-a", null, LOCKED, req(), m(1), "").outcome()).isEqualTo(MoneyResult.Outcome.ACCOUNT_LOCKED);
        assertThat(s.withdraw("n-a", null, LOCKED, req(), m(1), "", DAY).outcome()).isEqualTo(MoneyResult.Outcome.ACCOUNT_LOCKED);
        assertThat(s.setAccountStatus(LOCKED, AccountStatus.ACTIVE)).isTrue();
        assertThat(s.deposit("n-a", null, LOCKED, req(), m(1), "").outcome()).isEqualTo(MoneyResult.Outcome.OK);
        assertThat(s.setAccountStatus("5555555555", AccountStatus.LOCKED)).isFalse();
    }

    @Test
    void concurrentWithdrawalsNeverOverdraw() throws Exception {
        BankingStore s = store();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                if (s.withdraw("n-a", "n-b", EM, req(), m(100_000), "", DAY).isSuccess()) ok.incrementAndGet();
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        assertThat(ok.get()).isEqualTo(50);
        assertThat(s.findAccount(EM).orElseThrow().getBalance()).isEqualByComparingTo("0");
        assertThat(s.recentTransactions(EM, 200, "n-a", "n-b")).hasSize(50);
    }

    @Test
    void ingestAppliesDeltasOnceAndAdvancesTheCheckpoint() throws Exception {
        BankingStore s = store();
        TransactionDTO dep = new TransactionDTO(UUID.randomUUID().toString(), A, TransactionType.DEPOSIT, m(1_000_000),
                m(0), "", "", Instant.now(), "n-peer", 1, 0, req(), null);
        TransactionDTO wd = new TransactionDTO(UUID.randomUUID().toString(), A, TransactionType.WITHDRAW, m(400_000),
                m(0), "", "", Instant.now(), "n-peer", 2, 0, req(), null);
        IngestResult r1 = s.ingestReplicated("n-peer", List.of(dep, wd));
        assertThat(r1.accepted()).hasSize(2);
        assertThat(r1.applied()).hasSize(2);
        assertThat(r1.applied().get(1).getBalanceAfter()).isEqualByComparingTo("50600000");
        assertThat(r1.checkpoint()).isEqualTo(2);
        IngestResult r2 = s.ingestReplicated("n-peer", List.of(dep, wd));      // replay
        assertThat(r2.accepted()).hasSize(2);
        assertThat(r2.applied()).isEmpty();
        assertThat(s.findAccount(A).orElseThrow().getBalance()).isEqualByComparingTo("50600000");
        assertThat(s.recentTransactions(A, 10, "n-a", "n-b")).allMatch(t -> t.getSyncState() == TransactionSyncState.FROM_PEER);
        assertThat(s.getInboundCheckpoint("n-peer")).isEqualTo(2);
    }

    @Test
    void ingestSuppressesAPeerEntryWhoseRequestIdWasAlreadyExecutedHere() throws Exception {
        BankingStore s = store();
        String id = req();
        s.withdraw("n-a", "n-b", A, id, m(2_000_000), "", DAY);
        TransactionDTO dup = new TransactionDTO(UUID.randomUUID().toString(), A, TransactionType.WITHDRAW, m(2_000_000),
                m(0), "", "", Instant.now(), "n-peer", 1, 0, id, null);
        IngestResult r = s.ingestReplicated("n-peer", List.of(dup));
        assertThat(r.accepted()).containsExactly(dup.getTransactionId());
        assertThat(r.applied()).isEmpty();
        assertThat(r.events()).extracting(LedgerEvent::kind).containsExactly(LedgerEvent.Kind.DUPLICATE_SUPPRESSED);
        assertThat(s.findAccount(A).orElseThrow().getBalance()).isEqualByComparingTo("48000000");
        assertThat(s.getInboundCheckpoint("n-peer")).isEqualTo(1);
    }

    @Test
    void ingestRejectsUnknownAccountsAndReportsOverdrafts() throws Exception {
        BankingStore s = store();
        TransactionDTO ghost = new TransactionDTO("ghost-1", "7777777777", TransactionType.DEPOSIT, m(5), m(0), "", "",
                Instant.now(), "n-peer", 1, 0, null, null);
        IngestResult r = s.ingestReplicated("n-peer", List.of(ghost));
        assertThat(r.rejected()).containsKey("ghost-1");
        assertThat(r.accepted()).isEmpty();
        TransactionDTO big = new TransactionDTO("big-1", EM, TransactionType.WITHDRAW, m(6_000_000), m(0), "", "",
                Instant.now(), "n-peer", 2, 0, null, null);
        IngestResult r2 = s.ingestReplicated("n-peer", List.of(big));
        assertThat(r2.events()).extracting(LedgerEvent::kind).containsExactly(LedgerEvent.Kind.OVERDRAFT_DETECTED);
        LedgerSummaryDTO sum = s.ledgerSummary("n-a");
        assertThat(sum.getOverdrawnAccounts()).containsExactly(EM);
    }

    @Test
    void outboxLifecycleDeliversRetriesAndDeadLetters() throws Exception {
        BankingStore s = store();
        s.deposit("n-a", "n-b", A, req(), m(1), "");
        s.deposit("n-a", "n-b", A, req(), m(2), "");
        List<OutboxEntry> due = s.dueOutbox("n-b", Instant.now(), 10);
        assertThat(due).hasSize(2);
        s.markDelivered(List.of(due.get(0).outboxId()));
        assertThat(s.outboxStats("n-b").pending()).isEqualTo(1);
        long id = due.get(1).outboxId();
        assertThat(s.rejectOutbox(id, "no", Instant.now(), 1, 2, 3)).isFalse();
        assertThat(s.rejectOutbox(id, "no", Instant.now(), 1, 2, 3)).isFalse();
        assertThat(s.rejectOutbox(id, "no", Instant.now(), 1, 2, 3)).isTrue();
        assertThat(s.outboxStats("n-b").dead()).isEqualTo(1);
        assertThat(s.requeueDead("n-b", Instant.now())).isEqualTo(1);
        assertThat(s.outboxStats("n-b").pending()).isEqualTo(1);
    }

    @Test
    void ledgerSummaryDigestChangesWithBalances() throws Exception {
        BankingStore s = store();
        LedgerSummaryDTO before = s.ledgerSummary("n-a");
        s.deposit("n-a", null, A, req(), m(1), "");
        LedgerSummaryDTO after = s.ledgerSummary("n-a");
        assertThat(after.getBalanceDigest()).isNotEqualTo(before.getBalanceDigest());
        assertThat(after.getTotalBalance()).isEqualByComparingTo(before.getTotalBalance().add(m(1)));
        assertThat(after.getTransactionCount()).isEqualTo(1);
        assertThat(after.getLastSequenceByOrigin()).containsEntry("n-a", 1L);
    }

    @Test
    void accountsCanBeCreatedAndLimitsChanged() throws Exception {
        BankingStore s = store();
        AccountDTO acc = new AccountDTO("7007000001", "Khách mới", m(1_000_000), "HN", AccountStatus.ACTIVE, Instant.now(), m(5_000_000));
        assertThat(s.createAccount(acc, PinHasher.hash("246810"))).isTrue();
        assertThat(s.createAccount(acc, PinHasher.hash("246810"))).isFalse();
        assertThat(s.setDailyLimit("7007000001", m(9_000_000))).isTrue();
        assertThat(s.findAccount("7007000001").orElseThrow().getDailyLimit()).isEqualByComparingTo("9000000");
        assertThat(s.setDailyLimit("5555555555", m(1))).isFalse();
    }

    @Test
    void auditLedgerIsHashChained() throws Exception {
        BankingStore s = store();
        for (int i = 0; i < 5; i++) s.appendAudit(Instant.now(), "n-a", "ops", "LOCK_ACCOUNT", "acc" + i, "OK", "reason " + i);
        AuditVerificationDTO v = s.verifyAuditChain();
        assertThat(v.isValid()).isTrue();
        assertThat(v.getEntriesChecked()).isEqualTo(5);
        assertThat(s.recentAudit(10)).hasSize(5);
        assertThat(s.recentAudit(10).get(0).getPrevHash()).isEqualTo(s.recentAudit(10).get(1).getHash());
    }
}
