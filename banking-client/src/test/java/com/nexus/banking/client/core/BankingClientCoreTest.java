package com.nexus.banking.client.core;

import com.nexus.banking.client.core.BankingClientCore.Endpoint;
import com.nexus.banking.client.core.BankingClientCore.Status;
import com.nexus.banking.client.core.BankingClientCore.TxOutcome;
import com.nexus.banking.client.model.ConnectionState;
import com.nexus.banking.contract.dto.AccountDTO;
import com.nexus.banking.contract.dto.BalanceNotificationDTO;
import com.nexus.banking.contract.dto.ClientSessionDTO;
import com.nexus.banking.contract.dto.ErrorCodes;
import com.nexus.banking.contract.dto.LoginResultDTO;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.dto.TransactionDTO;
import com.nexus.banking.contract.enums.AccountStatus;
import com.nexus.banking.contract.enums.SessionStatus;
import com.nexus.banking.contract.enums.TransactionSyncState;
import com.nexus.banking.contract.enums.TransactionType;
import com.nexus.banking.contract.remote.BankingCallback;
import com.nexus.banking.contract.remote.BankingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.rmi.ConnectException;
import java.rmi.RemoteException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Core behaviour against a scriptable in-memory branch (no network, no threads: ticks are driven by the test). */
class BankingClientCoreTest {

    /** One shared "bank" so two fake branches see the same money, like replicated nodes. */
    static final class Books {
        BigDecimal balance = new BigDecimal("10000000.00");
        final Map<String, TransactionDTO> byRequest = new HashMap<>();
        final List<TransactionDTO> ledger = new ArrayList<>();
        int debits;
    }

    static final class FakeBranch implements BankingService {
        final String id;
        final Books books;
        boolean down;
        boolean dropResponseOnce;         // execute the operation, then lose the answer (the nasty case)
        boolean dropRequestOnce;          // lose the request before it executes
        boolean evictOnNextCall;
        boolean wrongPin;
        BankingCallback callback;
        int logins;
        String evictionReason = "Tài khoản đã đăng nhập ở thiết bị khác";

        FakeBranch(String id, Books books) {
            this.id = id;
            this.books = books;
        }

        private void gate() throws RemoteException {
            if (down) throw new ConnectException("branch " + id + " unreachable");
        }

        AccountDTO account() {
            return new AccountDTO("1001000001", "Nguyễn Văn An", books.balance, "HN", AccountStatus.ACTIVE, Instant.now(), new BigDecimal("20000000.00"));
        }

        @Override
        public OperationResult<LoginResultDTO> login(String acc, String pin, String instance, BankingCallback cb) throws RemoteException {
            gate();
            if (wrongPin) return OperationResult.failure(ErrorCodes.AUTH_FAILED, "Số tài khoản hoặc mã PIN không đúng");
            logins++;
            callback = cb;
            ClientSessionDTO s = new ClientSessionDTO("session-" + id + "-" + logins, acc, "Nguyễn Văn An", instance, "127.0.0.1",
                    Instant.now(), Instant.now(), SessionStatus.ACTIVE, id, cb != null);
            return OperationResult.success("ok", new LoginResultDTO(s, account(), id, "Chi nhánh " + id, cb != null));
        }

        private <T> OperationResult<T> sessionCheck() {
            if (evictOnNextCall) return OperationResult.failure(ErrorCodes.SESSION_EVICTED, evictionReason);
            return null;
        }

        @Override
        public OperationResult<AccountDTO> getAccount(String sid, String acc) throws RemoteException {
            gate();
            OperationResult<AccountDTO> e = sessionCheck();
            return e != null ? e : OperationResult.success(account());
        }

        @Override
        public OperationResult<String> lookupAccountHolder(String sid, String acc) throws RemoteException {
            gate();
            return OperationResult.success("Lê Hoàng Cường");
        }

