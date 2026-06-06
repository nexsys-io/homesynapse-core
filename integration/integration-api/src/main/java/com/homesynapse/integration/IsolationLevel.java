/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

/**
 * Declares the isolation level at which an integration adapter runs, on
 * {@link IntegrationDescriptor#isolationLevel()} (AMD-63).
 *
 * <p>This is a reservation: all adapters run {@link #IN_JVM} for the MVP.
 * Reserving the enum slot now costs one field; adding it after adapters ship
 * would be a retroactive descriptor amendment across every published adapter
 * (the same cheap-insurance pattern as AMD-34's schema reservation).</p>
 *
 * @see IntegrationDescriptor#isolationLevel()
 */
public enum IsolationLevel {

    /**
     * The adapter runs in the core JVM under supervisor thread management. This
     * is the MVP behaviour and the only supported level.
     */
    IN_JVM,

    /**
     * Reserved for post-MVP subprocess isolation of misbehaving native/JNI
     * adapters. The M9 supervisor rejects this value with
     * {@link UnsupportedOperationException} at startup until a future amendment
     * activates it; no code path may treat it as runnable (AMD-63-INV-01).
     */
    RESERVED_SUBPROCESS
}
