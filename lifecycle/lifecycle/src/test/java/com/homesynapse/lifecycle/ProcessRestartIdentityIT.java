/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.homesynapse.device.AttributeSchema;
import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.CommandDefinition;
import com.homesynapse.device.ConfirmationMode;
import com.homesynapse.device.ConfirmationPolicy;
import com.homesynapse.device.Device;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityRole;
import com.homesynapse.device.EntityType;
import com.homesynapse.device.ExactMatch;
import com.homesynapse.device.ExpectedOutcome;
import com.homesynapse.device.HardwareIdentifier;
import com.homesynapse.device.IdempotencyClass;
import com.homesynapse.device.ParameterSchema;
import com.homesynapse.device.Permission;
import com.homesynapse.device.RegistryEventMapper;
import com.homesynapse.event.DeviceRemovedEvent;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.test.TestClock;
import com.homesynapse.value.AttributeType;
import com.homesynapse.value.BooleanValue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * M9.5-DUR (AMD-99) — the full-process-restart identity leg that
 * {@link RestartHonestyIT} structurally cannot cover (it restarts only the
 * integration; this restarts the whole composition root over the SAME event
 * log): adopted identities must survive a real process-boundary rebuild.
 *
 * <p>Boot 1 publishes {@code device_registered} + {@code entity_registered}×2
 * (tuned {@code ConfirmationPolicy} present) and asserts the live projection
 * applies them. Boot 2 opens the SAME dbPath and asserts the SAME
 * deviceId/entityIds resolve with EQUAL records (capabilities + tuning
 * included), and that the {@code registry.projection_live} positive-evidence
 * INFO fired (REG-INV-1: at boot the registries are reconstructed by replaying
 * the registration events). Boot 3 proves the {@code device_removed} tombstone
 * leg: replay applies the removal, so the device is gone post-rebuild
 * (carry-list C4).</p>
 */
@DisplayName("ProcessRestartIdentityIT — identities survive a full process restart "
        + "(AMD-99 / REG-INV-1)")
final class ProcessRestartIdentityIT {

    private static final HomeId TEST_HOME_ID =
            HomeId.of(Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAAC"));
    private static final DeviceId DEVICE_ID =
            DeviceId.parse("01JAAAAAAAAAAAAAAAAAAAAAD1");
    private static final EntityId LIGHT_ENTITY_ID =
            EntityId.parse("01JAAAAAAAAAAAAAAAAAAAAAE1");
    private static final EntityId SENSOR_ENTITY_ID =
            EntityId.parse("01JAAAAAAAAAAAAAAAAAAAAAE2");
    private static final IntegrationId INTEGRATION_ID =
            IntegrationId.parse("01JAAAAAAAAAAAAAAAAAAAAAD2");
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private HomeSynapseCore core;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    ProcessRestartIdentityIT() {
    }

    @AfterEach
    void tearDown() {
        if (core != null) {
            core.stop();
        }
    }

    @Test
    @DisplayName("same ids + equal tuned records across a real process-boundary rebuild; "
            + "tombstone removes across the next restart")
    void identitiesSurviveProcessRestart(@TempDir Path tempDir) throws Exception {
        // ── Boot 1: publish the registration facts; the live projection applies. ──
        boot(tempDir);
        publishRegistration(RegistryEventMapper.toPayload(device()),
                SubjectRef.device(DEVICE_ID), EventTypes.DEVICE_REGISTERED);
        publishRegistration(RegistryEventMapper.toPayload(lightEntity()),
                SubjectRef.entity(LIGHT_ENTITY_ID), EventTypes.ENTITY_REGISTERED);
        publishRegistration(RegistryEventMapper.toPayload(sensorEntity()),
                SubjectRef.entity(SENSOR_ENTITY_ID), EventTypes.ENTITY_REGISTERED);

        awaitTrue(() -> core.deviceRegistry().findDevice(DEVICE_ID).isPresent()
                        && core.entityRegistry().findEntity(LIGHT_ENTITY_ID).isPresent()
                        && core.entityRegistry().findEntity(SENSOR_ENTITY_ID).isPresent(),
                "the live projection applying the three registration events");
        Device appliedDevice = core.deviceRegistry().getDevice(DEVICE_ID);
        Entity appliedLight = core.entityRegistry().getEntity(LIGHT_ENTITY_ID);
        Entity appliedSensor = core.entityRegistry().getEntity(SENSOR_ENTITY_ID);
        core.stop();
        core = null;

        // ── Boot 2: a NEW core over the SAME dbPath — replay rebuilds. ──────────
        ListAppender<ILoggingEvent> projectionLog = attachProjectionLogCapture();
        try {
            boot(tempDir);
            // onCaughtUp fires on the subscriber's VT as the TRANSITION -> LIVE
            // switch completes — await it rather than racing the callback.
            awaitTrue(() -> projectionLog.list.stream()
                            .map(ILoggingEvent::getFormattedMessage)
                            .anyMatch(message ->
                                    message.startsWith("registry.projection_live: ")),
                    "the registry.projection_live INFO");
        } finally {
            detachProjectionLogCapture(projectionLog);
        }

        assertThat(core.deviceRegistry().findDevice(DEVICE_ID))
                .as("the SAME deviceId resolves after a full process restart")
                .isPresent()
                .contains(appliedDevice);
        assertThat(core.entityRegistry().findEntity(LIGHT_ENTITY_ID))
                .as("the SAME entityId resolves with the tuned record intact")
                .isPresent()
                .contains(appliedLight);
        assertThat(core.entityRegistry().findEntity(SENSOR_ENTITY_ID))
                .isPresent()
                .contains(appliedSensor);
        assertThat(core.entityRegistry().getEntity(LIGHT_ENTITY_ID)
                .capabilities().get(0).confirmation())
                .as("the adopted DP-a tuning is provable across restarts (AMD-97-INV-01)")
                .isEqualTo(tunedPolicy());
        assertThat(core.deviceRegistry()
                .findByHardwareIdentifier("zigbee", "0011223344556677"))
                .as("the IEEE binding derives from the replayed hardwareIdentifiers")
                .isPresent();
        assertThat(projectionLog.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("registry.projection_live: ")))
                .as("the boot-rebuild positive-evidence INFO fired exactly once")
                .hasSize(1)
                .first()
                .asString()
                .startsWith("registry.projection_live: devices=1 entities=2");

        // ── Boot 3: tombstone leg — device_removed replays as a removal. ────────
        core.eventPublisher().publishRoot(new EventDraft(
                EventTypes.DEVICE_REMOVED, 1, null, SubjectRef.device(DEVICE_ID),
                EventPriority.NORMAL, EventOrigin.SYSTEM,
                new DeviceRemovedEvent("restart-identity-test"), null, null));
        awaitTrue(() -> core.deviceRegistry().findDevice(DEVICE_ID).isEmpty(),
                "the live projection applying the tombstone");
        core.stop();
        core = null;

        boot(tempDir);

        assertThat(core.deviceRegistry().findDevice(DEVICE_ID))
                .as("the tombstone replays: the removed device stays gone post-rebuild")
                .isEmpty();
        assertThat(core.entityRegistry().findEntity(LIGHT_ENTITY_ID)).isEmpty();
        assertThat(core.entityRegistry().findEntity(SENSOR_ENTITY_ID)).isEmpty();
        assertThat(core.entityRegistry().listAllEntities()).isEmpty();
    }

