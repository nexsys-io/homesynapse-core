/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.homesynapse.automation.CommandValidator;
import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.CommandDefinition;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityRegistry;
import com.homesynapse.device.IdempotencyClass;
import com.homesynapse.event.CommandIdempotency;
import com.homesynapse.event.CommandIssuedEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;

import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Javalin handler for {@code POST /api/v1/entities/{entityId}/commands} —
 * the command write surface (CMD-API, Doc 09 §4.3/§4.4).
 *
 * <p>A thin adapter over the existing command pipeline: validate, publish ONE
 * root {@code command_issued} event, respond {@code 202 Accepted}. The
 * co-located dispatch subscriber, ledger, and confirmation machinery do
 * everything downstream — this handler duplicates none of it.</p>
 *
 * <p>Validation order (DP-6): body parse/shape → 400; entity resolve (bad
 * ULID or absent) → 404; Tier-1 command validation → 422; idempotency
 * (DP-4) → replay/409; publish; 202. "Accepted" means the event is durably
 * persisted (INV-ES-04) — NOT that the device executed anything. The
 * timeout/idempotency resolution mirrors {@code StandardActionExecutor}'s
 * capability precedence exactly (DP-3): the capability's positive
 * {@code default_timeout}, else the injected config fallback; unresolvable
 * idempotency is {@code NOT_IDEMPOTENT} (never silently re-fire).</p>
 *
 * <p>Thread safety: stateless beyond the injected collaborators; the
 * idempotency cache serializes internally (LTD-11).</p>
 *
 * @see GetCommandStatusEndpoint
 * @see RestFilters#installCommandEndpoints
 */
final class IssueCommandEndpoint implements Handler {

    private static final Logger LOG = LoggerFactory.getLogger(IssueCommandEndpoint.class);

    /**
     * rest-api is the JSON boundary (LTD-08) — parse and serialize here only.
     * Map entries serialize KEY-SORTED so the published {@code parameters}
     * JSON is byte-deterministic: {@code CommandRequest} defensively copies
     * via {@code Map.copyOf}, whose iteration order is per-JVM salted —
     * without the sort, identical requests would publish differently-ordered
     * payload bytes across restarts (the deterministic-encoding discipline).
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    /** Maximum accepted {@code Idempotency-Key} length (Doc 09 §3.4). */
    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    /** Mirrors the sole existing emitter's schema version (StandardActionExecutor). */
    private static final int SCHEMA_VERSION = 1;

    /** The AMD-95 non-blank floor for parameterless commands. */
    private static final String EMPTY_PARAMETERS_JSON = "{}";

    private final EventPublisher eventPublisher;
    private final EntityRegistry entityRegistry;
    private final CommandValidator commandValidator;
    private final IdempotencyCache idempotencyCache;
    private final int defaultConfirmationTimeoutMs;
    private final LongSupplier viewPositionSupplier;
    private final Clock clock;

