/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.integration.PermanentIntegrationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Coordinator transport auto-detection — the Doc 08 §3.3 five-step probe sequence,
 * implemented exactly:
 * <ol>
 *   <li>flush the receive buffer;</li>
 *   <li>send a ZNP SYS_PING SREQ, wait ≤ 4 s for an SRSP;</li>
 *   <li>no response → flush again, settle 200 ms;</li>
 *   <li>send an ASH RST (Cancel-prefixed), wait ≤ 4 s for RSTACK;</li>
 *   <li>an ERROR frame instead → second RST, wait ≤ 4 s.</li>
 * </ol>
 * Total budget 10 s. Exhaustion logs a structured ERROR
 * ({@code zigbee.auto_detect_failed}, port path + bytes received) and throws
 * {@link PermanentIntegrationException} with the (a)/(b)/(c) user guidance.
 *
 * <p>ZNP exists here as probe-encode ONLY (D-M92-2, DP-C): one SYS_PING SREQ plus FCS
 * validation of a possible SRSP — the probe answers "is this ZNP?", nothing more. The
 * full {@code ZnpTransport} is Wave-2.
 *
 * <p>An explicit {@code adapter_type} override bypasses detection entirely; M9.2
 * takes it as a method PARAMETER — the {@code integrations.zigbee.adapter_type}
 * config key is bound by the adapter at M9.3/M9.4 (W10: no config reads here).
 *
 * <p>Deterministic under a fake channel and fixed clock: every timeout path is
 * testable without real waits.
 *
 * <p>Thread-safe: stateless static methods.
 *
 * @see SerialByteChannel
 * @see PortLocator
 */
final class TransportProbe {

    /** The probe verdict: which protocol substrate the coordinator speaks. */
    enum Kind {
        /** TI Z-Stack ZNP (UNPI framing) — full transport is Wave-2 (DP-C). */
        ZNP,
        /** Silicon Labs EZSP over ASH. */
        EZSP
    }

    /** Per-step response wait (Doc 08 §3.3 steps 2/4/5). */
    static final long STEP_TIMEOUT_MILLIS = 4000;
    /** Line-settle wait after the ZNP probe (Doc 08 §3.3 step 3). */
    static final long SETTLE_MILLIS = 200;
    /** Total probe budget (Doc 08 §3.3). */
    static final long TOTAL_BUDGET_MILLIS = 10_000;

    /** ZNP SYS_PING SREQ wire bytes: FE 00 21 01 20 (FCS = 0x00^0x21^0x01 = 0x20). */
    static final byte[] ZNP_SYS_PING_SREQ = {
        (byte) 0xFE, 0x00, 0x21, 0x01, 0x20
    };

    private static final Logger log = LoggerFactory.getLogger(TransportProbe.class);
    private static final int ZNP_SOF = 0xFE;
    private static final int ZNP_TYPE_MASK = 0xE0;
    private static final int ZNP_TYPE_SRSP = 0x60;
    private static final int READ_BUFFER_SIZE = 256;
    private static final int DIAGNOSTIC_BYTE_LIMIT = 64;

    private TransportProbe() {
    }

    /**
     * Auto-detects the coordinator transport on {@code channel}.
     *
     * @param channel the byte channel (opened), never {@code null}
     * @param portPath the port path for diagnostics, never {@code null}
     * @param clock the time source, never {@code null}
     * @return the detected transport kind
     * @throws PermanentIntegrationException if the probe budget is exhausted with no
     *                                       valid response
     */
    static Kind detect(SerialByteChannel channel, String portPath, Clock clock)
            throws PermanentIntegrationException {
        return detect(channel, portPath, clock, null);
    }

