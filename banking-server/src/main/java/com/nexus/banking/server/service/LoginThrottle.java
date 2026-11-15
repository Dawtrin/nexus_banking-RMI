package com.nexus.banking.server.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Slows PIN guessing: after {@code maxFailures} wrong PINs an account is refused for {@code lockout}. */
public class LoginThrottle {
    private static final class State {
        int failures;
        Instant blockedUntil = Instant.EPOCH;
    }

    private final int maxFailures;
    private final Duration lockout;
    private final Supplier<Instant> clock;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public LoginThrottle(int maxFailures, Duration lockout, Supplier<Instant> clock) {
        this.maxFailures = maxFailures;
        this.lockout = lockout;
        this.clock = clock;
    }

    public LoginThrottle() {
        this(5, Duration.ofSeconds(60), Instant::now);
    }

    /** Seconds the caller must still wait, or 0 when a login attempt is allowed. */
    public synchronized long secondsBlocked(String account) {
        State s = states.get(account);
        if (s == null) return 0;
        long left = Duration.between(clock.get(), s.blockedUntil).toSeconds() + 1;
        return Math.max(0, left);
    }

    /** Returns true when this failure triggered the temporary lockout. */
    public synchronized boolean recordFailure(String account) {
        State s = states.computeIfAbsent(account, k -> new State());
        if (++s.failures >= maxFailures) {
            s.failures = 0;
            s.blockedUntil = clock.get().plus(lockout);
            return true;
        }
        return false;
    }

    public synchronized void recordSuccess(String account) {
        states.remove(account);
    }
}
