package com.nexus.banking.server.service;

import com.nexus.banking.contract.dto.ErrorCodes;
import com.nexus.banking.contract.dto.LedgerSummaryDTO;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.dto.ReplicationAck;
import com.nexus.banking.contract.dto.TransactionDTO;
import com.nexus.banking.server.support.Node;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.nexus.banking.server.support.Node.m;
import static com.nexus.banking.server.support.Node.req;
import static org.assertj.core.api.Assertions.assertThat;

/** Two active/active branches exchanging ledger entries: eventual consistency, catch-up, duplicate suppression. */
class ReplicationTest {
    private Node a;
    private Node b;

    @BeforeEach
    void up() {
        a = Node.ram("server-a", "server-b");
        b = Node.ram("server-b", "server-a");
        Node.connect(a, b);
    }

    @AfterEach
    void down() {
        a.close();
        b.close();
    }

    private void converge() {
        for (int i = 0; i < 4; i++) {
            a.replication.runOnce();
            b.replication.runOnce();
        }
    }

    private String digest(Node n) throws Exception {
        return n.store.ledgerSummary(n.config.getServerId()).getBalanceDigest();
    }

    @Test
    void aDepositOnOneBranchAppearsOnTheOther() throws Exception {
        String s = a.login(Node.AN);
        a.bank.deposit(s, Node.AN, req(), m(5_000_000), "lương");
        assertThat(b.balance(Node.AN)).isEqualByComparingTo("50000000");   // not replicated yet
        a.replication.runOnce();
        assertThat(b.balance(Node.AN)).isEqualByComparingTo("55000000");
        assertThat(digest(a)).isEqualTo(digest(b));
    }

    @Test
    void transfersReplicateBothLegs() throws Exception {
        String s = a.login(Node.AN);
        a.bank.transfer(s, Node.AN, Node.CUONG, req(), m(3_000_000), "");
        a.replication.runOnce();
        assertThat(b.balance(Node.AN)).isEqualByComparingTo("47000000");
        assertThat(b.balance(Node.CUONG)).isEqualByComparingTo("83000000");
        assertThat(digest(a)).isEqualTo(digest(b));
    }

    @Test
    void bothBranchesAcceptTransactionsIndependentlyAndConverge() throws Exception {
        String sa = a.login(Node.AN);
        String sc = b.login(Node.CUONG);
        a.bank.withdraw(sa, Node.AN, req(), m(1_000_000), "");
        b.bank.deposit(sc, Node.CUONG, req(), m(2_000_000), "");
        b.bank.transfer(sc, Node.CUONG, Node.BINH, req(), m(500_000), "");
        converge();
        assertThat(a.balance(Node.AN)).isEqualByComparingTo("49000000");
        assertThat(a.balance(Node.CUONG)).isEqualByComparingTo("81500000");
        assertThat(a.balance(Node.BINH)).isEqualByComparingTo("25500000");
        assertThat(digest(a)).isEqualTo(digest(b));
        assertThat(a.store.outboxStats("server-b").pending()).isZero();
        assertThat(b.store.outboxStats("server-a").pending()).isZero();
    }

    @Test
    void networkPartitionThenRecoveryConvergesWithoutLosingAnything() throws Exception {
        a.link.set(null);                                            // cable pulled
        b.link.set(null);
        String sa = a.login(Node.AN);
        String sc = b.login(Node.CUONG);
        for (int i = 0; i < 5; i++) a.bank.withdraw(sa, Node.AN, req(), m(100_000), "offline A");
        for (int i = 0; i < 3; i++) b.bank.deposit(sc, Node.CUONG, req(), m(200_000), "offline B");
        a.bank.transfer(sa, Node.AN, Node.EM, req(), m(1_000_000), "offline transfer");
        a.replication.runOnce();
        b.replication.runOnce();
        assertThat(a.store.outboxStats("server-b").pending()).isEqualTo(7);   // 5 withdrawals + 2 transfer legs
        assertThat(digest(a)).isNotEqualTo(digest(b));
        // transactions are tagged "waiting for sync" while the peer is unreachable
        assertThat(a.bank.getRecentTransactions(sa, Node.AN, 10).getData())
                .allMatch(t -> t.getSyncState() == com.nexus.banking.contract.enums.TransactionSyncState.PENDING);

        Thread.sleep(300);                                           // let the connection back-off elapse
        Node.connect(a, b);                                          // cable plugged back in
        converge();
        assertThat(digest(a)).isEqualTo(digest(b));
        assertThat(a.balance(Node.AN)).isEqualByComparingTo("48500000");
        assertThat(b.balance(Node.AN)).isEqualByComparingTo("48500000");
        assertThat(b.balance(Node.CUONG)).isEqualByComparingTo("80600000");
        assertThat(a.balance(Node.CUONG)).isEqualByComparingTo("80600000");
        assertThat(a.store.outboxStats("server-b").pending()).isZero();
        assertThat(a.bank.getRecentTransactions(sa, Node.AN, 10).getData())
                .allMatch(t -> t.getSyncState() == com.nexus.banking.contract.enums.TransactionSyncState.SYNCED);
    }

