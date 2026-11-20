package com.gathin.tvhome;

/** How long to wait before the next reconnect attempt: 2 s, doubling, never more than a minute. */
final class HaBackoff {
    private static final long FIRST_MS = 2_000L;
    private static final long MAX_MS = 60_000L;

    private HaBackoff() {}

    static long delayMs(int attempt) {
        int a = Math.max(0, Math.min(attempt, 10));
        return Math.min(FIRST_MS << a, MAX_MS);
    }
}
