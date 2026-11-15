package com.nexus.banking.server.service;

import com.nexus.banking.contract.dto.AccountDTO;
import com.nexus.banking.contract.dto.ErrorCodes;
import com.nexus.banking.contract.dto.LoginResultDTO;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.dto.TransactionDTO;
import com.nexus.banking.contract.enums.TransactionType;
import com.nexus.banking.server.support.Node;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static com.nexus.banking.server.support.Node.m;
import static com.nexus.banking.server.support.Node.req;
import static org.assertj.core.api.Assertions.assertThat;

class BankingServiceTest {
    private Node n;

    @BeforeEach
    void up() {
        n = Node.ram("server-a", "server-b");
    }

    @AfterEach
    void down() {
        n.close();
    }

    // ----------------------------------------------------------------------------------------- login

    @Test
    void loginSucceedsWithCorrectPinAndReturnsAccount() {
        OperationResult<LoginResultDTO> r = n.bank.login(Node.AN, Node.PIN, "device-0001-abcd", null);
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getData().getAccount().getOwnerName()).isEqualTo("Nguyễn Văn An");
        assertThat(r.getData().getAccount().getBalance()).isEqualByComparingTo("50000000");
        assertThat(r.getData().getServerId()).isEqualTo("server-a");
        assertThat(n.auditActions()).contains("LOGIN");
    }

    @Test
    void wrongPinAndUnknownAccountGiveTheSameError() {
        OperationResult<LoginResultDTO> wrong = n.bank.login(Node.AN, "000000", "device-0001-abcd", null);
        OperationResult<LoginResultDTO> unknown = n.bank.login("5555555555", Node.PIN, "device-0001-abcd", null);
        assertThat(wrong.getErrorCode()).isEqualTo(ErrorCodes.AUTH_FAILED);
        assertThat(unknown.getErrorCode()).isEqualTo(ErrorCodes.AUTH_FAILED);
        assertThat(wrong.getMessage()).isEqualTo(unknown.getMessage());
        assertThat(n.auditActions()).contains("LOGIN_FAILED");
    }

    @Test
    void pinGuessingIsThrottled() {
        for (int i = 0; i < 5; i++) assertThat(n.bank.login(Node.AN, "111111", "device-0001-abcd", null).getErrorCode()).isEqualTo(ErrorCodes.AUTH_FAILED);
        OperationResult<LoginResultDTO> blocked = n.bank.login(Node.AN, Node.PIN, "device-0001-abcd", null);
        assertThat(blocked.getErrorCode()).isEqualTo(ErrorCodes.TOO_MANY_ATTEMPTS);
    }

    @Test
    void lockedAccountCannotLogIn() {
        assertThat(n.bank.login(Node.LOCKED, Node.PIN, "device-0001-abcd", null).getErrorCode()).isEqualTo(ErrorCodes.ACCOUNT_LOCKED);
    }

    @Test
    void malformedInputIsRejectedBeforeAnyLookup() {
        assertThat(n.bank.login("abc", Node.PIN, "device-0001-abcd", null).getErrorCode()).isEqualTo(ErrorCodes.VALIDATION);
        assertThat(n.bank.login(Node.AN, "12", "device-0001-abcd", null).getErrorCode()).isEqualTo(ErrorCodes.VALIDATION);
        assertThat(n.bank.login(Node.AN, Node.PIN, "x", null).getErrorCode()).isEqualTo(ErrorCodes.VALIDATION);
    }

    // --------------------------------------------------------------------------------- money movement

    @Test
    void depositIncreasesBalanceAndRecordsLedgerEntry() {
        String s = n.login(Node.AN);
        OperationResult<TransactionDTO> r = n.bank.deposit(s, Node.AN, req(), m(1_000_000), "nạp tiền");
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getData().getType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(r.getData().getBalanceAfter()).isEqualByComparingTo("51000000");
        assertThat(n.balance(Node.AN)).isEqualByComparingTo("51000000");
    }

    @Test
    void withdrawalIsRefusedWhenBalanceIsInsufficientAndLeavesBooksUntouched() {
        String s = n.login(Node.EM);                       // 5,000,000 balance, 5,000,000 limit
        n.bank.withdraw(s, Node.EM, req(), m(4_000_000), "");
        OperationResult<TransactionDTO> r = n.bank.withdraw(s, Node.EM, req(), m(2_000_000), "");
        assertThat(r.getErrorCode()).isEqualTo(ErrorCodes.INSUFFICIENT_FUNDS);
        assertThat(n.balance(Node.EM)).isEqualByComparingTo("1000000");
        assertThat(n.bank.getRecentTransactions(s, Node.EM, 50).getData()).hasSize(1);
    }

    @Test
    void dailyLimitIsEnforced() {
        String s = n.login(Node.AN);                       // limit 20,000,000
        assertThat(n.bank.withdraw(s, Node.AN, req(), m(15_000_000), "").isSuccess()).isTrue();
        OperationResult<TransactionDTO> r = n.bank.withdraw(s, Node.AN, req(), m(6_000_000), "");
        assertThat(r.getErrorCode()).isEqualTo(ErrorCodes.LIMIT_EXCEEDED);
        assertThat(n.bank.withdraw(s, Node.AN, req(), m(5_000_000), "").isSuccess()).isTrue();
    }

    @Test
    void invalidAmountsAreRejected() {
        String s = n.login(Node.AN);
        assertThat(n.bank.deposit(s, Node.AN, req(), m(0), "").getErrorCode()).isEqualTo(ErrorCodes.INVALID_AMOUNT);
        assertThat(n.bank.deposit(s, Node.AN, req(), new BigDecimal("-5"), "").getErrorCode()).isEqualTo(ErrorCodes.INVALID_AMOUNT);
        assertThat(n.bank.deposit(s, Node.AN, req(), new BigDecimal("1.001"), "").getErrorCode()).isEqualTo(ErrorCodes.INVALID_AMOUNT);
        assertThat(n.bank.deposit(s, Node.AN, req(), null, "").getErrorCode()).isEqualTo(ErrorCodes.INVALID_AMOUNT);
        assertThat(n.balance(Node.AN)).isEqualByComparingTo("50000000");
    }

    // ------------------------------------------------------------------------- idempotency (no double spend)

    @Test
    void retryWithSameRequestIdNeverDebitsTwice() {
        String s = n.login(Node.AN);
        String id = req();
        OperationResult<TransactionDTO> first = n.bank.withdraw(s, Node.AN, id, m(2_000_000), "rút ATM");
        OperationResult<TransactionDTO> retry = n.bank.withdraw(s, Node.AN, id, m(2_000_000), "rút ATM");
        OperationResult<TransactionDTO> retry2 = n.bank.withdraw(s, Node.AN, id, m(2_000_000), "rút ATM");
        assertThat(first.isSuccess() && retry.isSuccess() && retry2.isSuccess()).isTrue();
        assertThat(retry.getData().getTransactionId()).isEqualTo(first.getData().getTransactionId());
        assertThat(retry2.getData().getTransactionId()).isEqualTo(first.getData().getTransactionId());
        assertThat(retry.getMessage()).contains("lặp lại");
        assertThat(n.balance(Node.AN)).isEqualByComparingTo("48000000");
        assertThat(n.bank.getRecentTransactions(s, Node.AN, 50).getData()).hasSize(1);
        assertThat(n.auditActions()).contains("IDEMPOTENT_REPLAY");
    }

    @Test
    void sameRequestIdForADifferentOperationIsRejected() {
        String s = n.login(Node.AN);
        String id = req();
        assertThat(n.bank.withdraw(s, Node.AN, id, m(1_000_000), "").isSuccess()).isTrue();
        assertThat(n.bank.withdraw(s, Node.AN, id, m(9_000_000), "").getErrorCode()).isEqualTo(ErrorCodes.REQUEST_ID_REUSED);
        assertThat(n.bank.deposit(s, Node.AN, id, m(1_000_000), "").getErrorCode()).isEqualTo(ErrorCodes.REQUEST_ID_REUSED);
        assertThat(n.balance(Node.AN)).isEqualByComparingTo("49000000");
    }

    @Test
    void concurrentRetriesOfOneRequestDebitExactlyOnce() throws Exception {
        String s = n.login(Node.AN);
        String id = req();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<OperationResult<TransactionDTO>>> fs = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                return n.bank.withdraw(s, Node.AN, id, m(1_000_000), "retry storm");
            }));
        }
        go.countDown();
        for (Future<OperationResult<TransactionDTO>> f : fs) assertThat(f.get().isSuccess()).isTrue();
        pool.shutdown();
        assertThat(n.balance(Node.AN)).isEqualByComparingTo("49000000");
    }

    @Test
    void concurrentWithdrawalsNeverOverdrawOrExceedTheLimit() throws Exception {
        String s = n.login(Node.EM);                      // 5,000,000
        ExecutorService pool = Executors.newFixedThreadPool(24);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                if (n.bank.withdraw(s, Node.EM, req(), m(100_000), "").isSuccess()) ok.incrementAndGet();
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        assertThat(ok.get()).isEqualTo(50);                // exactly 5,000,000 / 100,000
        assertThat(n.balance(Node.EM)).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------------------------------ transfer

    @Test
    void transferBooksBothLegsAtomically() {
        String s = n.login(Node.AN);
        OperationResult<TransactionDTO> r = n.bank.transfer(s, Node.AN, Node.CUONG, req(), m(3_000_000), "tiền nhà");
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getData().getType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(r.getData().getReferenceAccount()).isEqualTo(Node.CUONG);
        assertThat(n.balance(Node.AN)).isEqualByComparingTo("47000000");
        assertThat(n.balance(Node.CUONG)).isEqualByComparingTo("83000000");
        assertThat(n.audit).isNotNull();
        String c = n.login(Node.CUONG);
        List<TransactionDTO> cuongHistory = n.bank.getRecentTransactions(c, Node.CUONG, 10).getData();
        assertThat(cuongHistory).hasSize(1);
        assertThat(cuongHistory.get(0).getType()).isEqualTo(TransactionType.TRANSFER_IN);
        assertThat(cuongHistory.get(0).getReferenceAccount()).isEqualTo(Node.AN);
    }

    @Test
    void transferRulesAreChecked() {
        String s = n.login(Node.AN);
        assertThat(n.bank.transfer(s, Node.AN, Node.AN, req(), m(1), "").getErrorCode()).isEqualTo(ErrorCodes.SAME_ACCOUNT);
        assertThat(n.bank.transfer(s, Node.AN, "5555555555", req(), m(1000), "").getErrorCode()).isEqualTo(ErrorCodes.ACCOUNT_NOT_FOUND);
        assertThat(n.bank.transfer(s, Node.AN, Node.LOCKED, req(), m(1000), "").getErrorCode()).isEqualTo(ErrorCodes.ACCOUNT_LOCKED);
        assertThat(n.bank.transfer(s, Node.AN, Node.BINH, req(), m(21_000_000), "").getErrorCode()).isEqualTo(ErrorCodes.LIMIT_EXCEEDED);
        assertThat(n.balance(Node.AN)).isEqualByComparingTo("50000000");
        assertThat(n.balance(Node.BINH)).isEqualByComparingTo("25000000");
    }

    @Test
    void transferRetryIsIdempotent() {
        String s = n.login(Node.AN);
        String id = req();
        n.bank.transfer(s, Node.AN, Node.BINH, id, m(1_000_000), "");
        n.bank.transfer(s, Node.AN, Node.BINH, id, m(1_000_000), "");
        assertThat(n.balance(Node.AN)).isEqualByComparingTo("49000000");
        assertThat(n.balance(Node.BINH)).isEqualByComparingTo("26000000");
    }

    @Test
    void totalMoneyIsConservedByTransfers() {
        String s = n.login(Node.CUONG);
        BigDecimal before = n.balance(Node.CUONG).add(n.balance(Node.EM));
        for (int i = 0; i < 20; i++) n.bank.transfer(s, Node.CUONG, Node.EM, req(), m(500_000), "");
        assertThat(n.balance(Node.CUONG).add(n.balance(Node.EM))).isEqualByComparingTo(before);
    }

    // ------------------------------------------------------------------------------- authorization

    @Test
    void aSessionMayOnlyOperateOnItsOwnAccount() {
        String s = n.login(Node.AN);
        assertThat(n.bank.getAccount(s, Node.BINH).getErrorCode()).isEqualTo(ErrorCodes.NOT_ACCOUNT_OWNER);
        assertThat(n.bank.withdraw(s, Node.BINH, req(), m(1000), "").getErrorCode()).isEqualTo(ErrorCodes.NOT_ACCOUNT_OWNER);
        assertThat(n.bank.getRecentTransactions(s, Node.BINH, 10).getErrorCode()).isEqualTo(ErrorCodes.NOT_ACCOUNT_OWNER);
        assertThat(n.balance(Node.BINH)).isEqualByComparingTo("25000000");
    }

    @Test
    void callsWithoutAValidSessionAreRefused() {
        assertThat(n.bank.getAccount("nope-nope-nope", Node.AN).getErrorCode()).isEqualTo(ErrorCodes.INVALID_SESSION);
        assertThat(n.bank.deposit(null, Node.AN, req(), m(1000), "").getErrorCode()).isEqualTo(ErrorCodes.INVALID_SESSION);
        String s = n.login(Node.AN);
        n.bank.logout(s);
        assertThat(n.bank.getAccount(s, Node.AN).getErrorCode()).isEqualTo(ErrorCodes.INVALID_SESSION);
    }

    @Test
    void lookupShowsBeneficiaryNameOnly() {
        String s = n.login(Node.AN);
        assertThat(n.bank.lookupAccountHolder(s, Node.CUONG).getData()).isEqualTo("Lê Hoàng Cường");
        assertThat(n.bank.lookupAccountHolder(s, "5555555555").getErrorCode()).isEqualTo(ErrorCodes.ACCOUNT_NOT_FOUND);
    }

    // ------------------------------------------------------------------------------- single session

    @Test
    void secondLoginOnAnotherDeviceEvictsTheFirstSession() {
        Node.RecordingCallback cb1 = new Node.RecordingCallback();
        String s1 = n.login(Node.AN, "device-one-0001", cb1);
        assertThat(n.bank.getAccount(s1, Node.AN).isSuccess()).isTrue();
        String s2 = n.login(Node.AN, "device-two-0002", null);
        OperationResult<AccountDTO> old = n.bank.getAccount(s1, Node.AN);
        assertThat(old.getErrorCode()).isEqualTo(ErrorCodes.SESSION_EVICTED);
        assertThat(old.getMessage()).contains("thiết bị khác");
        assertThat(cb1.evictions).hasSize(1);               // pushed immediately, not discovered by polling
        assertThat(n.bank.getAccount(s2, Node.AN).isSuccess()).isTrue();
        assertThat(n.sessions.activeCount()).isEqualTo(1);
    }

    @Test
    void reconnectOfTheSameDeviceIsNotTreatedAsAnotherDevice() {
        Node.RecordingCallback cb = new Node.RecordingCallback();
        String s1 = n.login(Node.AN, "device-one-0001", cb);
        String s2 = n.login(Node.AN, "device-one-0001", cb);
        assertThat(cb.evictions).isEmpty();
        assertThat(n.bank.getAccount(s1, Node.AN).getErrorCode()).isEqualTo(ErrorCodes.INVALID_SESSION);
        assertThat(n.bank.getAccount(s2, Node.AN).isSuccess()).isTrue();
    }

    // ----------------------------------------------------------------------------------- push

    @Test
    void balanceChangesArePushedToTheOwnersDevices() {
        Node.RecordingCallback an = new Node.RecordingCallback();
        Node.RecordingCallback cuong = new Node.RecordingCallback();
        String sa = n.login(Node.AN, "device-an-000001", an);
        n.login(Node.CUONG, "device-cuong-0001", cuong);
        n.bank.transfer(sa, Node.AN, Node.CUONG, req(), m(2_000_000), "hi");
        assertThat(an.balances).hasSize(1);
        assertThat(an.balances.get(0).getDelta()).isEqualByComparingTo("-2000000");
        assertThat(an.balances.get(0).getNewBalance()).isEqualByComparingTo("48000000");
        assertThat(cuong.balances).hasSize(1);
        assertThat(cuong.balances.get(0).getDelta()).isEqualByComparingTo("2000000");
        assertThat(cuong.balances.get(0).getCounterparty()).isEqualTo(Node.AN);
    }

    @Test
    void aBrokenCallbackNeverBreaksTheTransaction() {
        com.nexus.banking.contract.remote.BankingCallback dead = new com.nexus.banking.contract.remote.BankingCallback() {
            @Override public void onBalanceChanged(com.nexus.banking.contract.dto.BalanceNotificationDTO x) throws java.rmi.RemoteException { throw new java.rmi.ConnectException("gone"); }
            @Override public void onSessionEvicted(String r) throws java.rmi.RemoteException { throw new java.rmi.ConnectException("gone"); }
            @Override public void onAccountStatusChanged(String a, com.nexus.banking.contract.enums.AccountStatus s, String m) throws java.rmi.RemoteException { throw new java.rmi.ConnectException("gone"); }
            @Override public void ping() throws java.rmi.RemoteException { throw new java.rmi.ConnectException("gone"); }
        };
        String s = n.login(Node.AN, "device-dead-0001", dead);
        assertThat(n.bank.deposit(s, Node.AN, req(), m(1000), "").isSuccess()).isTrue();
        assertThat(n.notifier.getDropped()).isEqualTo(1);
        assertThat(n.sessions.listLive().get(0).isPushEnabled()).isFalse();
        assertThat(n.bank.deposit(s, Node.AN, req(), m(1000), "").isSuccess()).isTrue();
    }

    @Test
    void largeMovementsAreAudited() {
        String s = n.login(Node.AN);
        n.bank.withdraw(s, Node.AN, req(), m(12_000_000), "");
        assertThat(n.auditActions()).contains("LARGE_WITHDRAW");
    }
}