    /**
     * Auto-detects the coordinator transport, or returns {@code adapterTypeOverride}
     * immediately with zero probing when it is non-null (the §3.3 manual override).
     *
     * @param channel the byte channel (opened), never {@code null}
     * @param portPath the port path for diagnostics, never {@code null}
     * @param clock the time source, never {@code null}
     * @param adapterTypeOverride the explicit transport kind, or {@code null} for
     *                            auto-detection
     * @return the detected or overridden transport kind
     * @throws PermanentIntegrationException if the probe budget is exhausted with no
     *                                       valid response
     */
    static Kind detect(SerialByteChannel channel, String portPath, Clock clock,
            Kind adapterTypeOverride) throws PermanentIntegrationException {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(portPath, "portPath");
        Objects.requireNonNull(clock, "clock");
        if (adapterTypeOverride != null) {
            log.info("zigbee.transport_override: adapter_type={} on {}; "
                    + "auto-detection skipped", adapterTypeOverride, portPath);
            return adapterTypeOverride;
        }

        Instant start = clock.instant();
        Instant budgetDeadline = start.plusMillis(TOTAL_BUDGET_MILLIS);
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        byte[] buffer = new byte[READ_BUFFER_SIZE];

        // Step 1: flush stale bytes from a previous session or power-cycle.
        channel.flushInput();

        // Step 2: ZNP SYS_PING SREQ, wait for a valid SRSP.
        channel.write(ZNP_SYS_PING_SREQ);
        if (awaitZnpSrsp(channel, clock, stepDeadline(clock, budgetDeadline), buffer,
                received)) {
            log.info("zigbee.transport_detected: kind=ZNP port={}", portPath);
            return Kind.ZNP;
        }

        // Step 3: flush again, settle 200 ms (a bounded drain-read acts as the wait).
        channel.flushInput();
        drainFor(channel, clock, SETTLE_MILLIS, buffer);

        // Step 4: ASH RST (Cancel-prefixed), wait for RSTACK.
        AshResult first = awaitRstack(channel, clock,
                stepDeadline(clock, budgetDeadline), buffer, received);
        if (first == AshResult.RSTACK) {
            log.info("zigbee.transport_detected: kind=EZSP port={}", portPath);
            return Kind.EZSP;
        }
        if (first == AshResult.ERROR) {
            // Step 5: the ASH state machine was confused by the ZNP probe bytes —
            // one retry per §3.3.
            AshResult second = awaitRstack(channel, clock,
                    stepDeadline(clock, budgetDeadline), buffer, received);
            if (second == AshResult.RSTACK) {
                log.info("zigbee.transport_detected: kind=EZSP port={}", portPath);
                return Kind.EZSP;
            }
        }

        byte[] diagnostic = received.toByteArray();
        log.error("zigbee.auto_detect_failed: port={} bytesReceived={} first={}",
                portPath, diagnostic.length,
                toHex(diagnostic, DIAGNOSTIC_BYTE_LIMIT));
        throw new PermanentIntegrationException(
                "zigbee.auto_detect_failed",
                String.format(
                        "Zigbee coordinator auto-detection failed on serial port %s: "
                                + "no ZNP or EZSP response within %d seconds. Verify "
                                + "the coordinator is connected and powered; check "
                                + "serial port permissions; if the issue persists, "
                                + "set integrations.zigbee.adapter_type to znp or "
                                + "ezsp to bypass auto-detection.",
                        portPath, TOTAL_BUDGET_MILLIS / 1000));
    }

    private enum AshResult {
        RSTACK, ERROR, TIMEOUT
    }

    private static Instant stepDeadline(Clock clock, Instant budgetDeadline) {
        Instant stepLimit = clock.instant().plusMillis(STEP_TIMEOUT_MILLIS);
        return stepLimit.isBefore(budgetDeadline) ? stepLimit : budgetDeadline;
    }

