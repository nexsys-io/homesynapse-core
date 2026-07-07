/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;

/**
 * The EZSP binding of the frozen {@link ReportingOps} seam (M9.4-RPT §1 —
 * Doc 08 §3.7/§3.12): ZDO Bind_req, ZCL global Configure Reporting, the
 * Read Reporting Configuration read-back, and the IAS CIE write, all riding
 * {@link EzspCoordinatorProtocol}'s single-in-flight pipeline synchronously on
 * the calling (ingestion) thread — no new threads, no executors.
 *
 * <p><strong>Typed results only:</strong> no exception crosses this seam. A
 * command-level timeout ({@link EzspCommandTimeoutException}) or an
 * unanswerable device converts to the method's typed failure — a TIMEOUT is a
 * RESULT, never a throw (the {@link ReportingOps} contract; the configurator's
 * degrade ladder branches on it).
 *
 * <p>Network addresses resolve cache-first (the injected resolver — fresh from
 * {@code recordInterview} at every drive site) with
 * {@link EzspCoordinatorProtocol#lookupNetworkAddress} as the fallback, the
 * command handler's F-6 identity-join shape.
 *
 * <p>Thread-safe: stateless over the protocol's own pipeline lock.
 */
final class EzspReportingOps implements ReportingOps {

    // ── BENCH-VERIFY constants (M9.4-RPT §1 — the 0x0019/0x90 model) ────────
    // ZDO cluster ids, ZCL global command ids, status codes, and the wire
    // layouts below are ZDP/ZCL8-derived and synthetic-tested until silicon:
    // a correction fed back from the bench is a one-constant edit fixing code
    // and tests together.

    /** ZDP Bind_req (ZDO cluster 0x0021; response = request | 0x8000). */
    static final int ZDO_CLUSTER_BIND_REQ = 0x0021;
    /** ZDP Bind_rsp (0x8021): [tsn][status]. */
    static final int ZDO_CLUSTER_BIND_RSP = 0x8021;
    /** ZDP status SUCCESS. */
    static final int ZDO_STATUS_SUCCESS = 0x00;
    /** Bind_req DstAddrMode 0x03: 64-bit destination address + endpoint. */
    static final int BIND_DST_ADDRESS_MODE_UNICAST = 0x03;
    /** The coordinator's application endpoint (the adapter-wide EP-1 rule). */
    static final int COORDINATOR_ENDPOINT = 1;

    /** ZCL global Configure Reporting (0x06). */
    static final int COMMAND_CONFIGURE_REPORTING = 0x06;
    /** ZCL global Configure Reporting Response (0x07). */
    static final int COMMAND_CONFIGURE_REPORTING_RESPONSE = 0x07;
    /** ZCL global Read Reporting Configuration (0x08). */
    static final int COMMAND_READ_REPORTING_CONFIGURATION = 0x08;
    /** ZCL global Read Reporting Configuration Response (0x09). */
    static final int COMMAND_READ_REPORTING_CONFIGURATION_RESPONSE = 0x09;
    /** ZCL global Write Attributes (0x02) — the IAS CIE write vehicle. */
    static final int COMMAND_WRITE_ATTRIBUTES = 0x02;
    /** ZCL global Write Attributes Response (0x04). */
    static final int COMMAND_WRITE_ATTRIBUTES_RESPONSE = 0x04;

    /** ZCL status SUCCESS. */
    static final int ZCL_STATUS_SUCCESS = 0x00;
    /** ZCL status 0x86: the attribute is not implemented. */
    static final int ZCL_STATUS_UNSUPPORTED_ATTRIBUTE = 0x86;
    /** ZCL status 0x8C: the attribute exists but cannot be reported. */
    static final int ZCL_STATUS_UNREPORTABLE_ATTRIBUTE = 0x8C;

    /** ZCL reporting-record direction 0x00: the device REPORTS the attribute. */
    static final int REPORTING_DIRECTION_REPORTED = 0x00;
    /** IAS Zone {@code IAS_CIE_Address} (ZCL8 §8.2.2.2.2). */
    static final int ATTRIBUTE_IAS_CIE_ADDRESS = 0x0010;
    /** ZCL data type 0xF0: IEEE address (EUI64, 8 bytes LE). */
    static final int DATA_TYPE_IEEE_ADDRESS = 0xF0;