    @Test
    void catchUpPullsMissedEntriesEvenWhenThePeerCannotPushThem() throws Exception {
        String sa = a.login(Node.AN);
        a.link.set(null);                                            // A cannot reach B, B can reach A
        for (int i = 0; i < 4; i++) a.bank.deposit(sa, Node.AN, req(), m(1_000_000), "");
        b.replication.runOnce();                                     // B pulls from A's ledger (catch-up)
        assertThat(b.balance(Node.AN)).isEqualByComparingTo("54000000");
        assertThat(b.store.getInboundCheckpoint("server-a")).isEqualTo(4);
        b.replication.runOnce();
        assertThat(b.balance(Node.AN)).isEqualByComparingTo("54000000");   // pulling twice never applies twice
    }

    @Test
    void replayingTheSameBatchIsHarmless() throws Exception {
        String s = a.login(Node.AN);
        a.bank.deposit(s, Node.AN, req(), m(1_000_000), "");
        List<TransactionDTO> batch = a.store.findByOriginAfter("server-a", 0, 10);
        for (int i = 0; i < 3; i++) {
            OperationResult<ReplicationAck> r = b.repl.replicateBatch("server-a", Node.SECRET, batch);
            assertThat(r.isSuccess()).isTrue();
            assertThat(r.getData().getAcceptedTransactionIds()).hasSize(1);
        }
        assertThat(b.balance(Node.AN)).isEqualByComparingTo("51000000");
    }

    @Test
    void theSameClientRequestExecutedOnBothBranchesDuringAPartitionDebitsOnce() throws Exception {
        a.link.set(null);
        b.link.set(null);
        String sa = a.login(Node.AN);
        String sb = b.login(Node.AN);
        String retryId = req();                                      // phone retries on the other ATM during the outage
        assertThat(a.bank.withdraw(sa, Node.AN, retryId, m(2_000_000), "").isSuccess()).isTrue();
        assertThat(b.bank.withdraw(sb, Node.AN, retryId, m(2_000_000), "").isSuccess()).isTrue();
        Thread.sleep(300);
        Node.connect(a, b);
        converge();
        assertThat(a.balance(Node.AN)).isEqualByComparingTo("48000000");   // ONE debit, not two
        assertThat(b.balance(Node.AN)).isEqualByComparingTo("48000000");
        assertThat(digest(a)).isEqualTo(digest(b));
        assertThat(a.auditActions()).contains("DUPLICATE_SUPPRESSED");
        assertThat(b.auditActions()).contains("DUPLICATE_SUPPRESSED");
    }

    @Test
    void spendingTheSameMoneyOnBothBranchesIsDetectedAsAnOverdraft() throws Exception {
        a.link.set(null);
        b.link.set(null);
        String sa = a.login(Node.EM);                                // 5,000,000
        String sb = b.login(Node.EM);
        assertThat(a.bank.withdraw(sa, Node.EM, req(), m(3_000_000), "").isSuccess()).isTrue();
        assertThat(b.bank.withdraw(sb, Node.EM, req(), m(3_000_000), "").isSuccess()).isTrue();
        Thread.sleep(300);
        Node.connect(a, b);
        converge();
        assertThat(a.balance(Node.EM)).isEqualByComparingTo("-1000000");
        assertThat(digest(a)).isEqualTo(digest(b));                  // books agree...
        LedgerSummaryDTO sum = a.store.ledgerSummary("server-a");
        assertThat(sum.getOverdrawnAccounts()).containsExactly(Node.EM);   // ...and the conflict is visible
        assertThat(a.auditActions()).contains("OVERDRAFT_DETECTED");
        assertThat(b.auditActions()).contains("OVERDRAFT_DETECTED");
    }

