/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import com.homesynapse.event.EventTypes;
import com.homesynapse.platform.identity.AutomationId;

/**
 * Production {@link AutomationRegistry}: an in-memory definition store plus a trigger
 * index (event-type → matching automations) for O(1) trigger-evaluation lookup
 * (Doc 07 §3.4).
 *
 * <p><strong>Concurrency (LTD-11).</strong> The registry holds an immutable
 * {@link Snapshot} behind a {@code volatile} reference. Reads ({@code get},
 * {@code getBySlug}, {@code getAll}, {@code candidatesForEventType}) are lock-free —
 * they read the current snapshot and never block. Writes ({@code load}/{@code reload})
 * build a fresh snapshot and publish it atomically under a {@link ReentrantLock}; no
 * {@code synchronized} is used.</p>
 *
 * <p><strong>Reload (C7).</strong> {@code reload} hot-swaps the snapshot. In-progress
 * Runs are preserved by the RunManager (M7.2), which holds each Run's original
 * definition snapshot — the registry swap does not disturb them. The registry contract
 * does not assume zero active Runs; M7.1 simply has none yet.</p>
 *
 * <p>The trigger index lists candidates already sorted by execution order — priority
 * descending, then {@code automationId} ascending (Doc 07 §3.13) — so the evaluator
 * iterates in deterministic order without re-sorting.</p>
 */
public final class StandardAutomationRegistry implements AutomationRegistry {

    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile Snapshot snapshot = Snapshot.empty();

    /** Constructs an empty registry; populate it via {@link #load(List)}. */
    public StandardAutomationRegistry() {
        // No-arg: definitions are supplied through load/reload.
    }

    @Override
    public void load(List<AutomationDefinition> definitions) {
        swap(definitions);
    }

    @Override
    public void reload(List<AutomationDefinition> definitions) {
        swap(definitions);
    }

    @Override
    public Optional<AutomationDefinition> get(AutomationId id) {
        Objects.requireNonNull(id, "id must not be null");
        return Optional.ofNullable(snapshot.byId.get(id));
    }

    @Override
    public Optional<AutomationDefinition> getBySlug(String slug) {
        Objects.requireNonNull(slug, "slug must not be null");
        return Optional.ofNullable(snapshot.bySlug.get(slug));
    }

    @Override
    public List<AutomationDefinition> getAll() {
        return snapshot.all;
    }

    /**
     * Returns the automations whose triggers consume {@code eventType}, in execution
     * order (priority descending, then automationId ascending). Package-private — the
     * {@link TriggerEvaluator} consumes this index; it is not part of the public API. A
     * {@code for_duration} trigger is indexed under its consumed type AND under
     * {@code trigger_duration_expired} (DUR-1), so the engine's own expiry event reaches the
     * automation that armed the timer.
     *
     * @param eventType the incoming event's type string, never {@code null}
     * @return the matching definitions in deterministic order, never {@code null}
     */
    List<AutomationDefinition> candidatesForEventType(String eventType) {
        return snapshot.triggerIndex.getOrDefault(eventType, List.of());
    }

    private void swap(List<AutomationDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions must not be null");
        Snapshot next = Snapshot.build(definitions);
        writeLock.lock();
        try {
            this.snapshot = next;
        } finally {
            writeLock.unlock();
        }
    }

    /** Immutable point-in-time view of the registry. */
    private static final class Snapshot {

        private final Map<AutomationId, AutomationDefinition> byId;
        private final Map<String, AutomationDefinition> bySlug;
        private final List<AutomationDefinition> all;
        private final Map<String, List<AutomationDefinition>> triggerIndex;

        private Snapshot(Map<AutomationId, AutomationDefinition> byId,
                         Map<String, AutomationDefinition> bySlug,
                         List<AutomationDefinition> all,
                         Map<String, List<AutomationDefinition>> triggerIndex) {
            this.byId = byId;
            this.bySlug = bySlug;
            this.all = all;
            this.triggerIndex = triggerIndex;
        }

