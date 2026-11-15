package com.nexus.banking.client.core;

import com.nexus.banking.client.model.ConnectionState;
import com.nexus.banking.contract.dto.AccountDTO;
import com.nexus.banking.contract.dto.BalanceNotificationDTO;
import com.nexus.banking.contract.dto.ErrorCodes;
import com.nexus.banking.contract.dto.LoginResultDTO;
import com.nexus.banking.contract.dto.OperationResult;
import com.nexus.banking.contract.dto.TransactionDTO;
import com.nexus.banking.contract.enums.AccountStatus;
import com.nexus.banking.contract.remote.BankingCallback;
import com.nexus.banking.contract.remote.BankingService;

import java.io.IOException;
import java.math.BigDecimal;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * UI-independent logic of the customer app: login with failover between the two branches, polling as a safety net
 * behind the RMI push channel, and - above all - SAFE money movement over an unreliable network.
 *
 * <p><b>Exactly-once money:</b> every deposit/withdraw/transfer gets ONE request id when the customer confirms it.
 * If the connection breaks after the server may already have executed the operation, the core reconnects (possibly to
 * the other branch) and re-sends the SAME request id; the server answers with the original transaction instead of
 * moving money again. When the outcome stays unknown the customer gets a {@link Pending} handle that can be retried
 * later, still without any risk of a double debit.</p>
 *
 * <p>Every remote operation runs on ONE serialized executor, so login, polling, money movement and pushes can never
 * interleave. Listener callbacks are invoked on that executor; UI adapters must hop to their own thread.</p>
 */
public final class BankingClientCore implements AutoCloseable {

    public record Endpoint(String label, String host, int port) {
    }

    @FunctionalInterface
    public interface Connector {
        BankingService connect(String host, int port) throws Exception;
    }

    /** Makes the push receiver reachable from the branch (real RMI export in production, identity in tests). */
    public interface CallbackExporter {
        default void prepareFor(String serverHost, int serverPort) {
        }

        default boolean canExport() {
            return true;
        }

        BankingCallback export(BankingCallback impl) throws Exception;

        default void unexport(BankingCallback impl) {
        }
    }

    public interface Listener {
        void onState(ConnectionState state, String status);

        /** A session was (re)established: which branch serves the customer now. */
        void onLogin(LoginResultDTO login, boolean pushActive);

        /** Authoritative balance/limit/status. {@code viaPush} is true for the instant update from a callback. */
        void onAccount(AccountDTO account, boolean viaPush);

        void onTransactions(List<TransactionDTO> newestFirst);

        /** Money came in or went out (callback). */
        void onNotification(BalanceNotificationDTO notification);

        /** Another device/branch/operator ended this session; the core has stopped. */
        void onEvicted(String reason);
    }

    public enum Kind { DEPOSIT, WITHDRAW, TRANSFER }

    /** A confirmed customer instruction together with its idempotency key. */
    public record Pending(Kind kind, String toAccount, BigDecimal amount, String note, String requestId) {
    }

    public enum Status { SUCCESS, REJECTED, UNKNOWN }

    /**
     * Result of a money operation. SUCCESS: committed ({@code replayed} when the server recognised a retry). REJECTED: a
     * definitive refusal, nothing moved. UNKNOWN: the network never confirmed; {@code pending} can be retried safely.
     */
    public record TxOutcome(Status status, TransactionDTO transaction, String errorCode, String message,
                            boolean replayed, Pending pending) {
    }

    public static final String REPLAY_MARKER = "lặp lại";
    static final int MAX_ATTEMPTS = 3;
    private static final int HISTORY = 50;
    private static final int RECONCILE_EVERY = 4;

    private final Connector connector;
    private final CallbackExporter exporter;
    private final Listener listener;
    private final Executor io;
    private final LongSupplier clock;
    private final long backoffBaseMillis;
    private final long backoffMaxMillis;
    private final String instanceId = UUID.randomUUID().toString();
    private ScheduledExecutorService scheduler;
    private ExecutorService ownedIo;

    private volatile ConnectionState state = ConnectionState.DISCONNECTED;
    private volatile boolean autoReconnect = true;
    private List<Endpoint> endpoints = List.of();
    private int endpointIndex;
    private String accountNumber;
    private String pin;
    private BankingService remote;
    private volatile String sessionId;
    private String serverId = "";
    private String branchName = "";
    private BankingCallback receiver;
    private BankingCallback receiverStub;
    private boolean pushActive;
    private long reconnectAttempt;
    private long nextReconnectAt;
    private int tickCount;
    private volatile Pending lastUnknown;
    private AccountDTO lastAccount;

