/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory idempotency cache for the command write surface (AMD-08 / Doc 09
 * §3.4, DP-4).
 *
 * <p>Wraps each stored {@link IdempotencyEntry} with a SHA-256 fingerprint of
 * the canonical request body so a retried request can be distinguished from a
 * key reuse with a different body: same key + same fingerprint replays the
 * cached {@code 202} without publishing; same key + different fingerprint is
 * a {@code 409} {@link ProblemType#IDEMPOTENCY_KEY_CONFLICT}.</p>
 *
 * <p>Eviction: at most {@value #MAX_ENTRIES} entries, least-recently-used
 * first; each entry expires {@code 24h} after {@link IdempotencyEntry#createdAt()}
 * (an entry aged exactly 24h is expired). The cache is deliberately NOT
 * persisted — it is lost on process restart, and a post-restart retry simply
 * issues a new command (the documented AMD-08 limitation).</p>
 *
 * <p>Thread safety: all reads and writes serialize through a
 * {@code ReentrantLock} (LTD-11) because lookups refresh LRU order.</p>
 */
final class IdempotencyCache {

    /** Maximum number of cached entries (Doc 09 §9). */
    static final int MAX_ENTRIES = 10_000;

    /** Per-entry time-to-live from {@link IdempotencyEntry#createdAt()}. */
    static final Duration TTL = Duration.ofHours(24);

    /** Discriminates the three lookup outcomes (DP-4 / AMD-08). */
    enum Outcome {
        /** Key absent (or expired): the command proceeds and is stored after publish. */
        MISS,
        /** Key present with the same body fingerprint: replay the cached 202. */
        REPLAY,
        /** Key present with a different body fingerprint: 409 conflict. */
        CONFLICT
    }

    /**
     * A lookup result: the {@link Outcome} plus, for {@link Outcome#REPLAY},
     * the cached entry to rebuild the response from.
     *
     * @param outcome the lookup outcome, never {@code null}
     * @param entry   the cached entry; non-null only for {@link Outcome#REPLAY}
     */
    record Result(Outcome outcome, IdempotencyEntry entry) {
        Result {
            Objects.requireNonNull(outcome, "outcome must not be null");
        }
    }

    /** Internal record wrapping the Phase-2 entry with its body fingerprint (DP-4). */
    private record CachedCommand(IdempotencyEntry entry, String fingerprint) {
    }

    private final ReentrantLock lock = new ReentrantLock();

    /** Access-ordered so {@code get} refreshes recency — LRU, not FIFO. */
    private final LinkedHashMap<String, CachedCommand> entries =
            new LinkedHashMap<>(16, 0.75f, true);

    private final Clock clock;

    /**
     * Constructs an empty cache.
     *
     * @param clock injected clock for TTL decisions; never {@code null}
     */
    IdempotencyCache(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Looks up {@code key}, refreshing its LRU recency on a hit.
     *
     * @param key         the client-supplied idempotency key; never {@code null}
     * @param fingerprint the canonical body fingerprint; never {@code null}
     * @return the lookup result; never {@code null}
     */
    Result get(String key, String fingerprint) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(fingerprint, "fingerprint");
        lock.lock();
        try {
            CachedCommand cached = entries.get(key);
            if (cached == null) {
                return new Result(Outcome.MISS, null);
            }
            if (isExpired(cached.entry())) {
                entries.remove(key);
                return new Result(Outcome.MISS, null);
            }
            if (!cached.fingerprint().equals(fingerprint)) {
                return new Result(Outcome.CONFLICT, null);
            }
            return new Result(Outcome.REPLAY, cached.entry());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Stores an entry after a successful publish, evicting least-recently-used
     * entries beyond {@link #MAX_ENTRIES}.
     *
     * @param key         the client-supplied idempotency key; never {@code null}
     * @param fingerprint the canonical body fingerprint; never {@code null}
     * @param entry       the entry to cache; never {@code null}
     */
    void store(String key, String fingerprint, IdempotencyEntry entry) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(entry, "entry");
        lock.lock();
        try {
            entries.put(key, new CachedCommand(entry, fingerprint));
            while (entries.size() > MAX_ENTRIES) {
                Iterator<String> eldest = entries.keySet().iterator();
                eldest.next();
                eldest.remove();
            }
        } finally {
            lock.unlock();
        }
    }

    /** An entry aged exactly {@link #TTL} is expired (24h00m is a miss). */
    private boolean isExpired(IdempotencyEntry entry) {
        return !clock.instant().isBefore(entry.createdAt().plus(TTL));
    }

    /**
     * Computes the canonical body fingerprint: SHA-256 hex over
     * {@code entityId + "\n" + capability + "\n" + command + "\n" + params}
     * with map keys sorted at every depth, so two bodies that differ only in
     * JSON key order fingerprint identically (DP-4). Scalar values carry a
     * type tag so {@code 1} (number) and {@code "1"} (string) never collide —
     * a false conflict is safe, a false replay is not.
     *
     * @param entityId   the target entity id string; never {@code null}
     * @param capability the request capability; never {@code null}
     * @param command    the request command; never {@code null}
     * @param parameters the request parameters; never {@code null}
     * @return the lowercase hex SHA-256 fingerprint; never {@code null}
     */
    static String fingerprint(String entityId, String capability, String command,
                              Map<String, Object> parameters) {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(parameters, "parameters");
        StringBuilder canonical = new StringBuilder(entityId).append('\n')
                .append(capability).append('\n')
                .append(command).append('\n');
        appendCanonical(parameters, canonical);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", e);
        }
    }

    private static void appendCanonical(Object value, StringBuilder canonical) {
        if (value == null) {
            canonical.append("null");
            return;
        }
        if (value instanceof Map<?, ?> map) {
            canonical.append('{');
            map.entrySet().stream()
                    .sorted(Comparator.comparing(
                            (Map.Entry<?, ?> entry) -> String.valueOf(entry.getKey())))
                    .forEach(entry -> {
                        canonical.append(String.valueOf(entry.getKey())).append('=');
                        appendCanonical(entry.getValue(), canonical);
                        canonical.append(';');
                    });
            canonical.append('}');
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            canonical.append('[');
            for (Object element : iterable) {
                appendCanonical(element, canonical);
                canonical.append(',');
            }
            canonical.append(']');
            return;
        }
        canonical.append(value.getClass().getSimpleName()).append(':').append(value);
    }
}