    /**
     * Sends the Cancel-prefixed RST and pumps until RSTACK, ERROR, or the deadline.
     */
    private static AshResult awaitRstack(SerialByteChannel channel, Clock clock,
            Instant deadline, byte[] buffer, ByteArrayOutputStream received) {
        byte[] rst = AshCodec.emit(new AshFrame.Rst());
        byte[] cancelPlusRst = new byte[rst.length + 1];
        cancelPlusRst[0] = (byte) AshCodec.CANCEL;
        System.arraycopy(rst, 0, cancelPlusRst, 1, rst.length);
        channel.write(cancelPlusRst);

        AshFrameAccumulator accumulator = new AshFrameAccumulator();
        while (true) {
            long remaining = Duration.between(clock.instant(), deadline).toMillis();
            if (remaining <= 0) {
                return AshResult.TIMEOUT;
            }
            int n = channel.read(buffer, remaining);
            if (n < 0) {
                return AshResult.TIMEOUT;
            }
            if (n == 0) {
                continue;
            }
            received.write(buffer, 0, n);
            for (byte[] body : accumulator.accept(buffer, n)) {
                if (AshCodec.parse(body) instanceof AshCodec.ParseResult.Parsed p) {
                    if (p.frame() instanceof AshFrame.RstAck) {
                        return AshResult.RSTACK;
                    }
                    if (p.frame() instanceof AshFrame.Error) {
                        return AshResult.ERROR;
                    }
                }
                // Rejected or unrelated frames: ignored during probing.
            }
        }
    }

    /**
     * Pumps until a whole, FCS-valid ZNP SRSP is observed or the deadline passes.
     * Contents are otherwise ignored — the probe answers "is this ZNP?" (D-M92-2).
     */
    private static boolean awaitZnpSrsp(SerialByteChannel channel, Clock clock,
            Instant deadline, byte[] buffer, ByteArrayOutputStream received) {
        ByteArrayOutputStream accumulated = new ByteArrayOutputStream();
        while (true) {
            long remaining = Duration.between(clock.instant(), deadline).toMillis();
            if (remaining <= 0) {
                return false;
            }
            int n = channel.read(buffer, remaining);
            if (n < 0) {
                return false;
            }
            if (n == 0) {
                continue;
            }
            received.write(buffer, 0, n);
            accumulated.write(buffer, 0, n);
            if (containsValidSrsp(accumulated.toByteArray())) {
                return true;
            }
        }
    }

    /**
     * Scans for {@code SOF | len | cmd0 | cmd1 | data[len] | fcs} with SRSP type bits
     * and a valid FCS (XOR over len..data).
     */
    private static boolean containsValidSrsp(byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            if ((bytes[i] & 0xFF) != ZNP_SOF) {
                continue;
            }
            if (i + 4 >= bytes.length) {
                return false; // header incomplete; keep accumulating
            }
            int length = bytes[i + 1] & 0xFF;
            int end = i + 4 + length; // index of the FCS byte
            if (end >= bytes.length) {
                continue; // frame incomplete from this SOF; try a later SOF
            }
            int cmd0 = bytes[i + 2] & 0xFF;
            if ((cmd0 & ZNP_TYPE_MASK) != ZNP_TYPE_SRSP) {
                continue;
            }
            int fcs = 0;
            for (int j = i + 1; j < end; j++) {
                fcs ^= bytes[j] & 0xFF;
            }
            if (fcs == (bytes[end] & 0xFF)) {
                return true;
            }
        }
        return false;
    }

    /** Discards inbound bytes for {@code waitMillis} — the §3.3 step-3 settle. */
    private static void drainFor(SerialByteChannel channel, Clock clock,
            long waitMillis, byte[] buffer) {
        Instant deadline = clock.instant().plusMillis(waitMillis);
        while (true) {
            long remaining = Duration.between(clock.instant(), deadline).toMillis();
            if (remaining <= 0) {
                return;
            }
            if (channel.read(buffer, remaining) < 0) {
                return;
            }
        }
    }

    private static String toHex(byte[] bytes, int limit) {
        int n = Math.min(bytes.length, limit);
        StringBuilder sb = new StringBuilder(n * 3);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02X", bytes[i]));
        }
        if (bytes.length > limit) {
            sb.append(" …");
        }
        return sb.toString();
    }
}
