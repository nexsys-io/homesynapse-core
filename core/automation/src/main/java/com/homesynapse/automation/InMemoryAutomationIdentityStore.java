/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.UlidFactory;

/**
 * In-memory {@link AutomationIdentityStore} — the M7.1 identity seam, and since AUTO-ID-1
 * the TEST DOUBLE: the composition root wires the durable
 * {@link CompanionAutomationIdentityStore} over {@code automations.ids.yaml}
 * (AMD-93 §2.3). Wired at a root, this class re-mints every identity per boot and
 * orphans the run history on record (MEASURE-2b F-1).
 *
 * <p>Assignments survive for the life of the process and are stable across
 * {@link AutomationRegistry#reload(java.util.List)} calls, which is the behavioral
 * contract the trigger/condition path depends on (AMD-88-INV-02). Generated identifiers
 * are ULIDs derived from the injected {@link Clock} (NO_DIRECT_TIME_ACCESS-safe).</p>
 *
 * <p>Thread-safe via a {@link ReentrantLock} (LTD-11 — never {@code synchronized}).</p>
 */
public final class InMemoryAutomationIdentityStore implements AutomationIdentityStore {

    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, AutomationId> automationIds = new HashMap<>();
    private final Map<String, String> generatedTriggerIds = new HashMap<>();

    /**
     * Constructs an identity store that mints ULIDs from the given clock.
     *
     * @param clock the injected clock, never {@code null}
     */
    public InMemoryAutomationIdentityStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public AutomationId automationIdFor(String automationSlug) {
        Objects.requireNonNull(automationSlug, "automationSlug must not be null");
        lock.lock();
        try {
            return automationIds.computeIfAbsent(automationSlug,
                    slug -> AutomationId.of(UlidFactory.generate(clock)));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String generatedTriggerIdFor(String automationSlug, int triggerIndex) {
        Objects.requireNonNull(automationSlug, "automationSlug must not be null");
        if (triggerIndex < 0) {
            throw new IllegalArgumentException("triggerIndex must be >= 0: " + triggerIndex);
        }
        String key = automationSlug + "#" + triggerIndex;
        lock.lock();
        try {
            return generatedTriggerIds.computeIfAbsent(key,
                    ignored -> UlidFactory.generate(clock).toString());
        } finally {
            lock.unlock();
        }
    }
}
