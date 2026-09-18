/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.device.DeviceRegistry;
import com.homesynapse.device.RegistryProjection;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.test.TestClock;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The M9.4a hardware-free rig (testFixtures): the REAL zigbee adapter code — factory,
 * transport, ASH, EZSP, interview, ingestion, adoption, command write path — over a
 * scripted {@link FakeNcp}, deterministic under an injected {@link TestClock}. The
 * composition-root gates ({@code HeroLoopHardwareFreeIT} / {@code ZigbeeReplaySafetyIT})
 * drive it from outside the package; the rig re-exports the package-private drive
 * seams they need (adoption, the §G cycle, the callback pump).
 *
 * <p>The scripted NCP answers the full interview walk for two measured Wave-1 device
 * identities (the bench-corpus Hue LCA017 and SNZB-03P — matching the bundled
 * {@code zigbee-profiles.json} matchers), records every cluster-specific ZCL unicast
 * the adapter dispatches, and delivers rig-queued callback frames (announces,
 * attribute reports) on the next {@link #deliverAndCycle()} pump — frames enter
 * through the REAL 0x0045 parse and the REAL ingestion path, never injected sideways.
 * Provenance: identities, endpoints, and cluster inventories mirror the M9.3
 * fixture/interview scripting ({@code EzspInterviewTest}), captured from the bench
 * corpus at {@code 5ceff3b}; report frames are synthetic confirmations following the
 * measured shapes (distinct TSNs — the dedup discipline).</p>
 *
 * <p>ENERGY-READ adds a THIRD scripted identity, the metering-plug fixture
 * ({@link #GEN4_IEEE}): the owned Gen4's RECORDED signature under a synthetic
 * identity, NOT a bench capture — no metering plug has joined (THE ADOPTION
 * FENCE). For it alone the scripted NCP also answers the adoption drive's
 * reporting exchanges (binds, the two formatting reads, configure, read-back),
 * and {@link #reportAttributes} injects any report frame the rig has a wire
 * type for. The two Wave-1 identities are byte-untouched.</p>
 */
public final class ZigbeeHardwareFreeRig {

    public static final long HUE_IEEE = 0x0017880109AB12CDL;
    public static final int HUE_NWK = 0x260F;
    public static final int HUE_ENDPOINT = 11;
    public static final long SNZB_IEEE = 0x00124B0012345678L;
    public static final int SNZB_NWK = 0x6B9A;
    public static final int SNZB_ENDPOINT = 1;
    /**
     * The metering-plug fixture (ENERGY-READ): the owned Shelly Plug US Gen4's
     * RECORDED signature — EP1, device type 0x010A "On/Off Plug-in Unit", input
     * clusters 0x0000 0x0003 0x0004 0x0005 0x0006 0x0702 0x0B04 0xFC21
     * (PLUG-DOSSIER row 16 / DEVICE-SET §1) — under a SYNTHETIC identity. The
     * IEEE, the network address and the Basic strings are fixture values; the
     * output-cluster list and the manufacturer code are not in the record and
     * the fixture claims none.
     */
    public static final long GEN4_IEEE = 0x00124B00AA00E4E4L;
    public static final int GEN4_NWK = 0x7E44;
    public static final int GEN4_ENDPOINT = 1;
    /**
     * The fixture's scripted formatting — what the scripted NCP answers the
     * adoption-time formatting reads with: ACPower 1/100, ACVoltage 1/10,
     * ACCurrent 1/1000; summation 1/1,000,000 in kWh. These are FIXTURE values
     * for the rate instrument (MEASURE-2). The owned Gen4's own divisors are
     * UNREAD — the first adoption writes them into the measurement record
     * (rows G4-1/G4-2); nothing here predicts them.
     */
    public static final int GEN4_FIXTURE_AC_POWER_MULTIPLIER = 1;
    public static final int GEN4_FIXTURE_AC_POWER_DIVISOR = 100;
    public static final int GEN4_FIXTURE_SUMMATION_MULTIPLIER = 1;
    public static final int GEN4_FIXTURE_SUMMATION_DIVISOR = 1_000_000;

    /** One cluster-specific ZCL unicast as the scripted NCP received it. */
    public record SentZcl(int networkAddress, int destinationEndpoint, int clusterId,
            int commandId, byte[] payload) {
        public SentZcl {
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }

    private record ScriptedDevice(long ieee, int nwk, int endpoint, int deviceType,
            int[] inClusters, int[] outClusters, String manufacturer, String model,
            int nodeType, int macCapability, int powerSource) {
    }

    private static final ScriptedDevice HUE = new ScriptedDevice(HUE_IEEE, HUE_NWK,
            HUE_ENDPOINT, 0x010D,
            new int[] {0x0000, 0x0003, 0x0004, 0x0005, 0x0006, 0x0008, 0x0300},
            new int[] {0x0019},
            "Signify Netherlands B.V.", "LCA017", 0x01, 0x8E, 0x01);
    private static final ScriptedDevice SNZB = new ScriptedDevice(SNZB_IEEE, SNZB_NWK,
            SNZB_ENDPOINT, 0x0107,
            new int[] {0x0000, 0x0001, 0x0003, 0x0020, 0x0406, 0x0500},
            new int[] {0x0003, 0x0019},
            "eWeLink", "SNZB-03P", 0x02, 0x80, 0x03);
    private static final ScriptedDevice GEN4 = new ScriptedDevice(GEN4_IEEE, GEN4_NWK,
            GEN4_ENDPOINT, 0x010A,
            new int[] {0x0000, 0x0003, 0x0004, 0x0005, 0x0006, 0x0702, 0x0B04, 0xFC21},
            new int[] {},
            "HomeSynapse Fixture", "GEN4-SIGNATURE", 0x01, 0x8E, 0x01);

    private final TestClock clock;
    private final FakeNcp ncp = new FakeNcp();
    private final ZigbeeIntegrationFactory factory;
    private final Deque<byte[]> queuedCallbacks = new ArrayDeque<>();
    /** Lock-free (LTD-11): the command executor writes, the gate thread reads. */
    private final List<SentZcl> sentZcl = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Devices the scripted NCP accepts unicasts for but that never reply (dead). */
    private final Set<Long> silencedDevices = ConcurrentHashMap.newKeySet();
    /** The Gen4 fixture's device-side reporting store: (cluster,attr) → {type,min,max,change}. */
    private final Map<Long, long[]> gen4ReportingStore = new ConcurrentHashMap<>();
    private int reportTsn = 0x40;

    public ZigbeeHardwareFreeRig(TestClock clock, Supplier<DeviceRegistry> deviceRegistry,
            Supplier<RegistryProjection> registryProjection, Path dataDirectory) {
        this.clock = Objects.requireNonNull(clock, "clock");
        ncp.onEzspCommand(this::handleCommand);
        // A FRESH channel per open, all over the ONE scripted NCP (whose RST
        // handler resets its ASH numbering): a supervisor restartIntegration
        // re-creates the adapter and re-opens the transport — a single closed
        // channel instance would leave the restarted session deaf (M9.4b §7.3).
        this.factory = new ZigbeeIntegrationFactory(
                Objects.requireNonNull(deviceRegistry, "deviceRegistry"),
                Objects.requireNonNull(registryProjection, "registryProjection"),
                Objects.requireNonNull(dataDirectory, "dataDirectory"),
                clock, arg -> {
                    FakeSerialByteChannel fresh = new FakeSerialByteChannel(clock);
                    fresh.onWrite(ncp);
                    return fresh;
                });
    }

    /** The factory to pass into the composition root's factory list. */
    public ZigbeeIntegrationFactory factory() {
        return factory;
    }

    public TestClock clock() {
        return clock;
    }

    // ── drive ────────────────────────────────────────────────────────────────

    /** Queues a ZDO Device_annce for the scripted device (join/rejoin). */
    public void announce(long ieee) {
        ScriptedDevice device = deviceFor(ieee);
        byte[] message = new byte[12];
        message[0] = (byte) nextTsn();
        message[1] = (byte) (device.nwk() & 0xFF);
        message[2] = (byte) ((device.nwk() >> 8) & 0xFF);
        for (int i = 0; i < 8; i++) {
            message[3 + i] = (byte) (device.ieee() >> (8 * i));
        }
        message[11] = (byte) device.macCapability();
        queuedCallbacks.add(incomingMessage(device,
                EzspCoordinatorProtocol.ZDO_PROFILE_ID,
                ZdoCodec.CLUSTER_DEVICE_ANNOUNCE, 0, message));
    }

    /** Queues an SNZB occupancy report ({@code map8} bit 0 — the measured shape). */
    public void reportOccupied(boolean occupied) {
        queuedCallbacks.add(report(SNZB, 0x0406,
                attributeRecord(0x0000, 0x18, new byte[] {(byte) (occupied ? 1 : 0)})));
    }

    /** Queues a Hue OnOff report ({@code bool} 0x00/0x01). */
    public void reportOnOff(boolean on) {
        queuedCallbacks.add(report(HUE, 0x0006,
                attributeRecord(0x0000, 0x10, new byte[] {(byte) (on ? 1 : 0)})));
    }

    /** Queues a Hue color-temperature report ({@code uint16} mireds LE). */
    public void reportColorTemperatureMireds(int mireds) {
        queuedCallbacks.add(report(HUE, 0x0300,
                attributeRecord(0x0007, 0x21, new byte[] {
                        (byte) (mireds & 0xFF), (byte) ((mireds >> 8) & 0xFF)})));
    }

    /** Queues a Hue CurrentLevel report ({@code uint8} 0-254 — the SD-2 canonical domain). */
    public void reportBrightnessLevel(int level) {
        queuedCallbacks.add(report(HUE, 0x0008,
                attributeRecord(0x0000, 0x20, new byte[] {(byte) level})));
    }

    /**
     * Queues the metering-plug fixture's Device_annce (ENERGY-READ). The
     * scripted NCP then answers its interview walk with the Gen4's recorded
     * signature (EP1, 0x010A, the eight input clusters) and — for the adoption
     * drive an accept-listed adapter runs — its binds, its two formatting
     * reads (the {@code GEN4_FIXTURE_*} values), its Configure Reporting and
     * its read-backs, so the REAL path reads, caches and scales.
     */
    public void announceGen4() {
        announce(GEN4_IEEE);
    }

    /**
     * Queues one Report Attributes frame from a scripted device — the generic
     * injector beside the per-device helpers above. Each attribute is encoded
     * at its ZCL wire type (the rig's own table of the standard attributes it
     * knows: a {@code Boolean} for the bool type, a {@code Number} for the
     * integer types, little-endian at the type's width); an attribute the rig
     * has no wire type for is refused rather than guessed at.
     *
     * @param ieee a scripted device
     * @param endpoint the reporting endpoint
     * @param clusterId the reported cluster
     * @param attributes attribute id → value, encoded in iteration order
     * @throws IllegalArgumentException for an unscripted device or an attribute
     *         with no scripted wire type
     */
    public void reportAttributes(long ieee, int endpoint, int clusterId,
            Map<Integer, Object> attributes) {
        ScriptedDevice device = deviceFor(ieee);
        ByteArrayOutputStream records = new ByteArrayOutputStream();
        for (Map.Entry<Integer, Object> attribute : attributes.entrySet()) {
            int dataType = wireType(clusterId, attribute.getKey());
            byte[] record = attributeRecord(attribute.getKey(), dataType,
                    wireValue(dataType, attribute.getValue()));
            records.write(record, 0, record.length);
        }
        queuedCallbacks.add(report(device, endpoint, clusterId,
                records.toByteArray()));
    }

    /**
     * Silences a scripted device: the NCP still SRSP-accepts unicasts addressed
     * to it (the radio path is healthy) but the device itself never replies —
     * no ZDO responses, no read responses, no availability-ping answer. The
     * dead-device simulation for the WU-AVAIL-SEED boot-truth legs; a silenced
     * device's cluster-specific unicasts are not captured in
     * {@link #sentZclFrames()} (a dead device receives nothing).
     */
    public void silence(long ieee) {
        silencedDevices.add(ieee);
    }

    /**
     * Pumps the queued callback frames through the REAL wire path (a keepalive nop
     * whose response carries them; they park in the protocol's callback queue) and
     * runs one §G cycle (drain → route → interviews → cache flush check).
     */
    public void deliverAndCycle() {
        adapter().coordinatorProtocol().ping();
        adapter().runCycleOnce();
    }

    /** Runs one §G cycle without pumping (announce-then-interview turns, etc.). */
    public void cycle() {
        adapter().runCycleOnce();
    }

    /** Adopts a proposed device (the user-acceptance stand-in until the REST path). */
    public Map<Integer, EntityId> adopt(long ieee) {
        return adapter().adoptionSlice().adopt(new IEEEAddress(ieee)).entityIds();
    }

    /** Every cluster-specific ZCL unicast the scripted NCP has received, in order. */
    public List<SentZcl> sentZclFrames() {
        return List.copyOf(sentZcl);
    }

    /** True once the adapter's EZSP session negotiated (run() reached the park). */
    public boolean sessionStarted() {
        ZigbeeIntegrationAdapter adapter = factory.lastCreated();
        return adapter != null && adapter.coordinatorProtocol() != null
                && adapter.coordinatorProtocol().negotiatedVersion() > 0;
    }

    private ZigbeeIntegrationAdapter adapter() {
        ZigbeeIntegrationAdapter adapter = factory.lastCreated();
        if (adapter == null) {
            throw new IllegalStateException(
                    "the supervisor has not created the zigbee adapter yet");
        }
        return adapter;
    }

    private static ScriptedDevice deviceFor(long ieee) {
        if (ieee == HUE_IEEE) {
            return HUE;
        }
        if (ieee == SNZB_IEEE) {
            return SNZB;
        }
        if (ieee == GEN4_IEEE) {
            return GEN4;
        }
        throw new IllegalArgumentException("no scripted device for IEEE 0x"
                + Long.toHexString(ieee));
    }

    private static ScriptedDevice deviceForNwk(int nwk) {
        if (nwk == GEN4_NWK) {
            return GEN4;
        }
        return nwk == HUE_NWK ? HUE : SNZB;
    }

    private int nextTsn() {
        reportTsn = (reportTsn + 1) & 0xFF;
        return reportTsn;
    }

    // ── the scripted NCP ─────────────────────────────────────────────────────

    private List<byte[]> handleCommand(byte[] command) {
        if (isLegacyVersion(command)) {
            // v13 negotiation response: protocolVersion 13, stackType 2, stack 0x7430.
            return List.of(new byte[] {
                    command[0], (byte) 0x80, 0x00, 13, 0x02, 0x30, 0x74});
        }
        int seq = command[0] & 0xFF;
        int frameId = frameIdOf(command);
        if (frameId == EzspCoordinatorProtocol.FRAME_NOP) {
            List<byte[]> frames = new ArrayList<>();
            byte[] queued;
            while ((queued = queuedCallbacks.poll()) != null) {
                frames.add(queued);
            }
            frames.add(extendedResponse(seq, frameId, new byte[0]));
            return frames;
        }
        if (frameId == EzspCoordinatorProtocol.FRAME_LOOKUP_NODE_ID_BY_EUI64) {
            byte[] parameters = extendedParameters(command);
            long ieee = 0;
            for (int i = 0; i < 8; i++) {
                ieee |= (long) (parameters[i] & 0xFF) << (8 * i);
            }
            int nwk = deviceFor(ieee).nwk();
            return List.of(extendedResponse(seq, frameId,
                    new byte[] {(byte) (nwk & 0xFF), (byte) ((nwk >> 8) & 0xFF)}));
        }
        if (frameId == EzspCoordinatorProtocol.FRAME_SEND_UNICAST) {
            return handleUnicast(seq, extendedParameters(command));
        }
        return List.of();
    }

    private List<byte[]> handleUnicast(int seq, byte[] parameters) {
        int nwk = (parameters[1] & 0xFF) | ((parameters[2] & 0xFF) << 8);
        int profile = (parameters[3] & 0xFF) | ((parameters[4] & 0xFF) << 8);
        int cluster = (parameters[5] & 0xFF) | ((parameters[6] & 0xFF) << 8);
        int destinationEndpoint = parameters[8] & 0xFF;
        byte[] message = new byte[parameters[15] & 0xFF];
        System.arraycopy(parameters, 16, message, 0, message.length);

        List<byte[]> frames = new ArrayList<>();
        frames.add(extendedResponse(seq, EzspCoordinatorProtocol.FRAME_SEND_UNICAST,
                new byte[] {0x00, parameters[13]}));    // EMBER_SUCCESS + echoed tag

        ScriptedDevice device = deviceForNwk(nwk);
        if (silencedDevices.contains(device.ieee())) {
            // A dead device: the radio accepted the unicast, nothing answers.
            return frames;
        }
        if (profile == EzspCoordinatorProtocol.ZDO_PROFILE_ID) {
            int tsn = message[0] & 0xFF;
            if (device == GEN4
                    && cluster == EzspReportingOps.ZDO_CLUSTER_BIND_REQ) {
                // The metering fixture accepts every bind (Bind_rsp SUCCESS);
                // HUE/SNZB keep their un-widened silence (the M9.4-RPT gotcha).
                frames.add(incomingMessage(device,
                        EzspCoordinatorProtocol.ZDO_PROFILE_ID,
                        EzspReportingOps.ZDO_CLUSTER_BIND_RSP, 0,
                        new byte[] {(byte) tsn,
                            (byte) EzspReportingOps.ZDO_STATUS_SUCCESS}));
                return frames;
            }
            byte[] reply = zdoReply(device, cluster, tsn);
            if (reply != null) {
                frames.add(incomingMessage(device,
                        EzspCoordinatorProtocol.ZDO_PROFILE_ID, cluster | 0x8000, 0,
                        reply));
            }
            return frames;
        }
        boolean clusterSpecific = (message[0] & 0x03) == 0x01;
        if (clusterSpecific) {
            byte[] payload = new byte[message.length - 3];
            System.arraycopy(message, 3, payload, 0, payload.length);
            sentZcl.add(new SentZcl(nwk, destinationEndpoint, cluster,
                    message[2] & 0xFF, payload));
            return frames;
        }
        if (cluster == 0x0000) {                        // interview Basic read
            int tsn = message[1] & 0xFF;
            frames.add(incomingMessage(device, EzspCoordinatorProtocol.HA_PROFILE_ID,
                    0x0000, device.endpoint(), basicReply(device, tsn)));
        } else if (device == GEN4) {
            byte[] reply = gen4GlobalReply(cluster, message);
            if (reply != null) {
                frames.add(incomingMessage(device,
                        EzspCoordinatorProtocol.HA_PROFILE_ID, cluster,
                        device.endpoint(), reply));
            }
        }
        return frames;
    }

    /**
     * The metering fixture's answers to the adoption drive's ZCL GLOBAL
     * exchanges (ENERGY-READ): the two formatting reads (the
     * {@code GEN4_FIXTURE_*} values, each attribute at its ZCL wire type),
     * Configure Reporting (stored device-side, SUCCESS) and Read Reporting
     * Configuration (echoed from that store — VERIFIED by measurement). The
     * store is long-typed: the uint48 change field is six bytes.
     */
    private byte[] gen4GlobalReply(int cluster, byte[] message) {
        int tsn = message[1] & 0xFF;
        int commandId = message[2] & 0xFF;
        if (commandId == ZclCodec.COMMAND_READ_ATTRIBUTES) {
            ByteArrayOutputStream reply = new ByteArrayOutputStream();
            reply.write(0x18);                          // global, server-to-client
            reply.write(tsn);
            reply.write(ZclCodec.COMMAND_READ_ATTRIBUTES_RESPONSE);
            for (int i = 3; i + 1 < message.length; i += 2) {
                int attribute = (message[i] & 0xFF) | ((message[i + 1] & 0xFF) << 8);
                long[] typed = gen4Formatting(cluster, attribute);
                reply.write(message[i]);
                reply.write(message[i + 1]);
                if (typed == null) {
                    reply.write(EzspReportingOps.ZCL_STATUS_UNSUPPORTED_ATTRIBUTE);
                    continue;
                }
                reply.write(EzspReportingOps.ZCL_STATUS_SUCCESS);
                reply.write((int) typed[0]);
                for (int b = 0; b < typed[1]; b++) {
                    reply.write((int) ((typed[2] >> (8 * b)) & 0xFF));
                }
            }
            return reply.toByteArray();
        }
        if (commandId == EzspReportingOps.COMMAND_CONFIGURE_REPORTING) {
            int attribute = (message[4] & 0xFF) | ((message[5] & 0xFF) << 8);
            long change = 0;
            for (int i = 11; i < message.length; i++) {
                change |= (long) (message[i] & 0xFF) << (8 * (i - 11));
            }
            gen4ReportingStore.put(((long) cluster << 16) | attribute, new long[] {
                message[6] & 0xFF,
                (message[7] & 0xFF) | ((message[8] & 0xFF) << 8),
                (message[9] & 0xFF) | ((message[10] & 0xFF) << 8),
                change});
            return new byte[] {0x18, (byte) tsn,
                (byte) EzspReportingOps.COMMAND_CONFIGURE_REPORTING_RESPONSE,
                (byte) EzspReportingOps.ZCL_STATUS_SUCCESS};
        }
        if (commandId == EzspReportingOps.COMMAND_READ_REPORTING_CONFIGURATION) {
            int attribute = (message[4] & 0xFF) | ((message[5] & 0xFF) << 8);
            long[] stored = gen4ReportingStore.getOrDefault(
                    ((long) cluster << 16) | attribute, new long[] {0x10, 0, 0, 0});
            int changeWidth = switch ((int) stored[0]) {
                case 0x20 -> 1;
                case 0x21, 0x29 -> 2;
                case 0x25 -> 6;
                default -> 0;
            };
            byte[] reply = new byte[12 + changeWidth];
            reply[0] = 0x18;
            reply[1] = (byte) tsn;
            reply[2] = (byte)
                    EzspReportingOps.COMMAND_READ_REPORTING_CONFIGURATION_RESPONSE;
            reply[3] = (byte) EzspReportingOps.ZCL_STATUS_SUCCESS;
            reply[4] = (byte) EzspReportingOps.REPORTING_DIRECTION_REPORTED;
            reply[5] = message[4];
            reply[6] = message[5];
            reply[7] = (byte) stored[0];
            reply[8] = (byte) (stored[1] & 0xFF);
            reply[9] = (byte) ((stored[1] >> 8) & 0xFF);
            reply[10] = (byte) (stored[2] & 0xFF);
            reply[11] = (byte) ((stored[2] >> 8) & 0xFF);
            for (int i = 0; i < changeWidth; i++) {
                reply[12 + i] = (byte) ((stored[3] >> (8 * i)) & 0xFF);
            }
            return reply;
        }
        return null;
    }

    /** One fixture formatting attribute as {type, width, value}; null = unsupported. */
    private static long[] gen4Formatting(int cluster, int attribute) {
        if (cluster == ElectricalMeasurementHandler.CLUSTER_ID) {
            return switch (attribute) {
                case ElectricalMeasurementHandler.ATTRIBUTE_AC_POWER_MULTIPLIER ->
                        new long[] {0x21, 2, GEN4_FIXTURE_AC_POWER_MULTIPLIER};
                case ElectricalMeasurementHandler.ATTRIBUTE_AC_POWER_DIVISOR ->
                        new long[] {0x21, 2, GEN4_FIXTURE_AC_POWER_DIVISOR};
                case ElectricalMeasurementHandler.ATTRIBUTE_AC_VOLTAGE_MULTIPLIER,
                        ElectricalMeasurementHandler.ATTRIBUTE_AC_CURRENT_MULTIPLIER ->
                        new long[] {0x21, 2, 1};
                case ElectricalMeasurementHandler.ATTRIBUTE_AC_VOLTAGE_DIVISOR ->
                        new long[] {0x21, 2, 10};
                case ElectricalMeasurementHandler.ATTRIBUTE_AC_CURRENT_DIVISOR ->
                        new long[] {0x21, 2, 1000};
                default -> null;
            };
        }
        if (cluster == MeteringHandler.CLUSTER_ID) {
            return switch (attribute) {
                case MeteringHandler.ATTRIBUTE_UNIT_OF_MEASURE ->
                        new long[] {0x30, 1, MeteringFormatting.UNIT_KILOWATT_HOURS};
                case MeteringHandler.ATTRIBUTE_MULTIPLIER ->
                        new long[] {0x22, 3, GEN4_FIXTURE_SUMMATION_MULTIPLIER};
                case MeteringHandler.ATTRIBUTE_DIVISOR ->
                        new long[] {0x22, 3, GEN4_FIXTURE_SUMMATION_DIVISOR};
                case MeteringHandler.ATTRIBUTE_SUMMATION_FORMATTING ->
                        new long[] {0x18, 1, 0x00};
                default -> null;
            };
        }
        return null;
    }

    private static byte[] zdoReply(ScriptedDevice device, int cluster, int tsn) {
        int nwkLo = device.nwk() & 0xFF;
        int nwkHi = (device.nwk() >> 8) & 0xFF;
        return switch (cluster) {
            case ZdoCodec.CLUSTER_NODE_DESC_REQ -> new byte[] {
                    (byte) tsn, 0x00, (byte) nwkLo, (byte) nwkHi,
                    (byte) device.nodeType(), 0x40, (byte) device.macCapability(),
                    // manufacturer code LE + max buffer + 7 reserved bytes (the
                    // metering fixture claims no code: 0x0000, unread).
                    (byte) (device == HUE ? 0x0B : device == GEN4 ? 0x00 : 0x86),
                    (byte) (device == HUE ? 0x10 : device == GEN4 ? 0x00 : 0x12),
                    82, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
            case ZdoCodec.CLUSTER_ACTIVE_EP_REQ -> device == HUE
                    ? new byte[] {(byte) tsn, 0x00, (byte) nwkLo, (byte) nwkHi,
                            0x02, 0x0B, (byte) 0xF2}    // EP 11 + Green Power 242
                    : new byte[] {(byte) tsn, 0x00, (byte) nwkLo, (byte) nwkHi,
                            0x01, 0x01};
            case ZdoCodec.CLUSTER_SIMPLE_DESC_REQ -> simpleDescriptor(device, tsn);
            default -> null;
        };
    }

    private static byte[] simpleDescriptor(ScriptedDevice device, int tsn) {
        int length = 1 + 2 + 2 + 1 + 1 + device.inClusters().length * 2
                + 1 + device.outClusters().length * 2;
        byte[] reply = new byte[5 + length];
        int i = 0;
        reply[i++] = (byte) tsn;
        reply[i++] = 0x00;
        reply[i++] = (byte) (device.nwk() & 0xFF);
        reply[i++] = (byte) ((device.nwk() >> 8) & 0xFF);
        reply[i++] = (byte) length;
        reply[i++] = (byte) device.endpoint();
        reply[i++] = 0x04;                              // HA profile 0x0104 LE
        reply[i++] = 0x01;
        reply[i++] = (byte) (device.deviceType() & 0xFF);
        reply[i++] = (byte) ((device.deviceType() >> 8) & 0xFF);
        reply[i++] = 0x01;                              // application version
        reply[i++] = (byte) device.inClusters().length;
        for (int clusterId : device.inClusters()) {
            reply[i++] = (byte) (clusterId & 0xFF);
            reply[i++] = (byte) ((clusterId >> 8) & 0xFF);
        }
        reply[i++] = (byte) device.outClusters().length;
        for (int clusterId : device.outClusters()) {
            reply[i++] = (byte) (clusterId & 0xFF);
            reply[i++] = (byte) ((clusterId >> 8) & 0xFF);
        }
        return reply;
    }

    private static byte[] basicReply(ScriptedDevice device, int tsn) {
        String build = "0x01000D08";
        byte[] reply = new byte[3
                + 5 + device.manufacturer().length()
                + 5 + device.model().length()
                + 5
                + 5 + build.length()];
        int i = 0;
        reply[i++] = 0x18;                              // global, server-to-client
        reply[i++] = (byte) tsn;
        reply[i++] = 0x01;                              // Read Attributes Response
        i = stringRecord(reply, i, 0x0004, device.manufacturer());
        i = stringRecord(reply, i, 0x0005, device.model());
        reply[i++] = 0x07;                              // powerSource, enum8
        reply[i++] = 0x00;
        reply[i++] = 0x00;
        reply[i++] = 0x30;
        reply[i++] = (byte) device.powerSource();
        stringRecord(reply, i, 0x4000, build);
        return reply;
    }

    private static int stringRecord(byte[] buffer, int offset, int attributeId,
            String value) {
        buffer[offset++] = (byte) (attributeId & 0xFF);
        buffer[offset++] = (byte) ((attributeId >> 8) & 0xFF);
        buffer[offset++] = 0x00;                        // SUCCESS
        buffer[offset++] = 0x42;                        // character string
        buffer[offset++] = (byte) value.length();
        for (char c : value.toCharArray()) {
            buffer[offset++] = (byte) c;
        }
        return offset;
    }

    private byte[] report(ScriptedDevice device, int cluster, byte[] records) {
        return report(device, device.endpoint(), cluster, records);
    }

    private byte[] report(ScriptedDevice device, int endpoint, int cluster,
            byte[] records) {
        byte[] message = new byte[3 + records.length];
        message[0] = 0x18;                              // global, server-to-client
        message[1] = (byte) nextTsn();                  // distinct TSNs — dedup discipline
        message[2] = 0x0A;                              // Report Attributes
        System.arraycopy(records, 0, message, 3, records.length);
        return incomingMessage(device, EzspCoordinatorProtocol.HA_PROFILE_ID, cluster,
                endpoint, message);
    }

    /**
     * The ZCL wire type of a standard attribute the rig can report (the
     * reportAttributes table): bool 0x10, map8 0x18, uint8 0x20, uint16 0x21,
     * uint48 0x25, int16 0x29.
     */
    private static int wireType(int clusterId, int attributeId) {
        int key = (clusterId << 16) | attributeId;
        return switch (key) {
            case 0x0006_0000 -> 0x10;                   // OnOff.OnOff
            case 0x0008_0000 -> 0x20;                   // LevelControl.CurrentLevel
            case 0x0300_0007 -> 0x21;                   // ColorControl.ColorTemperatureMireds
            case 0x0406_0000 -> 0x18;                   // OccupancySensing.Occupancy
            case 0x0402_0000 -> 0x29;                   // TemperatureMeasurement.MeasuredValue
            case 0x0405_0000 -> 0x21;                   // RelativeHumidity.MeasuredValue
            case 0x0B04_050B -> 0x29;                   // ElectricalMeasurement.ActivePower
            case 0x0B04_0505, 0x0B04_0508 -> 0x21;      // RMSVoltage, RMSCurrent
            case 0x0702_0000 -> 0x25;                   // Metering.CurrentSummationDelivered
            default -> throw new IllegalArgumentException(
                    "no scripted wire type for cluster 0x"
                            + Integer.toHexString(clusterId) + " attribute 0x"
                            + Integer.toHexString(attributeId));
        };
    }

    private static byte[] wireValue(int dataType, Object value) {
        if (dataType == 0x10) {
            if (!(value instanceof Boolean flag)) {
                throw new IllegalArgumentException("a bool attribute takes a "
                        + "Boolean, not " + value);
            }
            return new byte[] {(byte) (flag ? 1 : 0)};
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("an integer attribute takes a "
                    + "Number, not " + value);
        }
        int width = switch (dataType) {
            case 0x18, 0x20 -> 1;
            case 0x21, 0x29 -> 2;
            default -> 6;                               // 0x25 uint48
        };
        long raw = number.longValue();
        byte[] encoded = new byte[width];
        for (int i = 0; i < width; i++) {
            encoded[i] = (byte) ((raw >> (8 * i)) & 0xFF);
        }
        return encoded;
    }

    private static byte[] attributeRecord(int attributeId, int dataType, byte[] value) {
        byte[] record = new byte[3 + value.length];
        record[0] = (byte) (attributeId & 0xFF);
        record[1] = (byte) ((attributeId >> 8) & 0xFF);
        record[2] = (byte) dataType;
        System.arraycopy(value, 0, record, 3, value.length);
        return record;
    }

    /** The 0x0045 incomingMessageHandler callback layout (v13 — bench-proven). */
    private static byte[] incomingMessage(ScriptedDevice device, int profile,
            int cluster, int sourceEndpoint, byte[] message) {
        byte[] parameters = new byte[19 + message.length];
        parameters[0] = 0x00;                           // EMBER_INCOMING_UNICAST
        parameters[1] = (byte) (profile & 0xFF);
        parameters[2] = (byte) ((profile >> 8) & 0xFF);
        parameters[3] = (byte) (cluster & 0xFF);
        parameters[4] = (byte) ((cluster >> 8) & 0xFF);
        parameters[5] = (byte) sourceEndpoint;
        parameters[6] = (byte) (sourceEndpoint == 0 ? 0 : 1);
        parameters[12] = (byte) 176;                    // LQI
        parameters[13] = (byte) -56;                    // RSSI
        parameters[14] = (byte) (device.nwk() & 0xFF);
        parameters[15] = (byte) ((device.nwk() >> 8) & 0xFF);
        parameters[16] = (byte) 0xFF;                   // no binding index
        parameters[17] = (byte) 0xFF;
        parameters[18] = (byte) message.length;
        System.arraycopy(message, 0, parameters, 19, message.length);

        byte[] frame = new byte[5 + parameters.length];
        frame[0] = 0x00;
        frame[1] = (byte) 0x90;                         // callback
        frame[2] = 0x01;
        frame[3] = (byte) EzspCoordinatorProtocol.FRAME_INCOMING_MESSAGE_HANDLER;
        frame[4] = 0x00;
        System.arraycopy(parameters, 0, frame, 5, parameters.length);
        return frame;
    }

    private static boolean isLegacyVersion(byte[] command) {
        return command.length == 4 && command[1] == 0x00 && command[2] == 0x00;
    }

    private static int frameIdOf(byte[] extendedCommand) {
        return (extendedCommand[3] & 0xFF) | ((extendedCommand[4] & 0xFF) << 8);
    }

    private static byte[] extendedParameters(byte[] extendedCommand) {
        byte[] parameters = new byte[extendedCommand.length - 5];
        System.arraycopy(extendedCommand, 5, parameters, 0, parameters.length);
        return parameters;
    }

    private static byte[] extendedResponse(int seq, int frameId, byte[] parameters) {
        byte[] frame = new byte[5 + parameters.length];
        frame[0] = (byte) seq;
        frame[1] = (byte) 0x80;
        frame[2] = 0x01;
        frame[3] = (byte) (frameId & 0xFF);
        frame[4] = (byte) ((frameId >> 8) & 0xFF);
        System.arraycopy(parameters, 0, frame, 5, parameters.length);
        return frame;
    }
}
