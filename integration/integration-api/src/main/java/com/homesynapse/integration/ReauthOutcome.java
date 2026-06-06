/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

/**
 * Result of an adapter's re-authentication hook
 * ({@link IntegrationAdapter#onReauthRequired()}) — AMD-55 §2.1 (arbitration A3).
 *
 * <p>The supervisor must distinguish an adapter that has begun an asynchronous
 * re-authentication from one that does not implement re-authentication at all,
 * because the two demand different supervisor responses.</p>
 *
 * @see IntegrationAdapter#onReauthRequired()
 * @see com.homesynapse.integration.runtime.ExceptionClassification#AUTH_FAILED
 * @see IntegrationReauthRequired
 * @see IntegrationReauthCompleted
 */
public enum ReauthOutcome {

    /**
     * The adapter has begun asynchronous re-authentication and will signal
     * completion via the {@code integration.reauth.completed} lifecycle event
     * ({@link IntegrationReauthCompleted}). The supervisor awaits that signal.
     */
    INITIATED,

    /**
     * The adapter does not implement re-authentication. The supervisor falls back
     * to the standard restart/suspension policy with the failure reason preserved.
     * This is the default for an adapter that does not override
     * {@link IntegrationAdapter#onReauthRequired()}.
     */
    UNSUPPORTED
}