    /**
     * The per-exchange deadline (chosen constant — the sleepy-device write
     * timeout class, dossier §E): a sleepy device that parks mid-configure
     * times out HERE and the configurator records the SLEEPY posture; the
     * drive runs in the post-announce awake window, so a healthy device
     * answers well inside it. BENCH-VERIFY the width against the SNZB's
     * measured awake window.
     */
    static final long REPORTING_EXCHANGE_TIMEOUT_MILLIS = 5_000;

    private static final Logger log =
            LoggerFactory.getLogger(EzspReportingOps.class);

    private final EzspCoordinatorProtocol protocol;
    private final Function<IEEEAddress, OptionalInt> cachedNetworkAddress;

    /**
     * Creates the binding.
     *
     * @param protocol the EZSP pipeline, never {@code null}
     * @param cachedNetworkAddress the cache-first address resolver (empty on a
     *        miss/unknown-sentinel — the protocol lookup is the fallback),
     *        never {@code null}
     */
    EzspReportingOps(EzspCoordinatorProtocol protocol,
            Function<IEEEAddress, OptionalInt> cachedNetworkAddress) {
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.cachedNetworkAddress = Objects.requireNonNull(cachedNetworkAddress,
                "cachedNetworkAddress");
    }

    @Override
    public boolean bind(IEEEAddress device, int endpoint, int clusterId) {
        OptionalInt networkAddress = resolveNetworkAddress(device);
        if (networkAddress.isEmpty()) {
            return false;
        }
        try {
            long coordinatorEui64 = protocol.coordinatorEui64();
            Optional<byte[]> response = protocol.zdoUnicastExchange(
                    networkAddress.getAsInt(), ZDO_CLUSTER_BIND_REQ,
                    ZDO_CLUSTER_BIND_RSP,
                    tsn -> encodeBindRequest(tsn, device, endpoint, clusterId,
                            coordinatorEui64),
                    REPORTING_EXCHANGE_TIMEOUT_MILLIS);
            return response
                    .filter(message -> message.length >= 2
                            && (message[1] & 0xFF) == ZDO_STATUS_SUCCESS)
                    .isPresent();
        } catch (EzspCommandTimeoutException | EzspFormatException e) {
            log.debug("zigbee.reporting_exchange_failed: op=bind device={} "
                    + "cluster=0x{}: {}", device,
                    Integer.toHexString(clusterId), e.getMessage());
            return false;
        }
    }

    @Override
    public ConfigureResult configureReporting(IEEEAddress device, int endpoint,
            int clusterId, int attributeId, int dataType, int minInterval,
            int maxInterval, int reportableChange) {
        OptionalInt networkAddress = resolveNetworkAddress(device);
        if (networkAddress.isEmpty()) {
            return ConfigureResult.TIMEOUT;
        }
        int changeWidth = analogChangeWidth(dataType);
        byte[] payload = new byte[8 + changeWidth];
        payload[0] = (byte) REPORTING_DIRECTION_REPORTED;
        payload[1] = (byte) (attributeId & 0xFF);
        payload[2] = (byte) ((attributeId >> 8) & 0xFF);
        payload[3] = (byte) dataType;
        payload[4] = (byte) (minInterval & 0xFF);
        payload[5] = (byte) ((minInterval >> 8) & 0xFF);
        payload[6] = (byte) (maxInterval & 0xFF);
        payload[7] = (byte) ((maxInterval >> 8) & 0xFF);
        for (int i = 0; i < changeWidth; i++) {
            payload[8 + i] = (byte) ((reportableChange >> (8 * i)) & 0xFF);
        }
        Optional<byte[]> response;
        try {
            response = protocol.zclGlobalExchange(networkAddress.getAsInt(),
                    endpoint, clusterId, COMMAND_CONFIGURE_REPORTING, payload,
                    COMMAND_CONFIGURE_REPORTING_RESPONSE,
                    REPORTING_EXCHANGE_TIMEOUT_MILLIS);
        } catch (EzspCommandTimeoutException | EzspFormatException e) {
            log.debug("zigbee.reporting_exchange_failed: op=configure device={} "
                    + "cluster=0x{}: {}", device,
                    Integer.toHexString(clusterId), e.getMessage());
            return ConfigureResult.TIMEOUT;
        }
        if (response.isEmpty()) {
            return ConfigureResult.TIMEOUT;
        }
        // The response is either one bare SUCCESS status (all records accepted)
        // or [status][direction][attrId LE] records; the first status byte
        // classifies either shape.
        OptionalInt status = firstPayloadByte(response.get());
        if (status.isEmpty()) {
            return ConfigureResult.TIMEOUT;
        }
        return switch (status.getAsInt()) {
            case ZCL_STATUS_SUCCESS -> ConfigureResult.SUCCESS;
            case ZCL_STATUS_UNSUPPORTED_ATTRIBUTE ->
                    ConfigureResult.UNSUPPORTED_ATTRIBUTE;
            case ZCL_STATUS_UNREPORTABLE_ATTRIBUTE ->
                    ConfigureResult.UNREPORTABLE_ATTRIBUTE;
            default -> {
                // An unmapped ZCL failure (INVALID_VALUE, INVALID_DATA_TYPE…):
                // the attribute may exist but reports will not flow — the
                // UNREPORTABLE class is the honest degrade rung.
                log.debug("zigbee.reporting_configure_status: device={} "
                        + "cluster=0x{} status=0x{}", device,
                        Integer.toHexString(clusterId),
                        Integer.toHexString(status.getAsInt()));
                yield ConfigureResult.UNREPORTABLE_ATTRIBUTE;
            }
        };
    }

