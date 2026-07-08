/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import com.homesynapse.event.AttributeSchemaRef;
import com.homesynapse.event.CapabilityInstanceRef;
import com.homesynapse.event.CommandDefinitionRef;
import com.homesynapse.event.ConfirmationPolicyRef;
import com.homesynapse.event.DeviceRegisteredEvent;
import com.homesynapse.event.EntityRegisteredEvent;
import com.homesynapse.event.ExpectationRef;
import com.homesynapse.event.ExpectedOutcomeRef;
import com.homesynapse.event.HardwareIdentifierRef;
import com.homesynapse.event.ParameterSchemaRef;
import com.homesynapse.platform.identity.AreaId;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.Ulid;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The AMD-99 mapping seam between the domain registry records and the
 * full-fidelity registration payloads: {@code toPayload(...)} on the emit side,
 * {@code toDevice(...)}/{@code toEntity(...)} on the apply side.
 *
 * <p>Every domain component maps — no silent drops (AMD-99 §3 STOP gate). The
 * mirrors must track the device-model schema: a new component on
 * {@link Device}/{@link Entity}/{@link CapabilityInstance}/{@link AttributeSchema}/
 * {@link CommandDefinition}/{@link ParameterSchema}/{@link ExpectedOutcome}/
 * {@link Expectation}/{@link ConfirmationPolicy} REQUIRES a mirror field or an
 * explicit exclusion ruling in the register.</p>
 *
 * <p><strong>Enum handling.</strong> Enums flatten to {@code name()} strings on
 * the emit side and reconstruct via {@code valueOf(...)} on the apply side. An
 * unknown enum name arriving from a FUTURE log fails loudly
 * ({@link IllegalArgumentException} — the bus's subscriber-isolation/DLQ path
 * handles it); it is never silently coerced.</p>
 *
 * <p><strong>Typed identity (LTD-04).</strong> Payload ids are raw {@link Ulid}
 * values; the typed wrappers ({@link DeviceId}, {@link EntityId}) are
 * reconstructed here at apply time. {@code integrationId} crosses as its
 * Crockford Base32 string (the ratified AMD-99 §3 field contract).</p>
 *
 * <p>Stateless, pure, thread-safe.</p>
 *
 * @see RegistryProjection
 */
public final class RegistryEventMapper {

    private RegistryEventMapper() {
        // Static mapping functions only.
    }

    // ── Emit side ───────────────────────────────────────────────────────────

    /**
     * Maps a domain device record to its full-fidelity registration payload.
     *
     * @param device the device to map, never {@code null}
     * @return the payload; never {@code null}
     */
    public static DeviceRegisteredEvent toPayload(Device device) {
        Objects.requireNonNull(device, "device must not be null");
        return new DeviceRegisteredEvent(
                device.deviceId().value(),
                device.deviceSlug(),
                device.displayName(),
                device.manufacturer(),
                device.model(),
                device.serialNumber(),
                device.firmwareVersion(),
                device.hardwareVersion(),
                device.integrationId().value().toString(),
                device.areaId() == null ? null : device.areaId().value(),
                device.viaDeviceId() == null ? null : device.viaDeviceId().value(),
                device.labels(),
                device.hardwareIdentifiers().stream()
                        .map(id -> new HardwareIdentifierRef(id.namespace(), id.value()))
                        .toList(),
                device.createdAt());
    }

    /**
     * Maps a domain entity record to its full-fidelity registration payload,
     * capabilities and installed confirmation tuning included.
     *
     * @param entity the entity to map — must be device-backed (registration
     *        events describe adopted hardware; helper entities have no
     *        registration emission path), never {@code null}
     * @return the payload; never {@code null}
     * @throws NullPointerException if {@code entity.deviceId()} is {@code null}
     */
    public static EntityRegisteredEvent toPayload(Entity entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        Objects.requireNonNull(entity.deviceId(),
                "entity deviceId must not be null: registration events describe "
                        + "device-backed entities; helper entities are not registrable");
        return new EntityRegisteredEvent(
                entity.entityId().value(),
                entity.entitySlug(),
                entity.entityType().name(),
                entity.displayName(),
                entity.deviceId().value(),
                entity.endpointIndex(),
                entity.areaId() == null ? null : entity.areaId().value(),
                entity.enabled(),
                entity.labels(),
                entity.entityRole().name(),
                entity.createdAt(),
                entity.capabilities().stream()
                        .map(RegistryEventMapper::toRef)
                        .toList());
    }