        private OperationResult<TransactionDTO> book(TransactionType type, String request, BigDecimal amount, String to) throws RemoteException {
            gate();
            OperationResult<TransactionDTO> e = sessionCheck();
            if (e != null) return e;
            if (dropRequestOnce) {
                dropRequestOnce = false;
                throw new ConnectException("request lost");
            }
            TransactionDTO existing = books.byRequest.get(request);
            OperationResult<TransactionDTO> result;
            if (existing != null) {
                result = OperationResult.success("Giao dịch đã được xử lý trước đó (yêu cầu lặp lại) - không trừ tiền lần 2", existing);
            } else if (type != TransactionType.DEPOSIT && books.balance.compareTo(amount) < 0) {
                return OperationResult.failure(ErrorCodes.INSUFFICIENT_FUNDS, "Số dư không đủ");
            } else {
                books.balance = type == TransactionType.DEPOSIT ? books.balance.add(amount) : books.balance.subtract(amount);
                if (type != TransactionType.DEPOSIT) books.debits++;
                TransactionDTO tx = new TransactionDTO(UUID.randomUUID().toString(), "1001000001", type, amount, books.balance,
                        to == null ? "" : to, "", Instant.now(), id, books.ledger.size() + 1, books.ledger.size() + 1, request,
                        TransactionSyncState.PENDING);
                books.byRequest.put(request, tx);
                books.ledger.add(0, tx);
                result = OperationResult.success("Giao dịch thành công", tx);
            }
            if (dropResponseOnce) {
                dropResponseOnce = false;
                throw new ConnectException("response lost after the server executed the request");
            }
            return result;
        }

        @Override
        public OperationResult<TransactionDTO> deposit(String sid, String acc, String req, BigDecimal amount, String note) throws RemoteException {
            return book(TransactionType.DEPOSIT, req, amount, null);
        }

        @Override
        public OperationResult<TransactionDTO> withdraw(String sid, String acc, String req, BigDecimal amount, String note) throws RemoteException {
            return book(TransactionType.WITHDRAW, req, amount, null);
        }

        @Override
        public OperationResult<TransactionDTO> transfer(String sid, String from, String to, String req, BigDecimal amount, String note) throws RemoteException {
            return book(TransactionType.TRANSFER, req, amount, to);
        }

        @Override
        public OperationResult<List<TransactionDTO>> getRecentTransactions(String sid, String acc, int limit) throws RemoteException {
            gate();
            OperationResult<List<TransactionDTO>> e = sessionCheck();
            return e != null ? e : OperationResult.success(new ArrayList<>(books.ledger));
        }

        @Override
        public OperationResult<Void> heartbeat(String sid) throws RemoteException {
            gate();
            OperationResult<Void> e = sessionCheck();
            return e != null ? e : OperationResult.ok();
        }

        @Override
        public OperationResult<Void> logout(String sid) { return OperationResult.ok(); }

        @Override
        public String getServerId() { return id; }
    }

    static final class Recorder implements BankingClientCore.Listener {
        final List<String> states = new ArrayList<>();
        final List<String> logins = new ArrayList<>();
        final List<AccountDTO> accounts = new ArrayList<>();
        final List<Boolean> viaPush = new ArrayList<>();
        List<TransactionDTO> lastTx = List.of();
        final List<BalanceNotificationDTO> notes = new ArrayList<>();
        final List<String> evictions = new ArrayList<>();

        @Override public void onState(ConnectionState s, String status) { states.add(s.name()); }
        @Override public void onLogin(LoginResultDTO l, boolean push) { logins.add(l.getServerId() + (push ? "+push" : "")); }
        @Override public void onAccount(AccountDTO a, boolean p) { accounts.add(a); viaPush.add(p); }
        @Override public void onTransactions(List<TransactionDTO> t) { lastTx = t; }
        @Override public void onNotification(BalanceNotificationDTO n) { notes.add(n); }
        @Override public void onEvicted(String r) { evictions.add(r); }

        AccountDTO lastAccount() { return accounts.get(accounts.size() - 1); }
    }

    private Books books;
    private FakeBranch a;
    private FakeBranch b;
    private Recorder rec;
    private BankingClientCore core;
    private final AtomicLong now = new AtomicLong(1_000);
    private final List<Endpoint> eps = List.of(new Endpoint("Hà Nội", "hn", 1), new Endpoint("TP.HCM", "hcm", 2));