    @Override
    public Optional<ReportingConfigRecord> readReportingConfiguration(
            IEEEAddress device, int endpoint, int clusterId, int attributeId) {
        OptionalInt networkAddress = resolveNetworkAddress(device);
        if (networkAddress.isEmpty()) {
            return Optional.empty();
        }
        byte[] payload = new byte[] {
            (byte) REPORTING_DIRECTION_REPORTED,
            (byte) (attributeId & 0xFF),
            (byte) ((attributeId >> 8) & 0xFF)
        };
        Optional<byte[]> response;
        try {
            response = protocol.zclGlobalExchange(networkAddress.getAsInt(),
                    endpoint, clusterId, COMMAND_READ_REPORTING_CONFIGURATION,
                    payload, COMMAND_READ_REPORTING_CONFIGURATION_RESPONSE,
                    REPORTING_EXCHANGE_TIMEOUT_MILLIS);
        } catch (EzspCommandTimeoutException | EzspFormatException e) {
            log.debug("zigbee.reporting_exchange_failed: op=readback device={} "
                    + "cluster=0x{}: {}", device,
                    Integer.toHexString(clusterId), e.getMessage());
            return Optional.empty();
        }
        return response.flatMap(EzspReportingOps::parseReadbackRecord);
    }

    @Override
    public boolean writeCieAddress(IEEEAddress device, int endpoint) {
        OptionalInt networkAddress = resolveNetworkAddress(device);
        if (networkAddress.isEmpty()) {
            return false;
        }
        try {
            long coordinatorEui64 = protocol.coordinatorEui64();
            byte[] payload = new byte[11];
            payload[0] = (byte) (ATTRIBUTE_IAS_CIE_ADDRESS & 0xFF);
            payload[1] = (byte) ((ATTRIBUTE_IAS_CIE_ADDRESS >> 8) & 0xFF);
            payload[2] = (byte) DATA_TYPE_IEEE_ADDRESS;
            for (int i = 0; i < 8; i++) {
                payload[3 + i] = (byte) ((coordinatorEui64 >> (8 * i)) & 0xFF);
            }
            Optional<byte[]> response = protocol.zclGlobalExchange(
                    networkAddress.getAsInt(), endpoint,
                    IasZoneHandler.CLUSTER_ID, COMMAND_WRITE_ATTRIBUTES, payload,
                    COMMAND_WRITE_ATTRIBUTES_RESPONSE,
                    REPORTING_EXCHANGE_TIMEOUT_MILLIS);
            // A Write Attributes Response is one bare SUCCESS status when every
            // write succeeded, else [status][attrId LE] records.
            if (response.isEmpty()) {
                return false;
            }
            OptionalInt status = firstPayloadByte(response.get());
            return status.isPresent()
                    && status.getAsInt() == ZCL_STATUS_SUCCESS;
        } catch (EzspCommandTimeoutException | EzspFormatException e) {
            log.debug("zigbee.reporting_exchange_failed: op=cie_write device={}: "
                    + "{}", device, e.getMessage());
            return false;
        }
    }