    /**
     * Constructs the handler over the given collaborators.
     *
     * @param eventPublisher               the sole write path (durable at
     *                                     return, INV-ES-04); never {@code null}
     * @param entityRegistry               resolves the target entity and its
     *                                     capability surface; never {@code null}
     * @param commandValidator             the Tier-1 422 instrument; never
     *                                     {@code null}
     * @param idempotencyCache             the AMD-08 replay/conflict cache;
     *                                     never {@code null}
     * @param defaultConfirmationTimeoutMs config fallback when no capability
     *                                     declares a positive default timeout
     * @param viewPositionSupplier         projection cursor for {@code meta};
     *                                     never {@code null}
     * @param clock                        injected clock; never {@code null}
     */
    IssueCommandEndpoint(EventPublisher eventPublisher,
                         EntityRegistry entityRegistry,
                         CommandValidator commandValidator,
                         IdempotencyCache idempotencyCache,
                         int defaultConfirmationTimeoutMs,
                         LongSupplier viewPositionSupplier,
                         Clock clock) {
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.entityRegistry = Objects.requireNonNull(entityRegistry, "entityRegistry");
        this.commandValidator = Objects.requireNonNull(commandValidator, "commandValidator");
        this.idempotencyCache = Objects.requireNonNull(idempotencyCache, "idempotencyCache");
        this.defaultConfirmationTimeoutMs = defaultConfirmationTimeoutMs;
        this.viewPositionSupplier =
                Objects.requireNonNull(viewPositionSupplier, "viewPositionSupplier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void handle(Context ctx) {
        apply(new JavalinEndpointContext(ctx));
    }

    /**
     * Pure handler logic — package-private so tests can drive the endpoint
     * with a recording {@link EndpointContext} stub.
     *
     * @param ctx the request/response SPI; never {@code null}
     */
    void apply(EndpointContext ctx) {
        // (1) Body parse/shape — 400 with FieldErrors (DP-6).
        CommandRequest request = parseRequest(ctx);
        if (request == null) {
            return;
        }

        // (2) Entity resolve — bad ULID or absent entity is 404 (DP-6).
        String rawEntityId = ctx.pathParam("entityId");
        EntityId entityId = parseEntityId(rawEntityId);
        if (entityId == null || entityRegistry.findEntity(entityId).isEmpty()) {
            EndpointResponses.problem(ctx, ProblemType.NOT_FOUND,
                    "Entity not found: " + rawEntityId);
            return;
        }

        // (3) Tier-1 command validation — 422 with the reason verbatim (DP-6).
        CommandValidator.ValidationResult validation =
                commandValidator.validate(entityId, request.command(), request.parameters());
        if (!validation.valid()) {
            EndpointResponses.problem(ctx, ProblemType.INVALID_COMMAND, validation.reason());
            return;
        }

        // (4) Idempotency — replay / conflict / miss (DP-4).
        String idempotencyKey = ctx.requestHeader("Idempotency-Key");
        String fingerprint = null;
        if (idempotencyKey != null) {
            if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
                problemWithFields(ctx, "Idempotency-Key header exceeds "
                                + MAX_IDEMPOTENCY_KEY_LENGTH + " characters",
                        List.of(fieldError("Idempotency-Key",
                                "must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH
                                        + " characters")));
                return;
            }
            fingerprint = IdempotencyCache.fingerprint(entityId.toString(),
                    request.capability(), request.command(), request.parameters());
            IdempotencyCache.Result cached = idempotencyCache.get(idempotencyKey, fingerprint);
            if (cached.outcome() == IdempotencyCache.Outcome.REPLAY) {
                IdempotencyEntry entry = cached.entry();
                respondAccepted(ctx, entry.commandId(), entry.correlationId(),
                        entityId.toString(), entry.createdAt(), entry.viewPosition());
                return;
            }
            if (cached.outcome() == IdempotencyCache.Outcome.CONFLICT) {
                EndpointResponses.problem(ctx, ProblemType.IDEMPOTENCY_KEY_CONFLICT,
                        "Idempotency-Key '" + idempotencyKey
                                + "' was already used with a different request body");
                return;
            }
        }

        // (5) Publish ONE root command_issued — durable at return (INV-ES-04).
        String parametersJson = serializeParameters(request.parameters());
        if (parametersJson == null) {
            problemWithFields(ctx, "Command parameters are not serializable as JSON",
                    List.of(fieldError("parameters", "must be JSON-serializable values")));
            return;
        }
        Optional<CommandDefinition> definition =
                resolveCommandDefinition(entityId, request.command());
        int timeoutMs = resolveTimeoutMs(definition);
        CommandIdempotency idempotency = definition
                .map(d -> mapIdempotency(d.idempotencyClass()))
                .orElse(CommandIdempotency.NOT_IDEMPOTENT); // never silently re-fire
        CommandIssuedEvent payload = new CommandIssuedEvent(entityId.value(),
                request.command(), parametersJson, timeoutMs, idempotency);
        EventDraft draft = new EventDraft(EventTypes.COMMAND_ISSUED, SCHEMA_VERSION,
                null, SubjectRef.entity(entityId), EventPriority.NORMAL,
                EventOrigin.USER_COMMAND, payload, null, null);
        EventEnvelope envelope;
        try {
            envelope = eventPublisher.publishRoot(draft);
        } catch (SequenceConflictException ex) {
            LOG.error("command_issued publish failed: sequence conflict for entity {}",
                    entityId, ex);
            EndpointResponses.problem(ctx, ProblemType.INTERNAL_ERROR,
                    "Command persistence failed: concurrent sequence conflict");
            return;
        }

        // (6) 202 — acceptance is durability, not execution.
        String commandId = envelope.eventId().toString();
        String correlationId = envelope.causalContext().correlationId().toString();
        if (idempotencyKey != null) {
            idempotencyCache.store(idempotencyKey, fingerprint, new IdempotencyEntry(
                    idempotencyKey, commandId, correlationId,
                    envelope.globalPosition(), envelope.ingestTime()));
        }
        respondAccepted(ctx, commandId, correlationId, entityId.toString(),
                envelope.ingestTime(), envelope.globalPosition());
    }

    // ── Request parsing ─────────────────────────────────────────────────

