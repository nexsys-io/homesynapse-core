/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.homesynapse.device.Area;
import com.homesynapse.device.AreaRegistry;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityRegistry;
import com.homesynapse.device.EntityRole;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.state.Availability;

/**
 * Parses a {@code automations.yaml} document (already loaded to a {@code Map} by the
 * configuration substrate) into validated {@link AutomationDefinition}s, assigning
 * identity and validating fail-closed (Doc 07 §3.3/§4.1/§6.1; AMD-88/89/93; SD-9).
 *
 * <p><strong>Fail-closed-at-load (SD-9 / R-δ AX-5).</strong> A definition that references
 * an unknown trigger/condition/action/selector type, an unknown core event type, a
 * dangling entity/area slug, a duplicate {@code trigger_id}, or an empty
 * {@code included_roles} set is rejected and surfaced (a {@link LoadFailure} the caller
 * publishes as {@code config_error}); valid sibling definitions still load. A definition
 * is never loaded into a silently-inert state — the precise failure mode this kills
 * (HA's 2026.6 silent-death class).</p>
 *
 * <p><strong>Input.</strong> The loader consumes the parsed {@code Map<String, Object>}
 * the configuration pipeline produces — it has no YAML library and no {@code config}
 * edge (the automation module must not depend on {@code com.homesynapse.config}; that
 * edge is forbidden by {@code assertAllowedModuleDependencies}). The composition root
 * (app-bootstrap, which may depend on both core and config) reads the document via
 * {@code ConfigurationService.getCurrentModel().rawMap()} and registers the
 * {@link AutomationSchema} fragment; this loader maps + validates that document. The
 * dangling-reference pass runs against the injected registries, which app-bootstrap
 * brings up before the automation engine subscribes (AMD-93 §4).</p>
 *
 * <p><strong>Scope.</strong> M7.1 fully parses the trigger, condition, and selector
 * vocabulary plus the Tier-1 action data records; action <em>execution</em> is M7.2.</p>
 *
 * <p>Thread-safe — stateless apart from the injected (thread-safe) collaborators.</p>
 */
public final class AutomationDefinitionLoader {

    /** Highest supported {@code schema_version} major (AMD-93 §2.1 — M7 schema is 1.0). */
    private static final int SUPPORTED_SCHEMA_MAJOR = 1;

    private static final Set<String> KNOWN_CORE_EVENT_TYPES = knownCoreEventTypes();

    private final AutomationIdentityStore identityStore;
    private final EntityRegistry entityRegistry;
    private final AreaRegistry areaRegistry;

    /**
     * Constructs a loader.
     *
     * @param identityStore  assigns/preserves automation + trigger identity, never {@code null}
     * @param entityRegistry validates entity-slug references, never {@code null}
     * @param areaRegistry   validates area references, never {@code null}
     */
    public AutomationDefinitionLoader(AutomationIdentityStore identityStore,
                                      EntityRegistry entityRegistry,
                                      AreaRegistry areaRegistry) {
        this.identityStore = Objects.requireNonNull(identityStore, "identityStore");
        this.entityRegistry = Objects.requireNonNull(entityRegistry, "entityRegistry");
        this.areaRegistry = Objects.requireNonNull(areaRegistry, "areaRegistry");
    }

    /**
     * Loads and validates all automation definitions in the parsed document.
     *
     * @param document the parsed {@code automations.yaml} root map, never {@code null}
     * @return the valid-subset plus the surfaced failures, never {@code null}
     */
    public LoadResult load(Map<String, Object> document) {
        Objects.requireNonNull(document, "document must not be null");
        List<AutomationDefinition> loaded = new ArrayList<>();
        List<LoadFailure> failures = new ArrayList<>();

        try {
            requireSupportedSchema(document);
        } catch (DefinitionException ex) {
            return new LoadResult(List.of(), List.of(new LoadFailure("<file>", ex.getMessage())));
        }

        List<Object> automations = coerceList(document.get("automations"));
        for (Object entry : automations) {
            String name = "<unnamed>";
            try {
                Map<String, Object> map = coerceMap(entry);
                name = optString(map, "name").orElse(name);
                loaded.add(parseAutomation(map));
            } catch (DefinitionException ex) {
                failures.add(new LoadFailure(name, ex.getMessage()));
            }
        }
        return new LoadResult(loaded, failures);
    }