    /** Test constructor: caller supplies executor and clock and calls {@link #tick()} itself. */
    public BankingClientCore(Connector connector, CallbackExporter exporter, Listener listener, Executor io,
                             LongSupplier clock, long backoffBaseMillis, long backoffMaxMillis) {
        this.connector = connector;
        this.exporter = exporter;
        this.listener = listener;
        this.io = io;
        this.clock = clock;
        this.backoffBaseMillis = backoffBaseMillis;
        this.backoffMaxMillis = backoffMaxMillis;
    }

    /** Production instance: serialized IO thread plus a poll tick. */
    public static BankingClientCore start(Connector connector, CallbackExporter exporter, Listener listener, long pollMillis) {
        ExecutorService io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "nexus-bank-io");
            t.setDaemon(true);
            return t;
        });
        BankingClientCore core = new BankingClientCore(connector, exporter, listener, io, System::currentTimeMillis, 1000, 10000);
        core.ownedIo = io;
        core.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nexus-bank-tick");
            t.setDaemon(true);
            return t;
        });
        core.scheduler.scheduleWithFixedDelay(() -> io.execute(core::tick), pollMillis, pollMillis, TimeUnit.MILLISECONDS);
        return core;
    }

    public ConnectionState getState() { return state; }
    public String getInstanceId() { return instanceId; }
    public boolean isPushActive() { return pushActive; }
    public Pending getLastUnknown() { return lastUnknown; }
    public void setAutoReconnect(boolean v) { this.autoReconnect = v; }

    private <T> CompletableFuture<T> submit(Callable<T> task) {
        CompletableFuture<T> f = new CompletableFuture<>();
        try {
            io.execute(() -> {
                try {
                    f.complete(task.call());
                } catch (Throwable t) {
                    f.completeExceptionally(t);
                }
            });
        } catch (RuntimeException e) {
            f.completeExceptionally(e);
        }
        return f;
    }

    private void setState(ConnectionState s, String status) {
        state = s;
        listener.onState(s, status);
    }

    // --------------------------------------------------------------------------------------------- login

    /** Rejections that make trying the other branch pointless (the answer would be the same). */
    private static final Set<String> DEFINITIVE = Set.of(ErrorCodes.AUTH_FAILED, ErrorCodes.TOO_MANY_ATTEMPTS,
            ErrorCodes.ACCOUNT_LOCKED, ErrorCodes.VALIDATION);

    private static final class LoginRejected extends Exception {
        final String code;

        LoginRejected(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    public CompletableFuture<OperationResult<LoginResultDTO>> login(List<Endpoint> endpoints, int preferredIndex,
                                                                    String accountNumber, String pin) {
        return submit(() -> {
            teardown(false);
            this.endpoints = List.copyOf(endpoints);
            this.endpointIndex = Math.max(0, Math.min(preferredIndex, endpoints.size() - 1));
            this.accountNumber = accountNumber;
            this.pin = pin;
            this.lastUnknown = null;
            setState(ConnectionState.CONNECTING, "Đang kết nối tới " + endpoints.get(endpointIndex).label() + "...");
            try {
                LoginResultDTO r = establish();
                return OperationResult.success(r.getSession() == null ? "" : "Đăng nhập thành công", r);
            } catch (LoginRejected e) {
                this.pin = null;
                setState(ConnectionState.FAILED, e.getMessage());
                return OperationResult.<LoginResultDTO>failure(e.code, e.getMessage());
            } catch (Exception e) {
                setState(ConnectionState.FAILED, "Không kết nối được: " + describe(e));
                return OperationResult.<LoginResultDTO>failure("CONNECTION_FAILED", describe(e));
            }
        });
    }

    /** Tries the preferred branch, then the other one. Throws LoginRejected for definitive refusals. */
    private LoginResultDTO establish() throws Exception {
        Exception last = null;
        int n = endpoints.size();
        for (int i = 0; i < n; i++) {
            int idx = (endpointIndex + i) % n;
            Endpoint ep = endpoints.get(idx);
            try {
                if (exporter != null) exporter.prepareFor(ep.host(), ep.port());
                BankingService r = connector.connect(ep.host(), ep.port());
                BankingCallback stub = null;
                if (exporter != null && exporter.canExport()) {
                    try {
                        if (receiver == null) receiver = new Receiver();
                        if (receiverStub == null) receiverStub = exporter.export(receiver);
                        stub = receiverStub;
                    } catch (Exception e) {
                        stub = null;        // push is optional: polling keeps the app correct
                    }
                }
                OperationResult<LoginResultDTO> res = r.login(accountNumber, pin, instanceId, stub);
                if (!res.isSuccess()) {
                    if (DEFINITIVE.contains(res.getErrorCode())) throw new LoginRejected(res.getErrorCode(), res.getMessage());
                    last = new IOException(res.getMessage());
                    continue;
                }
                this.remote = r;
                this.endpointIndex = idx;
                this.sessionId = res.getData().getSession().getSessionId();
                this.serverId = res.getData().getServerId();
                this.branchName = res.getData().getBranchName();
                this.pushActive = res.getData().isCallbackRegistered();
                this.reconnectAttempt = 0;
                setState(ConnectionState.CONNECTED, "Đã kết nối " + branchName);
                listener.onLogin(res.getData(), pushActive);
                emitAccount(res.getData().getAccount(), false);
                refresh();
                return res.getData();
            } catch (LoginRejected e) {
                throw e;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("Không có chi nhánh nào để kết nối");
    }

    public String getBranchName() { return branchName; }
    public String getServerId() { return serverId; }
    public String getAccountNumber() { return accountNumber; }
    public List<Endpoint> getEndpoints() { return endpoints; }
    public int getEndpointIndex() { return endpointIndex; }

    // ------------------------------------------------------------------------------------------ polling

    /** One poll or reconnect step. Must run on the serialized executor. */
    public void tick() {
        try {
            if (state == ConnectionState.CONNECTED) {
                tickCount++;
                if (!pushActive || tickCount % RECONCILE_EVERY == 0) refresh();
                else heartbeat();       // push is working: a cheap liveness check still detects a dead branch or eviction fast
            } else if (state == ConnectionState.RECONNECTING && clock.getAsLong() >= nextReconnectAt) {
                reconnectStep();
            }
        } catch (Throwable t) {
            startReconnect("Lỗi: " + describe(t));
        }
    }

    private void heartbeat() {
        try {
            OperationResult<Void> r = remote.heartbeat(sessionId);
            if (!r.isSuccess()) handleFailure(r.getErrorCode(), r.getMessage());
        } catch (RemoteException e) {
            startReconnect("Mất kết nối: " + describe(e));
        }
    }

    /** Fetches the authoritative balance and history. */
    private void refresh() {
        if (remote == null || sessionId == null) return;
        try {
            OperationResult<AccountDTO> a = remote.getAccount(sessionId, accountNumber);
            if (!a.isSuccess()) {
                handleFailure(a.getErrorCode(), a.getMessage());
                return;
            }
            emitAccount(a.getData(), false);
            OperationResult<List<TransactionDTO>> t = remote.getRecentTransactions(sessionId, accountNumber, HISTORY);
            if (t.isSuccess()) listener.onTransactions(t.getData());
            else handleFailure(t.getErrorCode(), t.getMessage());
        } catch (RemoteException e) {
            startReconnect("Mất kết nối: " + describe(e));
        }
    }

    public CompletableFuture<Void> refreshNow() {
        return submit(() -> {
            if (state == ConnectionState.CONNECTED) refresh();
            return null;
        });
    }

    private void emitAccount(AccountDTO a, boolean viaPush) {
        if (!viaPush) lastAccount = a;
        listener.onAccount(a, viaPush);
    }

    private void handleFailure(String code, String message) {
        if (ErrorCodes.SESSION_EVICTED.equals(code)) evicted(message);
        else if (ErrorCodes.INVALID_SESSION.equals(code)) startReconnect("Phiên hết hạn");
        // other errors are reported where they happened
    }

    private void evicted(String reason) {
        if (state == ConnectionState.EVICTED) return;
        teardown(true);
        setState(ConnectionState.EVICTED, reason);
        listener.onEvicted(reason);
    }

    // ------------------------------------------------------------------------------------- reconnection

    private void startReconnect(String reason) {
        if (state == ConnectionState.EVICTED || state == ConnectionState.DISCONNECTED || pin == null) return;
        remote = null;
        sessionId = null;
        pushActive = false;
        if (!autoReconnect) {
            setState(ConnectionState.FAILED, reason + ". Tự động kết nối lại đang tắt.");
            return;
        }
        if (state != ConnectionState.RECONNECTING) reconnectAttempt = 0;
        reconnectAttempt++;
        long delay = Math.min(backoffMaxMillis, backoffBaseMillis * (1L << Math.min(reconnectAttempt - 1, 10)));
        nextReconnectAt = clock.getAsLong() + delay;
        // fail over: the next attempt starts at the OTHER branch
        if (endpoints.size() > 1) endpointIndex = (endpointIndex + 1) % endpoints.size();
        setState(ConnectionState.RECONNECTING, reason + " - thử lại (lần " + reconnectAttempt + ")");
    }

    private void reconnectStep() {
        try {
            establish();
        } catch (LoginRejected e) {
            pin = null;
            setState(ConnectionState.FAILED, e.getMessage());
            evictedIfLocked(e);
        } catch (Exception e) {
            long delay = Math.min(backoffMaxMillis, backoffBaseMillis * (1L << Math.min(reconnectAttempt, 10)));
            reconnectAttempt++;
            nextReconnectAt = clock.getAsLong() + delay;
            if (endpoints.size() > 1) endpointIndex = (endpointIndex + 1) % endpoints.size();
            setState(ConnectionState.RECONNECTING, "Chưa kết nối lại được: " + describe(e) + " (lần " + reconnectAttempt + ")");
        }
    }

    private void evictedIfLocked(LoginRejected e) {
        if (ErrorCodes.ACCOUNT_LOCKED.equals(e.code)) listener.onEvicted(e.getMessage());
    }

    // -------------------------------------------------------------------------------------- money movement

    public CompletableFuture<TxOutcome> deposit(BigDecimal amount, String note) {
        return money(new Pending(Kind.DEPOSIT, null, amount, note, UUID.randomUUID().toString()));
    }

    public CompletableFuture<TxOutcome> withdraw(BigDecimal amount, String note) {
        return money(new Pending(Kind.WITHDRAW, null, amount, note, UUID.randomUUID().toString()));
    }

    public CompletableFuture<TxOutcome> transfer(String toAccount, BigDecimal amount, String note) {
        return money(new Pending(Kind.TRANSFER, toAccount, amount, note, UUID.randomUUID().toString()));
    }

    /** Re-sends an instruction whose outcome was unknown, with the SAME request id. */
    public CompletableFuture<TxOutcome> retry(Pending p) {
        return money(p);
    }

    private CompletableFuture<TxOutcome> money(Pending p) {
        return submit(() -> execute(p));
    }

    private TxOutcome execute(Pending p) {
        String lastProblem = "Chưa nhận được xác nhận từ chi nhánh";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (state == ConnectionState.EVICTED) {
                return new TxOutcome(Status.REJECTED, null, ErrorCodes.SESSION_EVICTED, "Phiên đã bị đóng", false, null);
            }
            if (remote == null || state != ConnectionState.CONNECTED) {
                try {
                    if (pin == null) break;
                    if (attempt > 1 && endpoints.size() > 1) endpointIndex = (endpointIndex + 1) % endpoints.size();
                    establish();
                } catch (LoginRejected e) {
                    return new TxOutcome(Status.REJECTED, null, e.code, e.getMessage(), false, null);
                } catch (Exception e) {
                    lastProblem = describe(e);
                    continue;
                }
            }
            try {
                OperationResult<TransactionDTO> r = call(p);
                if (r.isSuccess()) {
                    lastUnknown = null;
                    refresh();
                    boolean replayed = r.getMessage() != null && r.getMessage().contains(REPLAY_MARKER);
                    return new TxOutcome(Status.SUCCESS, r.getData(), null, r.getMessage(), replayed, null);
                }
                if (ErrorCodes.SESSION_EVICTED.equals(r.getErrorCode())) {
                    evicted(r.getMessage());
                    return new TxOutcome(Status.REJECTED, null, r.getErrorCode(), r.getMessage(), false, null);
                }
                if (ErrorCodes.INVALID_SESSION.equals(r.getErrorCode())) {
                    startReconnect("Phiên hết hạn");        // the request may not have run: re-login, same request id
                    lastProblem = r.getMessage();
                    continue;
                }
                if (ErrorCodes.STORAGE_UNAVAILABLE.equals(r.getErrorCode())) {
                    lastProblem = r.getMessage();          // server says nothing was executed; same id is safe to repeat
                    continue;
                }
                return new TxOutcome(Status.REJECTED, null, r.getErrorCode(), r.getMessage(), false, null);
            } catch (RemoteException e) {
                lastProblem = describe(e);
                startReconnect("Mất kết nối khi giao dịch: " + describe(e));
                // the server may or may not have executed it: fall through and retry with the SAME request id
            }
        }
        lastUnknown = p;
        return new TxOutcome(Status.UNKNOWN, null, "OUTCOME_UNKNOWN",
                "Chưa xác định được kết quả giao dịch (" + lastProblem + "). Bạn có thể thử lại: hệ thống nhận diện mã yêu cầu nên "
                        + "sẽ KHÔNG trừ tiền 2 lần.", false, p);
    }

    private OperationResult<TransactionDTO> call(Pending p) throws RemoteException {
        return switch (p.kind()) {
            case DEPOSIT -> remote.deposit(sessionId, accountNumber, p.requestId(), p.amount(), p.note());
            case WITHDRAW -> remote.withdraw(sessionId, accountNumber, p.requestId(), p.amount(), p.note());
            case TRANSFER -> remote.transfer(sessionId, accountNumber, p.toAccount(), p.requestId(), p.amount(), p.note());
        };
    }

    /** Beneficiary name lookup for the transfer screen. */
    public CompletableFuture<OperationResult<String>> lookupHolder(String account) {
        return submit(() -> {
            if (remote == null || state != ConnectionState.CONNECTED) {
                return OperationResult.<String>failure("NOT_CONNECTED", "Chưa kết nối tới chi nhánh");
            }
            try {
                return remote.lookupAccountHolder(sessionId, account);
            } catch (RemoteException e) {
                startReconnect("Mất kết nối: " + describe(e));
                return OperationResult.<String>failure("CONNECTION_LOST", describe(e));
            }
        });
    }

    /** Manual switch to the other branch (demo of active/active). */
    public CompletableFuture<OperationResult<LoginResultDTO>> switchBranch() {
        return submit(() -> {
            if (pin == null || endpoints.size() < 2) {
                return OperationResult.<LoginResultDTO>failure("NOT_AVAILABLE", "Không có chi nhánh khác để chuyển");
            }
            BankingService old = remote;
            String oldSession = sessionId;
            endpointIndex = (endpointIndex + 1) % endpoints.size();
            try {
                LoginResultDTO r = establish();
                try {
                    if (old != null && oldSession != null) old.logout(oldSession);
                } catch (Exception ignored) {
                    // the old branch is only being politely told; the new session already replaced it
                }
                return OperationResult.success("Đã chuyển sang " + r.getBranchName(), r);
            } catch (LoginRejected e) {
                return OperationResult.<LoginResultDTO>failure(e.code, e.getMessage());
            } catch (Exception e) {
                endpointIndex = (endpointIndex + endpoints.size() - 1) % endpoints.size();
                return OperationResult.<LoginResultDTO>failure("CONNECTION_FAILED", describe(e));
            }
        });
    }

    // ------------------------------------------------------------------------------------------- logout

    public CompletableFuture<Void> logout() {
        return submit(() -> {
            teardown(true);
            setState(ConnectionState.DISCONNECTED, "Đã đăng xuất");
            return null;
        });
    }

    private void teardown(boolean politely) {
        BankingService r = remote;
        String sid = sessionId;
        remote = null;
        sessionId = null;
        pushActive = false;
        pin = politely ? null : pin;
        if (politely && r != null && sid != null) {
            try {
                r.logout(sid);
            } catch (Exception ignored) {
                // best effort
            }
        }
        if (politely && receiverStub != null && exporter != null) {
            exporter.unexport(receiver);
            receiverStub = null;
            receiver = null;
        }
    }

    @Override
    public void close() {
        try {
            submit(() -> {
                teardown(true);
                return null;
            }).get(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // shutting down anyway
        }
        if (scheduler != null) scheduler.shutdownNow();
        if (ownedIo != null) ownedIo.shutdownNow();
    }

    // ------------------------------------------------------------------------------------------- pushes

    /** The remote object the branch calls. Each push hops onto the serialized executor. */
    public final class Receiver implements BankingCallback {
        @Override
        public void onBalanceChanged(BalanceNotificationDTO n) {
            io.execute(() -> {
                if (state != ConnectionState.CONNECTED || !n.getAccountNumber().equals(accountNumber)) return;
                listener.onNotification(n);
                // instant update from the push; the refresh below replaces it with the authoritative values
                if (lastAccount != null) emitAccount(lastAccount.withBalance(n.getNewBalance()), true);
                refresh();
            });
        }

        @Override
        public void onSessionEvicted(String reason) {
            io.execute(() -> evicted(reason));
        }

        @Override
        public void onAccountStatusChanged(String account, AccountStatus status, String message) {
            io.execute(() -> {
                if (status == AccountStatus.LOCKED) evicted(message);
                else refresh();
            });
        }

        @Override
        public void ping() {
        }
    }

    // ----------------------------------------------------------------------------------------- helpers

    static String describe(Throwable e) {
        // our own RemoteException messages are already written for the customer
        if (e.getMessage() != null && e.getMessage().startsWith("Không kết nối được")) return e.getMessage();
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String m = root.getMessage();
        return m == null || m.isBlank() ? root.getClass().getSimpleName() : m;
    }

    /** For tests. */
    List<Pending> pendingForTest() {
        return lastUnknown == null ? new ArrayList<>() : List.of(lastUnknown);
    }
}
