/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.observability;

import java.util.Set;

/**
 * Registry for custom JFR event types used by HomeSynapse subsystems.
 *
 * <p>All application-level JFR event types are registered through this interface
 * at subsystem initialization (Doc 11 §3.2, §4.3, §8.1). This is a compile-time
 * registry — the set of custom event types is fixed for a given release. The
 * registry enforces that all JFR event fields use primitives or String (enums are
 * silently ignored by JFR, decision D-09). Budget: 15–25 custom event types with
 * a safe ceiling of 50–100 (Doc 11 §3.2).</p>
 *
 * @see MetricsStreamBridge
 * @see MetricSnapshot
 */
public interface MetricsRegistry {
    /**
     * Register a custom JFR event type.
     *
     * <p>Phase 3 validates field types at registration time. All fields must be
     * primitives (int, long, double, boolean) or String. Other types (enums,
     * custom classes) are silently ignored by JFR.</p>
     *
     * @param eventClassName the fully-qualified class name of the JFR event class
     *        (e.g., "com.homesynapse.jfr.EventAppendLatencyEvent"). Non-null.
     * @param category the JFR event category string (e.g., "HomeSynapse.Device",
     *        "HomeSynapse.Command"). Non-null, must not be empty.
     *
     * @throws NullPointerException if eventClassName or category is null
     * @throws IllegalArgumentException if category is empty or if the event class
     *         is already registered
     * @throws IllegalStateException if the event class is already registered
     *
     * @see MetricsStreamBridge
     */
    void register(String eventClassName, String category);

    /**
     * Check if a JFR event class is registered.
     *
     * <p>Thread-safe.</p>
     *
     * @param eventClassName the fully-qualified class name. Non-null.
     * @return true if the event class has been registered, false otherwise.
     *
     * @throws NullPointerException if eventClassName is null
     */
    boolean isRegistered(String eventClassName);

    /**
     * Returns an unmodifiable set of all registered JFR event class names.
     *
     * <p>Thread-safe. Returns a snapshot of the current registry state.</p>
     *
     * @return an unmodifiable set of registered event class names. Non-null,
     *         may be empty before subsystems register their custom events.
     *
     * @see #register(String, String)
     */
    Set<String> registeredEventClasses();
}
