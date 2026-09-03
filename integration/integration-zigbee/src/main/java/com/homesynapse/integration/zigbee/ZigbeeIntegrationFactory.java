/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.device.DeviceRegistry;
import com.homesynapse.device.RegistryProjection;
import com.homesynapse.integration.DataPath;
import com.homesynapse.integration.HealthParameters;
import com.homesynapse.integration.IntegrationAdapter;
import com.homesynapse.integration.IntegrationContext;
import com.homesynapse.integration.IntegrationDescriptor;
import com.homesynapse.integration.IoType;
import com.homesynapse.integration.PermanentIntegrationException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The zigbee {@link ZigbeeAdapterFactory} (Doc 08 §4.1, M9.4a §4.1) — constructed
 * directly by the application wiring (DECIDE-04, LTD-17) and passed to the
 * supervisor's {@code start(...)}. {@code descriptor()} is a pure function;
 * {@code create(...)} assembles the adapter internals from the
 * {@link IntegrationContext} plus the adapter-owned dependencies the context does
 * not carry: the {@link DeviceRegistry} (the adoption dedup surface — constructor-
 * injected per the M9.3 R4 seam, NOT an {@code IntegrationContext} component), the
 * adapter data directory (the {@code zigbee-devices.json} home), and the injected
 * {@link Clock} (NO_DIRECT_TIME_ACCESS — the frozen 12-component context carries
 * no clock).
 *
 * <p><strong>The transport seam:</strong> the byte-channel supplier is
 * package-private-injectable so the hardware-free gates run the REAL adapter code
 * over a scripted NCP (driven mode). The public constructor builds the PRODUCTION
 * path (M9.4b §5.1): the adapter locates the coordinator port at {@code run()}
 * (the {@code integrations.zigbee.serial_port} key, else the VID:PID locator —
 * never descriptor strings, AMD-96/E2), probes it, and binds the real jSerialComm
 * channel. jSerialComm types stay interior to this module (D-M92-1).</p>
 *
 * <p>Thread-safe: the factory holds immutable wiring inputs only.</p>
 */
public final class ZigbeeIntegrationFactory implements ZigbeeAdapterFactory {

    /** The integration type — matches the {@code integrations.zigbee} schema key. */
    public static final String INTEGRATION_TYPE = "zigbee";

    private final Supplier<DeviceRegistry> deviceRegistry;
    private final Supplier<RegistryProjection> registryProjection;
    private final Path dataDirectory;
    private final Clock clock;
    private final Function<Object, SerialByteChannel> channelOpener;
    private volatile ZigbeeIntegrationAdapter lastCreated;

    /**
     * Creates the production factory (M9.4b §5.1): adapters locate, probe, and bind
     * the real serial coordinator at {@code run()} (port location is I/O and never
     * runs at {@code initialize()} — INV-RF-03).
     *
     * @param deviceRegistry supplies the device registry (the adoption slice's IEEE
     *        dedup surface — R4). A supplier because the registry the composition
     *        root owns is assembled during {@code start()}, after the factory list
     *        is constructed; it is resolved once, at {@code create(...)} (Phase 6).
     *        Never {@code null}, and must not supply {@code null}
     * @param registryProjection supplies the AMD-99 single registry-apply path
     *        (REG-INV-1) — the same supplier shape and rationale as
     *        {@code deviceRegistry}: the composition root constructs the
     *        projection in Phase 3, resolved once at {@code create(...)}
     *        (Phase 6). Never {@code null}, and must not supply {@code null}
     * @param dataDirectory the adapter data directory (the device-cache home),
     *        never {@code null}
     * @param clock the injected time source, never {@code null}
     */
    public ZigbeeIntegrationFactory(Supplier<DeviceRegistry> deviceRegistry,
            Supplier<RegistryProjection> registryProjection,
            Path dataDirectory, Clock clock) {
        this(deviceRegistry, registryProjection, dataDirectory, clock, null);
    }