    // ── Apply side ──────────────────────────────────────────────────────────

    /**
     * Reconstructs the domain device record from a registration payload.
     *
     * @param event the payload, never {@code null}
     * @return the device; never {@code null}
     */
    public static Device toDevice(DeviceRegisteredEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        return new Device(
                new DeviceId(event.deviceId()),
                event.deviceSlug(),
                event.displayName(),
                event.manufacturer(),
                event.model(),
                event.serialNumber(),
                event.firmwareVersion(),
                event.hardwareVersion(),
                new IntegrationId(Ulid.parse(event.integrationId())),
                event.areaId() == null ? null : new AreaId(event.areaId()),
                event.viaDeviceId() == null ? null : new DeviceId(event.viaDeviceId()),
                event.labels(),
                event.hardwareIdentifiers().stream()
                        .map(ref -> new HardwareIdentifier(ref.namespace(), ref.value()))
                        .collect(Collectors.toUnmodifiableSet()),
                event.createdAt());
    }

    /**
     * Reconstructs the domain entity record from a registration payload.
     *
     * @param event the payload, never {@code null}
     * @return the entity; never {@code null}
     * @throws IllegalArgumentException if an enum name in the payload is
     *         unknown to this build (a future log replayed on older code —
     *         fail loudly, never coerce)
     */
    public static Entity toEntity(EntityRegisteredEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        return new Entity(
                new EntityId(event.entityId()),
                event.entitySlug(),
                EntityType.valueOf(event.entityType()),
                event.displayName(),
                new DeviceId(event.deviceId()),
                event.endpointIndex(),
                event.areaId() == null ? null : new AreaId(event.areaId()),
                event.enabled(),
                event.labels(),
                event.capabilities().stream()
                        .map(RegistryEventMapper::fromRef)
                        .toList(),
                EntityRole.valueOf(event.entityRole()),
                event.createdAt());
    }

    // ── Capability forest (emit) ────────────────────────────────────────────

    private static CapabilityInstanceRef toRef(CapabilityInstance instance) {
        return new CapabilityInstanceRef(
                instance.capabilityId(),
                instance.version(),
                instance.namespace(),
                instance.featureMap(),
                mapValues(instance.attributes(), RegistryEventMapper::toRef),
                mapValues(instance.commands(), RegistryEventMapper::toRef),
                toRef(instance.confirmation()));
    }

    private static AttributeSchemaRef toRef(AttributeSchema schema) {
        return new AttributeSchemaRef(
                schema.attributeKey(),
                schema.type(),
                schema.minimum(),
                schema.maximum(),
                schema.step(),
                schema.validValues() == null ? null : List.copyOf(schema.validValues()),
                schema.unitSymbol(),
                schema.canonicalUnitSymbol(),
                schema.permissions().stream().map(Enum::name).toList(),
                schema.nullable(),
                schema.persistent());
    }

    private static CommandDefinitionRef toRef(CommandDefinition command) {
        return new CommandDefinitionRef(
                command.commandType(),
                command.parameters().stream().map(RegistryEventMapper::toRef).toList(),
                command.requiredFeatures(),
                command.expectedOutcomes().stream()
                        .map(RegistryEventMapper::toRef)
                        .toList(),
                command.defaultTimeout(),
                command.idempotencyClass().name());
    }

    private static ParameterSchemaRef toRef(ParameterSchema parameter) {
        return new ParameterSchemaRef(
                parameter.parameterName(),
                parameter.type(),
                parameter.minimum(),
                parameter.maximum(),
                parameter.required(),
                parameter.requiredFeatures(),
                parameter.validValues() == null
                        ? null : List.copyOf(parameter.validValues()));
    }

    private static ExpectedOutcomeRef toRef(ExpectedOutcome outcome) {
        return new ExpectedOutcomeRef(
                outcome.attributeKey(),
                toRef(outcome.expectation()),
                outcome.timeoutMs());
    }

