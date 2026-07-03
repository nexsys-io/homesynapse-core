/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.fazecast.jSerialComm.SerialPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * EZSP coordinator transport: composes the byte channel and the {@link AshSession},
 * decoding ASH DATA payloads into {@link EzspFrame} records (Doc 08 §3.3, §8.1).
 *
 * <p>{@link #open(Object)} keeps the frozen Phase-2 {@code Object} parameter
 * (D-M92-1): the production path validates {@code instanceof SerialPort} and throws
 * {@link IllegalArgumentException} otherwise — no jSerialComm type appears on any
 * exported signature. Opening performs the ASH reset handshake, so a returned
 * {@code open(...)} means DATA may flow.
 *
 * <p>The EZSP decode dialect pivots on version negotiation (W8): until
 * {@link #pinVersion(int)} is called only the legacy version-response format decodes;
 * afterwards the extended v8+ format (with the D-M92-4 width seam) applies. The
 * negotiation frames themselves are always legacy.
 *
 * <p>Port-death detection hooks: a dead port surfaces as
 * {@link TransportFailureException} from send/receive (the read-error signal consumed
 * by {@link PortWatchdog}); {@link #sessionState()} exposes ASH-liveness. Never gate
 * health on {@code isOpen()} alone (W5).
 *
 * <p>Not thread-safe: single-threaded access by the transport thread
 * ({@code IoType.SERIAL}, Doc 05 §3.2), per the {@link CoordinatorTransport} contract.
 *
 * @see AshSession
 * @see EzspCoordinatorProtocol
 */
final class EzspAshTransport implements CoordinatorTransport {

    private static final Logger log = LoggerFactory.getLogger(EzspAshTransport.class);
    /** Poll chunk for the indefinitely-blocking frozen {@link #receiveFrame()}. */
    private static final long RECEIVE_POLL_MILLIS = 1000;

    private final Clock clock;
    private final Function<Object, SerialByteChannel> channelOpener;

    private AshSession session;
    private EzspCodec codec; // null until pinVersion(...) — legacy-only decode (W8)

    /**
     * Creates the production transport. Performs no I/O (INV-RF-03).
     *
     * @param clock the time source, never {@code null}
     */
    EzspAshTransport(Clock clock) {
        this(clock, EzspAshTransport::openProductionChannel);
    }

    /**
     * Creates a transport with an injected channel opener — the test seam (D-M92-3).
     *
     * @param clock the time source, never {@code null}
     * @param channelOpener maps the {@code open(Object)} argument to a byte channel,
     *                      never {@code null}
     */
    EzspAshTransport(Clock clock, Function<Object, SerialByteChannel> channelOpener) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.channelOpener = Objects.requireNonNull(channelOpener, "channelOpener");
    }

    private static SerialByteChannel openProductionChannel(Object serialPort) {
        if (!(serialPort instanceof SerialPort port)) {
            throw new IllegalArgumentException(
                    "serialPort must be a com.fazecast.jSerialComm.SerialPort; got "
                            + serialPort.getClass().getName());
        }
        return JSerialCommByteChannel.open(port);
    }

    @Override
    public void open(Object serialPort) {
        Objects.requireNonNull(serialPort, "serialPort");
        if (session != null) {
            throw new IllegalStateException("transport is already open");
        }
        SerialByteChannel opened = channelOpener.apply(serialPort);
        AshSession fresh = new AshSession(opened, clock);
        try {
            fresh.connect();
        } catch (RuntimeException e) {
            opened.close();
            throw e;
        }
        this.session = fresh;
    }

    @Override
    public void close() {
        if (session == null) {
            return;
        }
        session.close();
        session = null;
        codec = null;
    }

    @Override
    public void sendFrame(byte[] data) {
        Objects.requireNonNull(data, "data");
        requireOpen().send(data);
    }

    @Override
    public ZigbeeFrame receiveFrame() {
        while (true) {
            Optional<Inbound> inbound = receiveDecoded(RECEIVE_POLL_MILLIS);
            if (inbound.isPresent()) {
                return inbound.get().frame();
            }
        }
    }

    /**
     * A decoded inbound EZSP frame with its correlation sequence — the richer
     * package-private companion to the frozen {@link #receiveFrame()} ({@link EzspFrame}
     * carries no sequence; single-in-flight correlation needs it to reject stale
     * responses).
     *
     * @param sequence the echoed sequence byte (opaque for callbacks)
     * @param frame the decoded frame
     */
    record Inbound(int sequence, EzspFrame frame) {
    }

    /**
     * Waits up to {@code maxWaitMillis} for the next decodable EZSP frame. Malformed
     * frames are discarded with a structured WARN (the {@link CoordinatorTransport}
     * contract: invalid frames never surface as exceptions).
     *
     * @param maxWaitMillis the maximum time to wait
     * @return the decoded frame, or empty on timeout
     */
    Optional<Inbound> receiveDecoded(long maxWaitMillis) {
        AshSession current = requireOpen();
        Instant deadline = clock.instant().plusMillis(maxWaitMillis);
        while (true) {
            long remaining = Duration.between(clock.instant(), deadline).toMillis();
            if (remaining <= 0) {
                return Optional.empty();
            }
            byte[] payload = current.receive(remaining);
            if (payload == null) {
                return Optional.empty();
            }
            try {
                if (codec == null) {
                    EzspCodec.LegacyVersionResponse legacy =
                            EzspCodec.decodeLegacyVersionResponse(payload);
                    byte[] parameters = new byte[] {
                        (byte) legacy.protocolVersion(),
                        (byte) legacy.stackType(),
                        (byte) (legacy.stackVersion() & 0xFF),
                        (byte) ((legacy.stackVersion() >> 8) & 0xFF)
                    };
                    return Optional.of(new Inbound(
                            legacy.sequence(), new EzspFrame(0x0000, false, parameters)));
                }
                EzspCodec.Decoded decoded = codec.decode(payload);
                return Optional.of(new Inbound(decoded.sequence(), decoded.frame()));
            } catch (EzspFormatException e) {
                log.warn("zigbee.ezsp_frame_rejected: {}", e.getMessage());
                // Discarded; keep pumping within the deadline.
            }
        }
    }

    /**
     * Pins the negotiated protocol version, switching decode to the extended v8+
     * format with the version-keyed status width seam (D-M92-4). Called exactly once
     * per session by the protocol layer after version negotiation (AMD-96/E6).
     *
     * @param negotiatedVersion the version from the negotiation response
     */
    void pinVersion(int negotiatedVersion) {
        this.codec = EzspCodec.forVersion(negotiatedVersion);
    }

    /** Returns the pinned codec, or {@code null} before negotiation. */
    EzspCodec codec() {
        return codec;
    }

    /** Returns the ASH session state, or CLOSED when the transport is not open. */
    AshSession.State sessionState() {
        return session == null ? AshSession.State.CLOSED : session.state();
    }

    /** Returns the last RSTACK reset code, or {@code -1} (diagnostics, Doc 08 §3.3). */
    int lastResetCode() {
        return session == null ? -1 : session.lastResetCode();
    }

    private AshSession requireOpen() {
        if (session == null) {
            throw new IllegalStateException("transport is not open");
        }
        return session;
    }
}