    // ---- Schema version (AMD-93 §2.1) --------------------------------------

    private void requireSupportedSchema(Map<String, Object> document) {
        Object raw = document.get("schema_version");
        if (raw == null) {
            return; // absent → (1, 0) assumed (every pre-M7 file is version 1.0)
        }
        Map<String, Object> version = coerceMap(raw);
        int major = optInt(version, "major", 1);
        if (major > SUPPORTED_SCHEMA_MAJOR) {
            throw new DefinitionException("schema_version major " + major
                    + " is newer than supported major " + SUPPORTED_SCHEMA_MAJOR
                    + " (forward-only migration floor, AMD-93)");
        }
    }

    // ---- Automation envelope -----------------------------------------------

    private AutomationDefinition parseAutomation(Map<String, Object> map) {
        String name = requireString(map, "name");
        String slug = optString(map, "slug").orElseGet(() -> slugify(name));
        AutomationId automationId = identityStore.automationIdFor(slug);
        boolean enabled = optBool(map, "enabled", true);
        ConcurrencyMode mode = parseEnum(ConcurrencyMode.class, optString(map, "mode")
                .orElse("single"), "mode");
        MaxExceededSeverity severity = parseEnum(MaxExceededSeverity.class,
                optString(map, "max_exceeded_severity").orElse("info"), "max_exceeded_severity");
        int priority = optInt(map, "priority", 0);
        int defaultMax = (mode == ConcurrencyMode.SINGLE || mode == ConcurrencyMode.RESTART) ? 1 : 10;
        int maxConcurrent = optInt(map, "max_concurrent", defaultMax);

        List<TriggerDefinition> triggers = parseTriggers(map, slug);
        if (triggers.isEmpty()) {
            throw new DefinitionException("automation '" + name + "' has no triggers");
        }
        List<ConditionDefinition> conditions = new ArrayList<>();
        for (Object cond : coerceList(map.get("conditions"))) {
            conditions.add(parseCondition(coerceMap(cond)));
        }
        List<ActionDefinition> actions = new ArrayList<>();
        for (Object action : coerceList(map.get("actions"))) {
            actions.add(parseAction(coerceMap(action)));
        }
        if (actions.isEmpty()) {
            throw new DefinitionException("automation '" + name + "' has no actions");
        }

        return new AutomationDefinition(automationId, slug, name, optString(map, "description")
                .orElse(null), enabled, mode, maxConcurrent, severity, priority,
                triggers, conditions, actions);
    }

    // ---- Triggers (AMD-88) -------------------------------------------------

    private List<TriggerDefinition> parseTriggers(Map<String, Object> map, String slug) {
        List<TriggerDefinition> triggers = new ArrayList<>();
        Set<String> seenTriggerIds = new LinkedHashSet<>();
        List<Object> raw = coerceList(map.get("triggers"));
        for (int index = 0; index < raw.size(); index++) {
            Map<String, Object> triggerMap = coerceMap(raw.get(index));
            TriggerDefinition trigger = parseTrigger(triggerMap, index, slug);
            String triggerId = triggerIdOf(trigger);
            if (!triggerId.isEmpty() && !seenTriggerIds.add(triggerId)) {
                throw new DefinitionException("duplicate trigger_id '" + triggerId
                        + "' within one automation");
            }
            triggers.add(trigger);
        }
        return triggers;
    }