    /** Exhaustive over the four domain permits — a fifth breaks compilation here. */
    private static ExpectationRef toRef(Expectation expectation) {
        return switch (expectation) {
            case ExactMatch em -> new ExpectationRef.ExactMatchRef(em.expectedValue());
            case WithinTolerance wt ->
                    new ExpectationRef.WithinToleranceRef(wt.target(), wt.tolerance());
            case EnumTransition et ->
                    new ExpectationRef.EnumTransitionRef(et.expectedValue());
            case AnyChange ac -> new ExpectationRef.AnyChangeRef(ac.previousValue());
        };
    }

    private static ConfirmationPolicyRef toRef(ConfirmationPolicy policy) {
        return new ConfirmationPolicyRef(
                policy.mode().name(),
                policy.authoritativeAttributes(),
                policy.defaultTolerance(),
                policy.defaultTimeoutMs());
    }

    // ── Capability forest (apply) ───────────────────────────────────────────

    private static CapabilityInstance fromRef(CapabilityInstanceRef ref) {
        return new CapabilityInstance(
                ref.capabilityId(),
                ref.version(),
                ref.namespace(),
                ref.featureMap(),
                mapValues(ref.attributes(), RegistryEventMapper::fromRef),
                mapValues(ref.commands(), RegistryEventMapper::fromRef),
                fromRef(ref.confirmation()));
    }

    private static AttributeSchema fromRef(AttributeSchemaRef ref) {
        return new AttributeSchema(
                ref.attributeKey(),
                ref.type(),
                ref.minimum(),
                ref.maximum(),
                ref.step(),
                ref.validValues() == null ? null : Set.copyOf(ref.validValues()),
                ref.unitSymbol(),
                ref.canonicalUnitSymbol(),
                ref.permissions().stream()
                        .map(Permission::valueOf)
                        .collect(Collectors.toUnmodifiableSet()),
                ref.nullable(),
                ref.persistent());
    }

    private static CommandDefinition fromRef(CommandDefinitionRef ref) {
        return new CommandDefinition(
                ref.commandType(),
                ref.parameters().stream().map(RegistryEventMapper::fromRef).toList(),
                ref.requiredFeatures(),
                ref.expectedOutcomes().stream()
                        .map(RegistryEventMapper::fromRef)
                        .toList(),
                ref.defaultTimeout(),
                IdempotencyClass.valueOf(ref.idempotencyClass()));
    }

    private static ParameterSchema fromRef(ParameterSchemaRef ref) {
        return new ParameterSchema(
                ref.parameterName(),
                ref.type(),
                ref.minimum(),
                ref.maximum(),
                ref.required(),
                ref.requiredFeatures(),
                ref.validValues() == null ? null : Set.copyOf(ref.validValues()));
    }

    private static ExpectedOutcome fromRef(ExpectedOutcomeRef ref) {
        return new ExpectedOutcome(
                ref.attributeKey(),
                fromRef(ref.expectation()),
                ref.timeoutMs());
    }

    /** Exhaustive over the four mirror permits — a fifth breaks compilation here. */
    private static Expectation fromRef(ExpectationRef ref) {
        return switch (ref) {
            case ExpectationRef.ExactMatchRef em -> new ExactMatch(em.expectedValue());
            case ExpectationRef.WithinToleranceRef wt ->
                    new WithinTolerance(wt.target(), wt.tolerance());
            case ExpectationRef.EnumTransitionRef et ->
                    new EnumTransition(et.expectedValue());
            case ExpectationRef.AnyChangeRef ac -> new AnyChange(ac.previousValue());
        };
    }

    private static ConfirmationPolicy fromRef(ConfirmationPolicyRef ref) {
        return new ConfirmationPolicy(
                ConfirmationMode.valueOf(ref.mode()),
                ref.authoritativeAttributes(),
                ref.defaultTolerance(),
                ref.defaultTimeoutMs());
    }

    // ── Shared ──────────────────────────────────────────────────────────────

    private static <V, R> Map<String, R> mapValues(Map<String, V> source,
            java.util.function.Function<V, R> mapper) {
        Map<String, R> mapped = new LinkedHashMap<>();
        source.forEach((key, value) -> mapped.put(key, mapper.apply(value)));
        return mapped;
    }
}
