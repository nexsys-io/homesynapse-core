/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.device.DeviceRegistry;
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
 * <p><strong>The transport seam (M9.4a):</strong> the byte-channel supplier is
 * package-private-injectable so the hardware-free gates run the REAL adapter code
 * over a scripted NCP. The public constructor leaves it unbound — the real
 * serial-port orchestration (probe/locator/port config) is fenced to M9.4b, and an
 * unbound transport surfaces as a {@link PermanentIntegrationException} at
 * {@code initialize()} (honest FAILED-no-retry; a deaf radio that looks paired is
 * a lying system).</p>
 *
 * <p>Thread-safe: the factory holds immutable wiring inputs only.</p>
 */
public final class ZigbeeIntegrationFactory implements ZigbeeAdapterFactory {

    /** The integration type — matches the {@code integrations.zigbee} schema key. */
    public static final String INTEGRATION_TYPE = "zigbee";

    private final Supplier<DeviceRegistry> deviceRegistry;
    private final Path dataDirectory;
    private final Clock clock;
    private final Function<Object, SerialByteChannel> channelOpener;
    private volatile ZigbeeIntegrationAdapter lastCreated;

    /**
     * Creates the production factory. The serial transport itself binds at M9.4b —
     * an adapter created from this constructor reports a permanent failure at
     * {@code initialize()} rather than pretending to run (never-false-ALIVE).
     *
     * @param deviceRegistry supplies the device registry (the adoption slice's IEEE
     *        dedup surface — R4). A supplier because the registry the composition
     *        root owns is assembled during {@code start()}, after the factory list
     *        is constructed; it is resolved once, at {@code create(...)} (Phase 6).
     *        Never {@code null}, and must not supply {@code null}
     * @param dataDirectory the adapter data directory (the device-cache home),
     *        never {@code null}
     * @param clock the injected time source, never {@code null}
     */
    public ZigbeeIntegrationFactory(Supplier<DeviceRegistry> deviceRegistry,
            Path dataDirectory, Clock clock) {
        this(deviceRegistry, dataDirectory, clock, null);
    }

    /**
     * The transport-injectable seam (bench/test): the channel supplier replaces the
     * real serial port — the E2E gates substitute the scripted NCP here.
     */
    ZigbeeIntegrationFactory(Supplier<DeviceRegistry> deviceRegistry, Path dataDirectory,
            Clock clock, Function<Object, SerialByteChannel> channelOpener) {
        this.deviceRegistry = Objects.requireNonNull(deviceRegistry, "deviceRegistry");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.channelOpener = channelOpener;
    }

    /**
     * The bundled {@code integrations.zigbee} config schema fragment (Doc 08 §9),
     * read from this module's classpath — the W10 registration input the
     * composition root passes to {@code registerIntegrationSchema("zigbee", …)}
     * after Phase 6 (Doc 12: integration schemas defer past core composition).
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
        ZigbeeIntegrationAdapter adapter = new ZigbeeIntegrationAdapter(
                context, registry, dataDirectory, clock, channelOpener);
        lastCreated = adapter;
        return adapter;
    }

    /** The most recently created adapter (the boot-smoke/E2E drive seam). */
    ZigbeeIntegrationAdapter lastCreated() {
        return lastCreated;
    }
}
