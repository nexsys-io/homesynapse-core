/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;

/**
 * Integration-scoped timer and periodic task scheduling service
 * (Doc 05 §3.8, §8.1).
 *
 * <p>{@code SchedulerService} replaces ad-hoc {@code Timer} and
 * {@code ScheduledExecutorService} usage in integration adapters. Each
 * integration receives its own instance with callbacks executing on the
 * integration's virtual thread group. The scheduler's lifecycle is tied to
 * the adapter — all scheduled tasks are cancelled when the adapter is
 * stopped.</p>
 *
 * <p>Only available if the adapter declares
 * {@link RequiredService#SCHEDULER} in its
 * {@link IntegrationDescriptor#requiredServices()}. If not declared, the
 * corresponding field in {@link IntegrationContext} is {@code null}.</p>
 *
 * <p>Typical use cases: polling loops for devices without push notification
 * support, retry timers for failed operations, periodic keepalive checks,
 * and debounce timers for rapid state changes.</p>
 *
 * <p><strong>Thread safety:</strong> All methods are safe for concurrent
 * use from any thread within the adapter's thread group.</p>
 *
 * @see RequiredService#SCHEDULER
 * @see IntegrationContext
 * @see IntegrationDescriptor#requiredServices()
 */
public interface SchedulerService {

    /**
     * Schedules a one-shot task for delayed execution.
     *
     * <p>The task executes on the integration's virtual thread group after
     * the specified delay. If the adapter is stopped before the delay
     * elapses, the task is cancelled.</p>
     *
     * @param task  the task to execute; never {@code null}
     * @param delay the delay before execution; never {@code null},
     *              must not be negative
     * @return a {@link ScheduledFuture} for cancellation and completion
     *         tracking; never {@code null}
     */
    ScheduledFuture<?> schedule(Runnable task, Duration delay);

    /**
     * Schedules a periodic task at a fixed rate.
     *
     * <p>The task first executes after {@code initialDelay}, then repeats
     * every {@code period}. If an execution takes longer than the period,
     * subsequent executions start immediately after the previous one
     * completes (no concurrent executions). All executions run on the
     * integration's virtual thread group.</p>
     *
     * @param task         the task to execute periodically; never {@code null}
     * @param initialDelay the delay before the first execution;
     *                     never {@code null}, must not be negative
     * @param period       the interval between successive executions;
     *                     never {@code null}, must be positive
     * @return a {@link ScheduledFuture} for cancellation and completion
     *         tracking; never {@code null}
     */
    ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration initialDelay, Duration period);

    /**
     * Cancels all scheduled tasks and releases scheduler resources.
     *
     * <p>Called by the supervisor when the adapter is stopped. After this
     * method returns, no further task callbacks will execute. Tasks that are
     * currently executing will complete but no new executions will start.</p>
     */
    void shutdown();
}