    /**
     * Parses the request body into a {@link CommandRequest}, writing the 400
     * problem (with {@code errors[]} FieldErrors) and returning {@code null}
     * on any parse/shape failure. Unknown body fields are ignored (the
     * FAIL_ON_UNKNOWN_PROPERTIES=false posture — extraction is field-driven).
     */
    private CommandRequest parseRequest(EndpointContext ctx) {
        JsonNode root;
        try {
            root = MAPPER.readTree(ctx.body());
        } catch (JsonProcessingException ex) {
            problemWithFields(ctx, "Request body is not valid JSON",
                    List.of(fieldError("body", "must be a JSON object")));
            return null;
        }
        if (root == null || !root.isObject()) {
            problemWithFields(ctx, "Request body must be a JSON object",
                    List.of(fieldError("body", "must be a JSON object")));
            return null;
        }
        List<Map<String, Object>> errors = new ArrayList<>();
        String capability = textField(root, "capability", errors);
        String command = textField(root, "command", errors);
        Map<String, Object> parameters = null;
        JsonNode parametersNode = root.get("parameters");
        if (parametersNode == null || parametersNode.isNull() || !parametersNode.isObject()) {
            errors.add(fieldError("parameters", "required and must be a JSON object"));
        } else {
            parameters = toParameterMap(parametersNode);
        }
        if (!errors.isEmpty()) {
            problemWithFields(ctx, "Command request body failed validation", errors);
            return null;
        }
        return new CommandRequest(capability, command, parameters);
    }

    private static String textField(JsonNode root, String field,
                                    List<Map<String, Object>> errors) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank()) {
            errors.add(fieldError(field, "required and must be a non-blank string"));
            return null;
        }
        return node.asText();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toParameterMap(JsonNode parametersNode) {
        return MAPPER.convertValue(parametersNode, LinkedHashMap.class);
    }

    private static EntityId parseEntityId(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return EntityId.of(Ulid.parse(raw));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    // ── Publish-shape resolution (mirrors StandardActionExecutor, DP-3) ──

    /** The target capability's command definition for {@code commandName}, if any declares it. */
    private Optional<CommandDefinition> resolveCommandDefinition(EntityId target,
                                                                 String commandName) {
        Optional<Entity> entity = entityRegistry.findEntity(target);
        if (entity.isEmpty()) {
            return Optional.empty();
        }
        for (CapabilityInstance instance : entity.get().capabilities()) {
            CommandDefinition definition = instance.commands().get(commandName);
            if (definition != null) {
                return Optional.of(definition);
            }
        }
        return Optional.empty();
    }

    /**
     * The {@code confirmationTimeoutMs} for {@code command_issued}: the
     * capability's positive {@code default_timeout}, else the injected config
     * fallback (Doc 07 §9). Always {@code > 0}.
     */
    private int resolveTimeoutMs(Optional<CommandDefinition> definition) {
        if (definition.isPresent()) {
            long capabilityMs = definition.get().defaultTimeout().toMillis();
            if (capabilityMs > 0) {
                return (int) Math.min(capabilityMs, Integer.MAX_VALUE);
            }
        }
        return defaultConfirmationTimeoutMs;
    }

    /** Maps the device-model idempotency class onto the event-model {@link CommandIdempotency}. */
    private static CommandIdempotency mapIdempotency(IdempotencyClass deviceClass) {
        return switch (deviceClass) {
            case IDEMPOTENT -> CommandIdempotency.IDEMPOTENT;
            case NOT_IDEMPOTENT -> CommandIdempotency.NOT_IDEMPOTENT;
            case CONDITIONAL -> CommandIdempotency.CONDITIONAL;
        };
    }

    /** Serializes to a non-blank JSON object string (the AMD-95 {@code {}} floor). */
    private static String serializeParameters(Map<String, Object> parameters) {
        try {
            String json = MAPPER.writeValueAsString(parameters);
            return (json == null || json.isBlank()) ? EMPTY_PARAMETERS_JSON : json;
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    // ── Response building (the live {data, meta} inline idiom, DP-2) ────

    private void respondAccepted(EndpointContext ctx, String commandId, String correlationId,
                                 String entityId, Instant acceptedAt, long persistedPosition) {
        long cursor = viewPositionSupplier.getAsLong();

        Map<String, Object> data = new LinkedHashMap<>(6);
        data.put("commandId", commandId);
        data.put("correlationId", correlationId);
        data.put("entityId", entityId);
        data.put("status", "accepted");
        data.put("acceptedAt", acceptedAt.toString());
        data.put("viewPosition", persistedPosition);

        Map<String, Object> meta = new LinkedHashMap<>(2);
        meta.put("viewPosition", cursor);
        meta.put("timestamp", clock.instant().toString());

        Map<String, Object> body = new LinkedHashMap<>(2);
        body.put("data", data);
        body.put("meta", meta);

        ctx.status(202);
        ctx.header("Cache-Control", "no-store");
        ctx.header(ListEntitiesEndpoint.VIEW_POSITION_HEADER, Long.toString(cursor));
        ctx.json(body);
    }

    private static void problemWithFields(EndpointContext ctx, String detail,
                                          List<Map<String, Object>> errors) {
        Map<String, Object> body =
                EndpointResponses.problemBody(ProblemType.INVALID_PARAMETERS, detail);
        body.put("errors", errors);
        ctx.status(ProblemType.INVALID_PARAMETERS.defaultStatus());
        ctx.json(body);
    }

    private static Map<String, Object> fieldError(String field, String message) {
        Map<String, Object> error = new LinkedHashMap<>(2);
        error.put("field", field);
        error.put("message", message);
        return error;
    }
}