    // ── Fixtures (canonical Numbers — the mirror boundary's Integer/Double pair) ──

    private static ConfirmationPolicy tunedPolicy() {
        return new ConfirmationPolicy(
                ConfirmationMode.TOLERANCE, List.of("color_temp_kelvin"), 50, 15000L);
    }

    private static Device device() {
        return new Device(
                DEVICE_ID, "zigbee-0011223344556677", "Signify Hue Bulb",
                "Signify", "LWA021", null, "1.108.7", null,
                INTEGRATION_ID, null, null, List.of("hero"),
                Set.of(new HardwareIdentifier("zigbee", "0011223344556677")),
                CREATED_AT);
    }

    private static Entity lightEntity() {
        AttributeSchema colorTemp = new AttributeSchema(
                "color_temp_kelvin", AttributeType.INT, 2000, 6500, 1, null,
                "K", "K", Set.of(Permission.READ, Permission.WRITE), false, true);
        CommandDefinition setColorTemp = new CommandDefinition(
                "set_color_temperature",
                List.of(new ParameterSchema(
                        "kelvin", AttributeType.INT, 2000, 6500, true, 0, null)),
                0,
                List.of(new ExpectedOutcome(
                        "on", new ExactMatch(new BooleanValue(true)), 15000L)),
                Duration.ofSeconds(15),
                IdempotencyClass.IDEMPOTENT);
        CapabilityInstance tuned = new CapabilityInstance(
                "color_temperature", 1, "core", 0,
                Map.of("color_temp_kelvin", colorTemp),
                Map.of("set_color_temperature", setColorTemp),
                tunedPolicy());
        return new Entity(
                LIGHT_ENTITY_ID, "zigbee-0011223344556677-ep1", EntityType.LIGHT,
                "Signify Hue Bulb", DEVICE_ID, 1, null, true, List.of(),
                List.of(tuned), EntityRole.PRIMARY, CREATED_AT);
    }

    private static Entity sensorEntity() {
        AttributeSchema temperature = new AttributeSchema(
                "temperature_c", AttributeType.FLOAT, -40.0d, 125.0d, null, null,
                "°C", "°C", Set.of(Permission.READ), false, true);
        CapabilityInstance measurement = new CapabilityInstance(
                "temperature_measurement", 1, "core", 0,
                Map.of("temperature_c", temperature),
                Map.of(),
                new ConfirmationPolicy(
                        ConfirmationMode.DISABLED, List.of(), null, 5000L));
        return new Entity(
                SENSOR_ENTITY_ID, "zigbee-0011223344556677-ep2", EntityType.SENSOR,
                "Signify Hue Bulb", DEVICE_ID, 2, null, true, List.of(),
                List.of(measurement), EntityRole.DIAGNOSTIC, CREATED_AT);
    }

    // ── Harness ─────────────────────────────────────────────────────────────

    private void boot(Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("config"));
        core = new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"),
                tempDir.resolve("config"),
                HomeSynapseConfig.testing(),
                TestClock.createDefault(),
                TEST_HOME_ID,
                null);
        core.start();
    }

    private void publishRegistration(DomainEvent payload, SubjectRef subjectRef,
            String eventType) throws Exception {
        core.eventPublisher().publishRoot(new EventDraft(
                eventType, 1, null, subjectRef, EventPriority.NORMAL,
                EventOrigin.INTEGRATION, payload, null, null));
    }

    private static ListAppender<ILoggingEvent> attachProjectionLogCapture() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        subscriberLogger().addAppender(appender);
        return appender;
    }

    private static void detachProjectionLogCapture(ListAppender<ILoggingEvent> appender) {
        subscriberLogger().detachAppender(appender);
        appender.stop();
    }

    private static ch.qos.logback.classic.Logger subscriberLogger() {
        return (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(RegistryProjectionSubscriber.class);
    }

    private static void awaitTrue(BooleanSupplier condition, String what) {
        for (int poll = 0; poll < 500; poll++) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted awaiting " + what, ex);
            }
        }
        throw new AssertionError("timed out awaiting " + what);
    }
}