    /**
     * The transport-injectable seam (bench/test): the channel supplier replaces the
     * real serial port — the E2E gates substitute the scripted NCP here.
     */
    ZigbeeIntegrationFactory(Supplier<DeviceRegistry> deviceRegistry,
            Supplier<RegistryProjection> registryProjection, Path dataDirectory,
            Clock clock, Function<Object, SerialByteChannel> channelOpener) {
        this.deviceRegistry = Objects.requireNonNull(deviceRegistry, "deviceRegistry");
        this.registryProjection = Objects.requireNonNull(registryProjection,
                "registryProjection");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.channelOpener = channelOpener;
    }

    /**
     * The bundled {@code integrations.zigbee} config schema fragment (Doc 08 §9),
     * read from this module's classpath — the input the composition root passes to
     * {@code HomeSynapseCore.registerIntegrationSchema("zigbee", …)} BEFORE
     * {@code start()} (PKG-SEC-2: static text, composed into the root schema ahead
     * of Phase-1 validation; the former W10 post-Phase-6 registration is retired).
     * Every {@code default} the fragment declares is operative from Phase 1 on —
     * see the resource's {@code $comment} for the permit-join consequence.
     *
     * @return the schema JSON text, never {@code null}
     */
    public static String configSchemaJson() {
        try (InputStream in = ZigbeeIntegrationFactory.class
                .getResourceAsStream("/schema/zigbee-config-schema.json")) {
            if (in == null) {
                throw new IllegalStateException(
                        "schema/zigbee-config-schema.json is missing from the "
                                + "integration-zigbee module resources");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to read schema/zigbee-config-schema.json", e);
        }
    }

    @Override
    public IntegrationDescriptor descriptor() {
        // RequiredServices stays empty in M9.4a: the M9.1 supervisor composes no
        // scheduler/telemetry services yet (the context tails are null) — declaring
        // them would be a lie the supervisor cannot honor.
        return new IntegrationDescriptor(
                INTEGRATION_TYPE,
                "Zigbee Coordinator",
                IoType.SERIAL,
                Set.of(),
                Set.of(DataPath.DOMAIN),
                HealthParameters.defaults(),
                Set.of(),
                1);
    }

    @Override
    public IntegrationAdapter create(IntegrationContext context)
            throws PermanentIntegrationException {
        Objects.requireNonNull(context, "context");
        DeviceRegistry registry = deviceRegistry.get();
        if (registry == null) {
            throw new PermanentIntegrationException("zigbee.device_registry_unbound",
                    "The device-registry supplier resolved null at create(); the "
                            + "composition root must bind the registry before Phase 6");
        }
        RegistryProjection projection = registryProjection.get();
        if (projection == null) {
            throw new PermanentIntegrationException(
                    "zigbee.registry_projection_unbound",
                    "The registry-projection supplier resolved null at create(); the "
                            + "composition root constructs it in Phase 3 — it must be "
                            + "bound before Phase 6 (AMD-99)");
        }
        // Driven mode (injected channel — the rig) vs production mode (§5.1): the
        // real enumerator + jSerialComm channel opener bind HERE, keeping
        // jSerialComm types interior to this module (D-M92-1).
        ZigbeeIntegrationAdapter adapter = channelOpener != null
                ? new ZigbeeIntegrationAdapter(
                        context, registry, projection, dataDirectory, clock,
                        channelOpener)
                : new ZigbeeIntegrationAdapter(
                        context, registry, projection, dataDirectory, clock, null,
                        new JSerialCommPortEnumerator(),
                        candidate -> JSerialCommByteChannel.open(
                                com.fazecast.jSerialComm.SerialPort.getCommPort(
                                        candidate.systemPath())));
        lastCreated = adapter;
        return adapter;
    }

    /** The most recently created adapter (the boot-smoke/E2E drive seam). */
    ZigbeeIntegrationAdapter lastCreated() {
        return lastCreated;
    }
}
