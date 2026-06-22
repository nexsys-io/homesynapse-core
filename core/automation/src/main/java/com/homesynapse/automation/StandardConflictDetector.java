/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import com.homesynapse.event.AutomationConflictDetectedEvent;
import com.homesynapse.event.AutomationConflictDetectedEvent.ConflictEntry;
import com.homesynapse.event.CausalContext;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventId;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.EntityId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production {@link ConflictDetector}: a post-execution scan for contradictory commands
 * targeting the same entity across the Runs triggered by one event (Doc 07 §3.13).
 *
 * <p>Report-only (DP-F / D6): both commands have already executed; this detector only emits
 * {@code automation_conflict_detected} (AMD-92 row 9). No suppression or priority resolution
 * occurs (Tier 2).</p>
 *
 * <p>{@link RunContext} does not carry a Run's commands, so the detector recovers each Run's
 * top-level {@link CommandAction}s from its {@link AutomationDefinition} (via the
 * {@link AutomationRegistry}) and resolves their target selectors via the
 * {@link SelectorResolver}. Commands to the same entity are <em>contradictory</em> when they
 * are not all identical (a differing command name, or the same command with differing
 * parameters). Nested command actions inside branches are out of scope at the Tier-1 floor.</p>
 *
 * <p>Thread-safe — stateless apart from its injected collaborators; publishes outside any
 * lock (LTD-11).</p>
 */
public final class StandardConflictDetector implements ConflictDetector {

    private static final Logger LOG = LoggerFactory.getLogger(StandardConflictDetector.class);

    private static final int SCHEMA_VERSION = 1;

    private final AutomationRegistry registry;
    private final SelectorResolver selectorResolver;
    private final EventPublisher publisher;

    /**
     * Constructs the conflict detector against its injected collaborators.
     *
     * @param registry         resolves a Run's automation to its command actions, never
     *                         {@code null}
     * @param selectorResolver resolves a command action's target selector to entities, never
     *                         {@code null}
     * @param publisher        the durable event publish surface, never {@code null}
     */
    public StandardConflictDetector(AutomationRegistry registry, SelectorResolver selectorResolver,
                                    EventPublisher publisher) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.selectorResolver = Objects.requireNonNull(selectorResolver, "selectorResolver");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    @Override
    public void scanForConflicts(EventId triggeringEventId, List<RunContext> triggeredRuns) {
        Objects.requireNonNull(triggeringEventId, "triggeringEventId must not be null");
        Objects.requireNonNull(triggeredRuns, "triggeredRuns must not be null");

        Map<EntityId, List<CommandRef>> byEntity = new LinkedHashMap<>();
        for (RunContext run : triggeredRuns) {
            if (run == null) {
                continue;
            }
            Optional<AutomationDefinition> automation = registry.get(run.automationId());
            if (automation.isEmpty()) {
                continue;
            }
            collectCommands(run, automation.get(), byEntity);
        }

        for (Map.Entry<EntityId, List<CommandRef>> entry : byEntity.entrySet()) {
            List<CommandRef> refs = entry.getValue();
            if (refs.size() >= 2 && contradictory(refs)) {
                publishConflict(triggeringEventId, entry.getKey(), refs);
            }
        }
    }

    private void collectCommands(RunContext run, AutomationDefinition automation,
                                 Map<EntityId, List<CommandRef>> byEntity) {
        for (ActionDefinition action : automation.actions()) {
            if (action instanceof CommandAction command) {
                String parameters = flattenParameters(command.parameters());
                for (EntityId target : selectorResolver.resolve(command.target())) {
                    byEntity.computeIfAbsent(target, key -> new ArrayList<>())
                            .add(new CommandRef(run.automationId(), run.triggeringEventId(),
                                    command.commandName(), parameters));
                }
            }
        }
    }

    /** Contradictory when the distinct {@code (commandName, parameters)} count is at least 2. */
    private static boolean contradictory(List<CommandRef> refs) {
        String first = refs.get(0).signature();
        for (CommandRef ref : refs) {
            if (!ref.signature().equals(first)) {
                return true;
            }
        }
        return false;
    }

    private void publishConflict(EventId triggeringEventId, EntityId entityRef,
                                 List<CommandRef> refs) {
        List<ConflictEntry> entries = new ArrayList<>(refs.size());
        for (CommandRef ref : refs) {
            entries.add(new ConflictEntry(ref.automationId(), ref.commandEventId(),
                    ref.commandName(), ref.parameters()));
        }
        AutomationConflictDetectedEvent payload = new AutomationConflictDetectedEvent(
                triggeringEventId, entityRef, entries, true);
        EventDraft draft = new EventDraft(EventTypes.AUTOMATION_CONFLICT_DETECTED, SCHEMA_VERSION,
                null, SubjectRef.entity(entityRef), EventPriority.DIAGNOSTIC,
                EventOrigin.AUTOMATION, payload, null, null);
        try {
            publisher.publish(draft,
                    CausalContext.chain(triggeringEventId.value(), triggeringEventId.value()));
        } catch (SequenceConflictException ex) {
            LOG.error("Failed to publish automation_conflict_detected for entity {}: "
                    + "sequence conflict", entityRef, ex);
        }
    }

    /** Stable flattening of command parameters for both the payload and the contradiction test. */
    private static String flattenParameters(Map<String, Object> parameters) {
        if (parameters.isEmpty()) {
            return "{}";
        }
        Map<String, Object> sorted = new TreeMap<>(parameters);
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
            first = false;
        }
        return builder.append('}').toString();
    }

    /** A single command a Run issues to a target entity (re-derived from the definition). */
    private record CommandRef(AutomationId automationId, EventId commandEventId,
                              String commandName, String parameters) {
        String signature() {
            return commandName + parameters;
        }
    }
}