    private TriggerDefinition parseTrigger(Map<String, Object> map, int index, String slug) {
        String type = requireString(map, "type");
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "state_change" -> new StateChangeTrigger(parseSelector(map),
                    requireString(map, "attribute"), optString(map, "from").orElse(null),
                    optString(map, "to").orElse(null), parseForDuration(map),
                    triggerId(map, index, slug));
            case "state" -> new StateTrigger(parseSelector(map), requireString(map, "attribute"),
                    requireString(map, "value"), parseForDuration(map), triggerId(map, index, slug));
            case "event" -> new EventTrigger(requireKnownEventType(map),
                    payloadMap(map, "payload_filters"), triggerId(map, index, slug));
            case "availability" -> new AvailabilityTrigger(parseSelector(map),
                    parseAvailability(requireString(map, "target_availability")),
                    parseForDuration(map), triggerId(map, index, slug));
            case "numeric_threshold" -> new NumericThresholdTrigger(parseSelector(map),
                    requireString(map, "attribute"), optDouble(map, "above"),
                    optDouble(map, "below"), parseForDuration(map), triggerId(map, index, slug));
            case "calendar" -> new CalendarTrigger(EntityId.parse(requireString(map, "calendar_entity")),
                    parseEnum(CalendarEventTransition.class, requireString(map, "transition"),
                            "transition"), optDuration(map, "offset"), triggerId(map, index, slug));
            case "reachability" -> new ReachabilityTrigger(DeviceId.parse(requireString(map, "device")),
                    parseAvailability(requireString(map, "target_availability")),
                    parseForDuration(map), triggerId(map, index, slug));
            case "manual" -> new ManualTrigger(optString(map, "invocation_context").orElse(null),
                    triggerId(map, index, slug));
            case "webhook" -> new WebhookTrigger(requireString(map, "webhook_id"),
                    stringSet(map, "allowed_methods", Set.of("POST")),
                    optBool(map, "local_only", true), triggerId(map, index, slug));
            // Tier-2 reserved permits load inactive (no triggerId; benign no-match).
            case "time" -> new TimeTrigger();
            case "sun" -> new SunTrigger();
            case "presence" -> new PresenceTrigger();
            default -> throw new DefinitionException("unknown trigger type: " + type);
        };
    }

    private String triggerId(Map<String, Object> map, int index, String slug) {
        return optString(map, "trigger_id")
                .orElseGet(() -> identityStore.generatedTriggerIdFor(slug, index));
    }

    private String requireKnownEventType(Map<String, Object> map) {
        String eventType = requireString(map, "event_type");
        // Integration-defined dotted types are runtime-registered and cannot be checked
        // at load; core (underscored) types must be known (SD-9 unknown-event-type).
        if (!eventType.contains(".") && !KNOWN_CORE_EVENT_TYPES.contains(eventType)) {
            throw new DefinitionException("unknown event_type: " + eventType);
        }
        return eventType;
    }

    // ---- Conditions (Doc 07 §3.8) ------------------------------------------

    private ConditionDefinition parseCondition(Map<String, Object> map) {
        String type = requireString(map, "type");
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "state" -> new StateCondition(parseSelector(map), requireString(map, "attribute"),
                    requireString(map, "value"));
            case "numeric" -> new NumericCondition(parseSelector(map), requireString(map, "attribute"),
                    optDouble(map, "above"), optDouble(map, "below"));
            case "time" -> new TimeCondition(optString(map, "after").orElse(null),
                    optString(map, "before").orElse(null));
            case "and" -> new AndCondition(parseConditionList(map));
            case "or" -> new OrCondition(parseConditionList(map));
            case "not" -> new NotCondition(parseCondition(coerceMap(requireValue(map, "condition"))));
            case "zone" -> throw new DefinitionException(
                    "condition type 'zone' is Tier 2 (presence/zone) and not supported");
            default -> throw new DefinitionException("unknown condition type: " + type);
        };
    }

    private List<ConditionDefinition> parseConditionList(Map<String, Object> map) {
        List<ConditionDefinition> conditions = new ArrayList<>();
        for (Object child : coerceList(map.get("conditions"))) {
            conditions.add(parseCondition(coerceMap(child)));
        }
        return conditions;
    }

    // ---- Actions (Doc 07 §3.9 — data only in M7.1; execution is M7.2) ------

    private ActionDefinition parseAction(Map<String, Object> map) {
        String type = requireString(map, "type");
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "command" -> new CommandAction(parseSelector(coerceMap(requireValue(map, "target"))),
                    requireString(map, "command"), payloadMap(map, "parameters"),
                    parseEnum(UnavailablePolicy.class, optString(map, "on_unavailable")
                            .orElse("skip"), "on_unavailable"));
            case "delay" -> new DelayAction(requireDuration(map, "duration"));
            case "wait_for" -> new WaitForAction(parseCondition(coerceMap(requireValue(map, "condition"))),
                    requireDuration(map, "timeout"), optDuration(map, "poll_interval"));
            case "condition_branch" -> new ConditionBranchAction(
                    parseCondition(coerceMap(requireValue(map, "condition"))),
                    parseActionList(map, "then"), parseActionList(map, "else"));
            case "emit_event" -> new EmitEventAction(requireString(map, "event_type"),
                    payloadMap(map, "payload"));
            // Tier-2 reserved action permits load inactive (execution deferred).
            case "activate_scene" -> new ActivateSceneAction();
            case "invoke_integration" -> new InvokeIntegrationAction();
            case "parallel" -> new ParallelAction();
            default -> throw new DefinitionException("unknown action type: " + type);
        };
    }

    private List<ActionDefinition> parseActionList(Map<String, Object> map, String key) {
        List<ActionDefinition> actions = new ArrayList<>();
        for (Object child : coerceList(map.get(key))) {
            actions.add(parseAction(coerceMap(child)));
        }
        return actions;
    }

    // ---- Selectors (AMD-89) -------------------------------------------------

    private Selector parseSelector(Map<String, Object> map) {
        if (map.containsKey("entity_ref")) {
            return new DirectRefSelector(EntityId.parse(requireString(map, "entity_ref")));
        }
        if (map.containsKey("entity")) {
            String slug = requireString(map, "entity");
            requireEntitySlug(slug);
            return new SlugSelector(slug);
        }
        if (map.containsKey("area")) {
            String areaSlug = requireString(map, "area");
            requireArea(areaSlug);
            return new AreaSelector(areaSlug, parseIncludedRoles(map));
        }
        if (map.containsKey("label")) {
            return new LabelSelector(requireString(map, "label"), parseIncludedRoles(map));
        }
        if (map.containsKey("type")) {
            return new TypeSelector(requireString(map, "type"), parseIncludedRoles(map));
        }
        if (map.containsKey("semantic_tag")) {
            Map<String, Object> tag = coerceMap(requireValue(map, "semantic_tag"));
            return new SemanticTagSelector(requireString(tag, "namespace"),
                    requireString(tag, "value"),
                    parseEnum(MatchMode.class, optString(tag, "match_mode").orElse("exact"),
                            "match_mode"),
                    parseIncludedRoles(tag));
        }
        if (map.containsKey("all_of")) {
            List<Selector> operands = new ArrayList<>();
            for (Object child : coerceList(map.get("all_of"))) {
                operands.add(parseSelector(coerceMap(child)));
            }
            return new CompoundSelector(operands);
        }
        throw new DefinitionException("no recognizable selector key "
                + "(entity_ref/entity/area/label/type/semantic_tag/all_of)");
    }

    private Set<EntityRole> parseIncludedRoles(Map<String, Object> map) {
        Object raw = map.get("included_roles");
        if (raw == null) {
            return Set.of(EntityRole.PRIMARY); // YAML default (AMD-89 §2.2)
        }
        List<Object> values = coerceList(raw);
        Set<EntityRole> roles = new LinkedHashSet<>();
        for (Object value : values) {
            roles.add(parseEnum(EntityRole.class, String.valueOf(value), "included_roles"));
        }
        if (roles.isEmpty()) {
            // E89-1: an empty included_roles can never resolve anything — fail closed.
            throw new DefinitionException("included_roles must not be empty (AMD-89 §4)");
        }
        return roles;
    }

    private void requireEntitySlug(String slug) {
        for (Entity entity : entityRegistry.listAllEntities()) {
            if (entity.entitySlug().equals(slug)) {
                return;
            }
        }
        throw new DefinitionException("unknown entity slug: " + slug);
    }

    private void requireArea(String areaSlug) {
        for (Area area : areaRegistry.getAll()) {
            if (area.name().equalsIgnoreCase(areaSlug)
                    || slugify(area.name()).equals(areaSlug)) {
                return;
            }
        }
        throw new DefinitionException("unknown area: " + areaSlug);
    }

    // ---- Scalar / structural helpers ---------------------------------------

    private Duration parseForDuration(Map<String, Object> map) {
        Duration duration = optDuration(map, "for_duration");
        if (duration != null && duration.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new DefinitionException("for_duration must be at least PT1S, got " + duration);
        }
        return duration;
    }

    private Duration optDuration(Map<String, Object> map, String key) {
        return optString(map, key).map(AutomationDefinitionLoader::parseIso8601).orElse(null);
    }

    private Duration requireDuration(Map<String, Object> map, String key) {
        return parseIso8601(requireString(map, key));
    }

    private static Duration parseIso8601(String value) {
        try {
            Duration duration = Duration.parse(value);
            if (value.toUpperCase(Locale.ROOT).contains("D")) {
                throw new DefinitionException("calendar-day durations (P..D) are rejected "
                        + "(ambiguous across DST); use PT..H — got " + value);
            }
            return duration;
        } catch (java.time.format.DateTimeParseException ex) {
            throw new DefinitionException("invalid ISO-8601 duration: " + value);
        }
    }

    private Availability parseAvailability(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "available", "online" -> Availability.AVAILABLE;
            case "unavailable", "offline" -> Availability.UNAVAILABLE;
            case "unknown" -> Availability.UNKNOWN;
            default -> throw new DefinitionException("unknown availability: " + value);
        };
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new DefinitionException("invalid " + field + ": " + value);
        }
    }

    private Set<String> stringSet(Map<String, Object> map, String key, Set<String> defaults) {
        Object raw = map.get(key);
        if (raw == null) {
            return defaults;
        }
        Set<String> values = new LinkedHashSet<>();
        for (Object value : coerceList(raw)) {
            values.add(String.valueOf(value));
        }
        return values.isEmpty() ? defaults : values;
    }

    private Map<String, Object> payloadMap(Map<String, Object> map, String key) {
        Object raw = map.get(key);
        return raw == null ? Map.of() : coerceMap(raw);
    }

    private static String triggerIdOf(TriggerDefinition trigger) {
        return switch (trigger) {
            case StateChangeTrigger t -> t.triggerId();
            case StateTrigger t -> t.triggerId();
            case EventTrigger t -> t.triggerId();
            case AvailabilityTrigger t -> t.triggerId();
            case NumericThresholdTrigger t -> t.triggerId();
            case CalendarTrigger t -> t.triggerId();
            case ReachabilityTrigger t -> t.triggerId();
            case ManualTrigger t -> t.triggerId();
            case WebhookTrigger t -> t.triggerId();
            case TimeTrigger ignored -> "";
            case SunTrigger ignored -> "";
            case PresenceTrigger ignored -> "";
        };
    }

    private static String slugify(String name) {
        return name.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private static String requireString(Map<String, Object> map, String key) {
        Object value = requireValue(map, key);
        return String.valueOf(value);
    }

    private static Optional<String> optString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? Optional.empty() : Optional.of(String.valueOf(value));
    }

    private static Double optDouble(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            throw new DefinitionException("expected a number for '" + key + "', got " + value);
        }
    }

    private static int optInt(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            throw new DefinitionException("expected an integer for '" + key + "', got " + value);
        }
    }

    private static boolean optBool(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static Object requireValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new DefinitionException("missing required field: " + key);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> coerceMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new DefinitionException("expected a mapping, got "
                + (value == null ? "null" : value.getClass().getSimpleName()));
    }

    private static List<Object> coerceList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        throw new DefinitionException("expected a list, got " + value.getClass().getSimpleName());
    }

    private static Set<String> knownCoreEventTypes() {
        Set<String> types = new LinkedHashSet<>();
        for (java.lang.reflect.Field field : com.homesynapse.event.EventTypes.class.getDeclaredFields()) {
            int mods = field.getModifiers();
            if (java.lang.reflect.Modifier.isPublic(mods)
                    && java.lang.reflect.Modifier.isStatic(mods)
                    && field.getType() == String.class) {
                try {
                    types.add((String) field.get(null));
                } catch (IllegalAccessException ex) {
                    throw new IllegalStateException("EventTypes constant not accessible", ex);
                }
            }
        }
        return Set.copyOf(types);
    }

    /** Internal control-flow signal for a per-definition validation failure. */
    private static final class DefinitionException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private DefinitionException(String message) {
            super(message);
        }
    }
}
