/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Hysteresis-based saturation health check for the writer queue (AMD-43 §3.6.3).
 *
 * <p>Designed to be ticked once per second (production wiring will use a shared
 * {@code ScheduledExecutorService}; tests call {@link #tick()} directly with a
 * fixed clock). Maintains three counter states ({@code criticalTicks},
 * {@code warnTicks}, {@code recoveryTicks}) and emits {@link HealthSignal}s
 * through the injected {@link Consumer} callback.</p>
 *
 * <p><strong>Hysteresis rationale (single-node deployment):</strong> The
 * 5-tick recovery threshold is not merely conservative — it is architecturally
 * appropriate for HomeSynapse. Unlike Kubernetes (which can afford
 * {@code successThreshold: 1} because other pods absorb traffic during false
 * recovery), HomeSynapse has no fallback. A false recovery means the operator
 * sees INFO {@code writer.queue.recovered}, stops investigating, and is
 * blindsided by the next CRITICAL. The 5-tick symmetric threshold prevents
 * flapping in the failure domain where flapping is most dangerous: a system
 * with no redundancy.</p>
 *
 * <p>Re-emit cadence prevents log spam during sustained saturation:</p>
 * <ul>
 *   <li>CRITICAL: re-emitted at least 10 seconds apart</li>
 *   <li>WARN: re-emitted at least 30 seconds apart</li>
 * </ul>
 *
 * <p>The {@link Consumer Consumer&lt;HealthSignal&gt;} emitter is responsible for
 * both structured-log routing and any consumer-level JFR translation. Keeping
 * this class transport-free preserves the event-bus module's clean dependency
 * graph (no {@code requires com.homesynapse.observability}, no new
 * {@code requires org.slf4j}).</p>
 *
 * <p>This class is NOT thread-safe — it must be ticked from a single
 * scheduler thread.</p>
 */
public final class QueueSaturationHealthCheck {

    /** Channel name for saturation events. */
    public static final String CHANNEL_SATURATING = "writer.queue.saturating";

    /** Channel name for recovery events. */
    public static final String CHANNEL_RECOVERED = "writer.queue.recovered";

    /** Re-emit cooldown for sustained CRITICAL. */
    private static final Duration CRITICAL_REEMIT_INTERVAL = Duration.ofSeconds(10);

    /** Re-emit cooldown for sustained WARN. */
    private static final Duration WARN_REEMIT_INTERVAL = Duration.ofSeconds(30);

    private final IntSupplier queueDepthSupplier;
    private final Clock clock;
    private final int warnDepth;
    private final int criticalDepth;
    private final int saturationTicks;
    private final Consumer<HealthSignal> emitter;

    private int criticalTicks;
    private int warnTicks;
    private int recoveryTicks;

    private boolean currentlyCritical;
    private boolean currentlyWarn;

    private Instant lastCriticalEmit;
    private Instant lastWarnEmit;

    /**
     * Creates a new health check with the given thresholds.
     *
     * @param queueDepthSupplier supplier of the current writer queue depth
     *                           (DEC-M3-14 — never holds a reference to persistence types)
     * @param clock              the injected clock for emission timestamps
     * @param warnDepth          warn threshold (default 5000)
     * @param criticalDepth      critical threshold (default 10000)
     * @param saturationTicks    consecutive ticks before transition (default 5)
     * @param emitter            callback invoked on signal emission
     */
    public QueueSaturationHealthCheck(IntSupplier queueDepthSupplier,
                                      Clock clock,
                                      int warnDepth,
                                      int criticalDepth,
                                      int saturationTicks,
                                      Consumer<HealthSignal> emitter) {
        this.queueDepthSupplier = Objects.requireNonNull(queueDepthSupplier,
                "queueDepthSupplier");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (warnDepth <= 0 || criticalDepth <= warnDepth) {
            throw new IllegalArgumentException(
                    "Thresholds must satisfy 0 < warnDepth < criticalDepth: warnDepth="
                            + warnDepth + ", criticalDepth=" + criticalDepth);
        }
        if (saturationTicks <= 0) {
            throw new IllegalArgumentException(
                    "saturationTicks must be positive: " + saturationTicks);
        }
        this.warnDepth = warnDepth;
        this.criticalDepth = criticalDepth;
        this.saturationTicks = saturationTicks;
        this.emitter = Objects.requireNonNull(emitter, "emitter");
    }

    /**
     * Advances the hysteresis state machine by one tick. Implements the algorithm
     * specified in AMD-43 §3.6.3.
     *
     * <p>Per the contract, this method is called from a single scheduler thread
     * (typically a 1-second cadence). All time access uses the injected clock
     * (DEC-M3-09 / NO_DIRECT_TIME_ACCESS).</p>
     */
    public void tick() {
        int depth = queueDepthSupplier.getAsInt();

        if (depth > criticalDepth) {
            criticalTicks++;
            warnTicks = 0;
            recoveryTicks = 0;
            if (criticalTicks >= saturationTicks) {
                Instant now = clock.instant();
                if (!currentlyCritical
                        || lastCriticalEmit == null
                        || Duration.between(lastCriticalEmit, now)
                                .compareTo(CRITICAL_REEMIT_INTERVAL) >= 0) {
                    emitter.accept(new HealthSignal(HealthLevel.CRITICAL,
                            CHANNEL_SATURATING, depth, now));
                    lastCriticalEmit = now;
                    currentlyCritical = true;
                }
            }
        } else if (depth > warnDepth) {
            warnTicks++;
            criticalTicks = 0;
            recoveryTicks = 0;
            if (warnTicks >= saturationTicks && !currentlyCritical) {
                Instant now = clock.instant();
                if (!currentlyWarn
                        || lastWarnEmit == null
                        || Duration.between(lastWarnEmit, now)
                                .compareTo(WARN_REEMIT_INTERVAL) >= 0) {
                    emitter.accept(new HealthSignal(HealthLevel.WARN,
                            CHANNEL_SATURATING, depth, now));
                    lastWarnEmit = now;
                    currentlyWarn = true;
                }
            }
        } else {
            // Below warn — recovery path
            criticalTicks = 0;
            warnTicks = 0;
            if (currentlyCritical || currentlyWarn) {
                recoveryTicks++;
                if (recoveryTicks >= saturationTicks) {
                    emitter.accept(new HealthSignal(HealthLevel.INFO,
                            CHANNEL_RECOVERED, depth, clock.instant()));
                    currentlyCritical = false;
                    currentlyWarn = false;
                    recoveryTicks = 0;
                    lastCriticalEmit = null;
                    lastWarnEmit = null;
                }
            } else {
                recoveryTicks = 0;
            }
        }
    }
}