        static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), List.of(), Map.of());
        }

        static Snapshot build(List<AutomationDefinition> definitions) {
            Map<AutomationId, AutomationDefinition> byId = new LinkedHashMap<>();
            Map<String, AutomationDefinition> bySlug = new LinkedHashMap<>();
            Map<String, List<AutomationDefinition>> index = new LinkedHashMap<>();

            for (AutomationDefinition definition : definitions) {
                byId.put(definition.automationId(), definition);
                bySlug.put(definition.slug(), definition);
                for (TriggerDefinition trigger : definition.triggers()) {
                    String eventType = consumedEventType(trigger);
                    if (eventType == null) {
                        continue; // Calendar/Webhook/Tier-2: no current producer (benign no-match)
                    }
                    index.computeIfAbsent(eventType, key -> new ArrayList<>())
                            .add(definition);
                    if (StandardTriggerEvaluator.forDurationOf(trigger) != null) {
                        // DUR-1: a for_duration trigger fires on the engine's own expiry event —
                        // index it under that type too, so the redelivered trigger_duration_expired
                        // reaches the evaluator's expired-payload arm; dedup below collapses a
                        // definition indexed twice into one bucket entry.
                        index.computeIfAbsent(EventTypes.TRIGGER_DURATION_EXPIRED,
                                        key -> new ArrayList<>())
                                .add(definition);
                    }
                }
            }

            // Sort each index bucket by execution order and freeze it.
            Comparator<AutomationDefinition> order =
                    Comparator.comparingInt(AutomationDefinition::priority).reversed()
                            .thenComparing(AutomationDefinition::automationId);
            Map<String, List<AutomationDefinition>> frozenIndex = new LinkedHashMap<>();
            for (Map.Entry<String, List<AutomationDefinition>> entry : index.entrySet()) {
                List<AutomationDefinition> bucket = new ArrayList<>(dedup(entry.getValue()));
                bucket.sort(order);
                frozenIndex.put(entry.getKey(), List.copyOf(bucket));
            }

            return new Snapshot(
                    Map.copyOf(byId),
                    Map.copyOf(bySlug),
                    List.copyOf(definitions),
                    Map.copyOf(frozenIndex));
        }

        /** Removes duplicate definitions while preserving first-seen order. */
        private static List<AutomationDefinition> dedup(List<AutomationDefinition> bucket) {
            List<AutomationDefinition> unique = new ArrayList<>();
            for (AutomationDefinition definition : bucket) {
                if (!unique.contains(definition)) {
                    unique.add(definition);
                }
            }
            return unique;
        }

        /**
         * The single event type a trigger consumes, or {@code null} if it has no current
         * producer in the taxonomy (Calendar/Webhook permits and the Tier-2 reserved
         * permits — they register but match nothing until their producers ship).
         */
        private static String consumedEventType(TriggerDefinition trigger) {
            return switch (trigger) {
                case StateChangeTrigger ignored -> EventTypes.STATE_CHANGED;
                case StateTrigger ignored -> EventTypes.STATE_CHANGED;
                case NumericThresholdTrigger ignored -> EventTypes.STATE_CHANGED;
                case AvailabilityTrigger ignored -> EventTypes.AVAILABILITY_CHANGED;
                case ReachabilityTrigger ignored -> EventTypes.AVAILABILITY_CHANGED;
                case EventTrigger event -> event.eventType();
                case ManualTrigger ignored -> EventTypes.AUTOMATION_INVOKED;
                case CalendarTrigger ignored -> null;   // M10 producer
                case WebhookTrigger ignored -> null;    // M10 producer
                case TimeTrigger ignored -> null;       // Tier 2
                case SunTrigger ignored -> null;        // Tier 2
                case PresenceTrigger ignored -> null;   // Tier 2 (M8.1)
            };
        }
    }
}