    /**
     * Resolves the current 16-bit network address: the injected cache view
     * first (fresh from {@code recordInterview} at the drive site), then the
     * protocol's {@code lookupNodeIdByEui64}. Resolution failure is a typed
     * empty — never a throw across the seam.
     */
    private OptionalInt resolveNetworkAddress(IEEEAddress device) {
        OptionalInt cached = cachedNetworkAddress.apply(device);
        if (cached.isPresent()) {
            return cached;
        }
        try {
            return OptionalInt.of(protocol.lookupNetworkAddress(device));
        } catch (IllegalStateException | EzspCommandTimeoutException
                | EzspFormatException e) {
            log.debug("zigbee.reporting_address_unresolved: device={}: {}",
                    device, e.getMessage());
            return OptionalInt.empty();
        }
    }

    /**
     * The ZDP Bind_req body (ZDP 2.4.3.2.2): [tsn][SrcAddress EUI64 LE]
     * [SrcEndp][ClusterID LE][DstAddrMode 0x03][DstAddress EUI64 LE][DstEndp] —
     * the binding is created ON the device, targeting the coordinator.
     */
    private static byte[] encodeBindRequest(int tsn, IEEEAddress device,
            int endpoint, int clusterId, long coordinatorEui64) {
        byte[] request = new byte[22];
        int i = 0;
        request[i++] = (byte) tsn;
        long source = device.value();
        for (int b = 0; b < 8; b++) {
            request[i++] = (byte) ((source >> (8 * b)) & 0xFF);
        }
        request[i++] = (byte) endpoint;
        request[i++] = (byte) (clusterId & 0xFF);
        request[i++] = (byte) ((clusterId >> 8) & 0xFF);
        request[i++] = (byte) BIND_DST_ADDRESS_MODE_UNICAST;
        for (int b = 0; b < 8; b++) {
            request[i++] = (byte) ((coordinatorEui64 >> (8 * b)) & 0xFF);
        }
        request[i] = (byte) COORDINATOR_ENDPOINT;
        return request;
    }

    /**
     * Parses one Read Reporting Configuration Response record: [status]
     * [direction][attrId LE] + (on SUCCESS) [dataType][min LE][max LE]
     * [reportable change (analog types only, type-width)]. A non-SUCCESS
     * status or a truncated record is an empty read-back — the configurator's
     * read-back-unavailable rung.
     */
    private static Optional<ReportingConfigRecord> parseReadbackRecord(
            byte[] message) {
        Optional<ZclCodec.ZclHeader> header = ZclCodec.parseHeader(message);
        if (header.isEmpty()) {
            return Optional.empty();
        }
        int offset = header.get().payloadOffset();
        if (message.length < offset + 9) {
            return Optional.empty();
        }
        if ((message[offset] & 0xFF) != ZCL_STATUS_SUCCESS) {
            return Optional.empty();
        }
        int dataType = message[offset + 4] & 0xFF;
        int minInterval = (message[offset + 5] & 0xFF)
                | ((message[offset + 6] & 0xFF) << 8);
        int maxInterval = (message[offset + 7] & 0xFF)
                | ((message[offset + 8] & 0xFF) << 8);
        int changeWidth = analogChangeWidth(dataType);
        if (message.length < offset + 9 + changeWidth) {
            return Optional.empty();
        }
        int reportableChange = 0;
        for (int i = 0; i < changeWidth; i++) {
            reportableChange |= (message[offset + 9 + i] & 0xFF) << (8 * i);
        }
        return Optional.of(new ReportingConfigRecord(minInterval, maxInterval,
                reportableChange));
    }

    /** The first ZCL payload byte after the global header, when present. */
    private static OptionalInt firstPayloadByte(byte[] message) {
        return ZclCodec.parseHeader(message)
                .filter(header -> message.length > header.payloadOffset())
                .map(header -> OptionalInt.of(
                        message[header.payloadOffset()] & 0xFF))
                .orElse(OptionalInt.empty());
    }

    /**
     * The reportable-change field width per ZCL data type: present for ANALOG
     * types only, sized as the type (ZCL8 §2.5.7.1). Covers the §3.7 DEFAULTS
     * vocabulary — uint8 0x20, uint16 0x21, int16 0x29, uint48 0x25; the
     * discrete types (bool 0x10, map8 0x18) carry NO change field. Unknown
     * types conservatively omit it (BENCH-VERIFY on any new row).
     */
    private static int analogChangeWidth(int dataType) {
        return switch (dataType) {
            case 0x20 -> 1;
            case 0x21, 0x29 -> 2;
            case 0x25 -> 6;
            default -> 0;
        };
    }
}