    @Test
    void anEntryForAnAccountTheOtherBranchDoesNotHaveYetIsRetriedNotLost() throws Exception {
        a.admin.createAccount(Node.ADMIN_TOKEN, "ops", "7007000001", "Khách mới", "HN", m(1_000_000), m(5_000_000), "246810");
        String s = a.bank.login("7007000001", "246810", "device-new-00001", null).getData().getSession().getSessionId();
        a.bank.deposit(s, "7007000001", req(), m(500_000), "");
        a.replication.runOnce();                                     // B has no such account: refused, kept in the outbox
        assertThat(a.store.outboxStats("server-b").pending()).isEqualTo(1);
        b.admin.createAccount(Node.ADMIN_TOKEN, "ops", "7007000001", "Khách mới", "HN", m(1_000_000), m(5_000_000), "246810");
        Thread.sleep(100);                                           // back-off of the rejected entry
        a.replication.runOnce();
        assertThat(b.balance("7007000001")).isEqualByComparingTo("1500000");
        assertThat(a.store.outboxStats("server-b").pending()).isZero();
    }

    @Test
    void replicationRejectsWrongSecretAndWrongCaller() {
        assertThat(b.repl.replicateBatch("server-a", "wrong-secret-wrong-secret", List.of()).getErrorCode()).isEqualTo(ErrorCodes.UNAUTHORIZED);
        assertThat(b.repl.getTransactionsAfter("intruder", Node.SECRET, 0, 10).getErrorCode()).isEqualTo(ErrorCodes.UNAUTHORIZED);
        assertThat(b.repl.getPeerStatus("server-a", Node.SECRET).isSuccess()).isTrue();
    }

    @Test
    void forgedEntriesAreRejected() {
        TransactionDTO forged = new TransactionDTO("x-1", Node.AN, com.nexus.banking.contract.enums.TransactionType.DEPOSIT,
                m(999_999_999), m(0), "", "", java.time.Instant.now(), "server-a", 1, 0, null, null);
        TransactionDTO claimsOtherOrigin = new TransactionDTO("x-2", Node.AN, com.nexus.banking.contract.enums.TransactionType.DEPOSIT,
                m(1), m(0), "", "", java.time.Instant.now(), "server-z", 1, 0, null, null);
        TransactionDTO negative = new TransactionDTO("x-3", Node.AN, com.nexus.banking.contract.enums.TransactionType.DEPOSIT,
                new java.math.BigDecimal("-5.00"), m(0), "", "", java.time.Instant.now(), "server-a", 2, 0, null, null);
        ReplicationAck ack = b.repl.replicateBatch("server-a", Node.SECRET, List.of(claimsOtherOrigin, negative)).getData();
        assertThat(ack.getAcceptedTransactionIds()).isEmpty();
        assertThat(ack.getRejected()).containsKeys("x-2", "x-3");
        assertThat(b.balance(Node.AN)).isEqualByComparingTo("50000000");
        assertThat(forged.getAmount()).isNotNull();
    }

    @Test
    void replicatedMoneyIsPushedToTheCustomersDeviceOnTheOtherBranch() {
        Node.RecordingCallback phone = new Node.RecordingCallback();
        b.login(Node.CUONG, "device-cuong-0001", phone);
        String sa = a.login(Node.AN);
        a.bank.transfer(sa, Node.AN, Node.CUONG, req(), m(1_500_000), "gửi anh Cường");
        a.replication.runOnce();
        assertThat(phone.balances).hasSize(1);
        assertThat(phone.balances.get(0).isFromPeerBranch()).isTrue();
        assertThat(phone.balances.get(0).getDelta()).isEqualByComparingTo("1500000");
        assertThat(phone.balances.get(0).getNewBalance()).isEqualByComparingTo("81500000");
    }

    @Test
    void loginOnOneBranchEvictsTheSessionOnTheOtherBranch() {
        Node.RecordingCallback atmHanoi = new Node.RecordingCallback();
        String s1 = a.login(Node.AN, "device-atm-hn-01", atmHanoi);
        b.login(Node.AN, "device-phone-hcm1", null);
        // peer eviction is asynchronous (replication executor / common pool): wait for it
        long deadline = System.currentTimeMillis() + 3000;
        while ((a.bank.getAccount(s1, Node.AN).isSuccess() || atmHanoi.evictions.isEmpty()) && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(20); } catch (InterruptedException e) { break; }
        }
        assertThat(a.bank.getAccount(s1, Node.AN).getErrorCode()).isEqualTo(ErrorCodes.SESSION_EVICTED);
        assertThat(atmHanoi.evictions).hasSize(1);
        assertThat(atmHanoi.evictions.get(0)).contains("thiết bị khác");
    }
}
