package com.nexus.banking.server.service;

import com.nexus.banking.contract.dto.BalanceNotificationDTO;
import com.nexus.banking.contract.dto.TransactionDTO;
import com.nexus.banking.contract.enums.AccountStatus;
import com.nexus.banking.contract.enums.EventSeverity;
import com.nexus.banking.contract.remote.BankingCallback;
import com.nexus.banking.server.monitoring.SystemLogService;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pushes events to customer devices through their RMI callbacks WITHOUT ever blocking a money movement: every push runs
 * on a separate pool, in order per session, and a callback that throws is dropped (the device keeps polling).
 */
public class CallbackNotifier implements AutoCloseable {
    private final String serverId;
    private final ClientSessionManager sessions;
    private final SystemLogService logService;
    private final ExecutorService pool;
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    /** Runs tasks of one session strictly one after another on the shared pool. */
    private final class Lane implements ClientSessionManager.SerialLane {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        private boolean running;

        @Override
        public void submit(Runnable r) {
            synchronized (this) {
                queue.add(r);
                if (running) return;
                running = true;
            }
            pool.execute(this::drain);
        }

        private void drain() {
            while (true) {
                Runnable next;
                synchronized (this) {
                    next = queue.poll();
                    if (next == null) {
                        running = false;
                        return;
                    }
                }
                try {
                    next.run();
                } catch (RuntimeException ignored) {
                    // every task handles its own failures
                }
            }
        }
    }

    public CallbackNotifier(String serverId, ClientSessionManager sessions, SystemLogService logService) {
        this(serverId, sessions, logService, true);
    }

    public CallbackNotifier(String serverId, ClientSessionManager sessions, SystemLogService logService, boolean async) {
        this.serverId = serverId;
        this.sessions = sessions;
        this.logService = logService;
        this.pool = async ? Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "nexus-callback");
            t.setDaemon(true);
            return t;
        }) : null;
        sessions.setLaneFactory(() -> async ? new Lane() : Runnable::run);
        sessions.setEvictionListener(this::evicted);
    }

    public long getDelivered() { return delivered.get(); }
    public long getDropped() { return dropped.get(); }

    private void push(ClientSessionManager.Session s, String what, Callback c) {
        BankingCallback cb = s.getCallback();
        if (cb == null) return;
        s.lane().submit(() -> {
            try {
                c.call(cb);
                delivered.incrementAndGet();
            } catch (Exception e) {
                dropped.incrementAndGet();
                s.dropCallback();
                logService.logEvent(EventSeverity.WARN, "CALLBACK", "Push '" + what + "' to account=" + s.getAccountNumber()
                        + " failed (" + e.getClass().getSimpleName() + "); the device falls back to polling");
            }
        });
    }

    @FunctionalInterface
    private interface Callback {
        void call(BankingCallback cb) throws Exception;
    }

    /** Pushes the effect of one ledger entry to every live device of its account. */
    public void balanceChanged(TransactionDTO tx, boolean fromPeerBranch) {
        for (ClientSessionManager.Session s : sessions.liveSessionsOf(tx.getAccountNumber())) {
            BalanceNotificationDTO n = new BalanceNotificationDTO(tx.getAccountNumber(), tx.getSignedAmount(),
                    tx.getBalanceAfter(), tx.getTransactionId(), tx.getType(), tx.getReferenceAccount(), tx.getNote(),
                    serverId, fromPeerBranch, Instant.now());
            push(s, "balance", cb -> cb.onBalanceChanged(n));
        }
    }

    public void accountStatus(String accountNumber, AccountStatus status, String message) {
        for (ClientSessionManager.Session s : sessions.liveSessionsOf(accountNumber)) {
            push(s, "status", cb -> cb.onAccountStatusChanged(accountNumber, status, message));
        }
    }

    private void evicted(ClientSessionManager.Session s, String reason) {
        push(s, "evicted", cb -> cb.onSessionEvicted(reason));
    }

    @Override
    public void close() {
        if (pool != null) pool.shutdownNow();
    }
}