    @BeforeEach
    void setUp() {
        books = new Books();
        a = new FakeBranch("server-a", books);
        b = new FakeBranch("server-b", books);
        rec = new Recorder();
        core = new BankingClientCore((host, port) -> {
            FakeBranch f = host.equals("hn") ? a : b;
            if (f.down) throw new ConnectException(host + " is down");
            return f;
        }, new BankingClientCore.CallbackExporter() {
            @Override public BankingCallback export(BankingCallback impl) { return impl; }
        }, rec, Runnable::run, now::get, 100, 1000);
    }

    private OperationResult<LoginResultDTO> login() {
        return core.login(eps, 0, "1001000001", "123456").join();
    }

    @Test
    void loginShowsAccountHistoryAndWhichBranchServes() {
        assertThat(login().isSuccess()).isTrue();
        assertThat(core.getState()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(rec.logins).containsExactly("server-a+push");
        assertThat(rec.lastAccount().getOwnerName()).isEqualTo("Nguyễn Văn An");
        assertThat(core.getBranchName()).isEqualTo("Chi nhánh server-a");
    }

    @Test
    void wrongPinIsReportedWithoutTryingTheOtherBranchOrRetrying() {
        a.wrongPin = true;
        OperationResult<LoginResultDTO> r = login();
        assertThat(r.getErrorCode()).isEqualTo(ErrorCodes.AUTH_FAILED);
        assertThat(core.getState()).isEqualTo(ConnectionState.FAILED);
        assertThat(b.logins).isZero();
    }

    @Test
    void ifTheNearestBranchIsDownLoginFailsOverToTheOther() {
        a.down = true;
        assertThat(login().isSuccess()).isTrue();
        assertThat(rec.logins).containsExactly("server-b+push");
        assertThat(core.getEndpointIndex()).isEqualTo(1);
    }

    @Test
    void withdrawalUpdatesBalanceFromTheServer() {
        login();
        TxOutcome o = core.withdraw(new BigDecimal("2000000.00"), "ATM").join();
        assertThat(o.status()).isEqualTo(Status.SUCCESS);
        assertThat(o.replayed()).isFalse();
        assertThat(rec.lastAccount().getBalance()).isEqualByComparingTo("8000000");
        assertThat(rec.lastTx).hasSize(1);
    }

    @Test
    void definitiveRefusalsAreNotRetried() {
        login();
        TxOutcome o = core.withdraw(new BigDecimal("99000000.00"), "").join();
        assertThat(o.status()).isEqualTo(Status.REJECTED);
        assertThat(o.errorCode()).isEqualTo(ErrorCodes.INSUFFICIENT_FUNDS);
        assertThat(books.balance).isEqualByComparingTo("10000000");
    }

    @Test
    void aLostResponseIsRetriedWithTheSameRequestIdAndDebitsOnlyOnce() {
        login();
        a.dropResponseOnce = true;                    // server executes, the answer never arrives
        TxOutcome o = core.withdraw(new BigDecimal("3000000.00"), "mạng chập chờn").join();
        now.addAndGet(5_000);                         // reconnect back-off elapsed (the money op reconnects by itself)
        assertThat(o.status()).isEqualTo(Status.SUCCESS);
        assertThat(o.replayed()).isTrue();            // the server recognised the retry
        assertThat(books.debits).isEqualTo(1);        // ONE debit although the request was sent twice
        assertThat(books.balance).isEqualByComparingTo("7000000");
        assertThat(books.ledger).hasSize(1);
    }

    @Test
    void aLostRequestIsRetriedAndExecutedOnce() {
        login();
        a.dropRequestOnce = true;
        TxOutcome o = core.withdraw(new BigDecimal("1000000.00"), "").join();
        assertThat(o.status()).isEqualTo(Status.SUCCESS);
        assertThat(o.replayed()).isFalse();
        assertThat(books.debits).isEqualTo(1);
    }

    @Test
    void retryAfterBranchFailureGoesToTheOtherBranchAndStillDebitsOnce() {
        login();
        a.dropResponseOnce = true;                    // A executed the debit, then died
        BankingClientCore.Pending[] seen = new BankingClientCore.Pending[1];
        a.down = false;
        TxOutcome o = core.withdraw(new BigDecimal("500000.00"), "").join();
        assertThat(o.status()).isEqualTo(Status.SUCCESS);
        assertThat(books.debits).isEqualTo(1);
        assertThat(seen[0]).isNull();
        assertThat(rec.logins).hasSizeGreaterThanOrEqualTo(2);     // it logged in again to retry
    }

    @Test
    void whenTheOutcomeStaysUnknownTheCustomerCanRetrySafelyLater() {
        login();
        a.down = true;
        b.down = true;
        TxOutcome unknown = core.withdraw(new BigDecimal("4000000.00"), "").join();
        assertThat(unknown.status()).isEqualTo(Status.UNKNOWN);
        assertThat(unknown.pending()).isNotNull();
        assertThat(unknown.message()).contains("KHÔNG trừ tiền 2 lần");
        a.down = false;
        TxOutcome again = core.retry(unknown.pending()).join();
        assertThat(again.status()).isEqualTo(Status.SUCCESS);
        TxOutcome third = core.retry(unknown.pending()).join();       // pressing the button AGAIN is harmless too
        assertThat(third.replayed()).isTrue();
        assertThat(books.debits).isEqualTo(1);
        assertThat(books.balance).isEqualByComparingTo("6000000");
    }

    @Test
    void pollingFailsOverWhenTheServingBranchDiesAndRecovers() {
        login();
        a.down = true;
        core.tick();
        assertThat(core.getState()).isEqualTo(ConnectionState.RECONNECTING);
        now.addAndGet(500);
        core.tick();
        assertThat(core.getState()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(core.getServerId()).isEqualTo("server-b");
    }

    @Test
    void anEvictedSessionStopsEverythingAndTellsTheUser() {
        login();
        a.evictOnNextCall = true;
        core.tick();
        assertThat(core.getState()).isEqualTo(ConnectionState.EVICTED);
        assertThat(rec.evictions).hasSize(1).first().asString().contains("thiết bị khác");
        now.addAndGet(60_000);
        core.tick();
        assertThat(b.logins).isZero();                // no silent re-login that would kick the other device back
        assertThat(core.getState()).isEqualTo(ConnectionState.EVICTED);
    }

    @Test
    void aPushUpdatesTheBalanceInstantlyThenReconcilesWithTheServer() throws Exception {
        login();
        books.balance = books.balance.add(new BigDecimal("1000000.00"));
        BalanceNotificationDTO n = new BalanceNotificationDTO("1001000001", new BigDecimal("1000000.00"),
                books.balance, "t1", TransactionType.TRANSFER_IN, "2002000001", "tiền", "server-a", false, Instant.now());
        a.callback.onBalanceChanged(n);
        assertThat(rec.notes).hasSize(1);
        assertThat(rec.viaPush).contains(true);
        assertThat(rec.lastAccount().getBalance()).isEqualByComparingTo("11000000");
        assertThat(rec.lastAccount().getOwnerName()).isEqualTo("Nguyễn Văn An");   // push never blanks the account
    }

    @Test
    void aPushedEvictionEndsTheSession() throws Exception {
        login();
        a.callback.onSessionEvicted("Có thiết bị khác đăng nhập");
        assertThat(core.getState()).isEqualTo(ConnectionState.EVICTED);
        assertThat(rec.evictions).containsExactly("Có thiết bị khác đăng nhập");
    }

    @Test
    void manualSwitchMovesTheSessionToTheOtherBranch() {
        login();
        assertThat(core.switchBranch().join().isSuccess()).isTrue();
        assertThat(core.getServerId()).isEqualTo("server-b");
        assertThat(core.withdraw(new BigDecimal("1000000.00"), "").join().status()).isEqualTo(Status.SUCCESS);
        assertThat(books.ledger.get(0).getOriginServerId()).isEqualTo("server-b");
    }

    @Test
    void logoutClearsTheCredentialsFromMemory() {
        login();
        core.logout().join();
        assertThat(core.getState()).isEqualTo(ConnectionState.DISCONNECTED);
        TxOutcome o = core.withdraw(new BigDecimal("1000.00"), "").join();
        assertThat(o.status()).isNotEqualTo(Status.SUCCESS);
        assertThat(books.debits).isZero();
    }
}
