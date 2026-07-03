/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Objects;

/**
 * The ASH data-link state machine (AN706, Doc 08 §3.3): reset handshake, stop-and-wait
 * window of 1, piggyback acknowledgements, retransmission on NAK or ACK timeout with
 * the adaptive timeout band, and the consecutive-timeout FAILED transition.
 *
 * <p>Key contracts:
 * <ul>
 *   <li>After construction no I/O occurs (INV-RF-03) — I/O begins at {@link #connect()}.</li>
 *   <li>A reset handshake (Cancel + RST → RSTACK) must complete before DATA flows;
 *       the RSTACK reset code is surfaced via {@link #lastResetCode()}.</li>
 *   <li>The adaptive ACK timeout starts at 1.6 s in the 0.4–3.2 s band: it doubles on
 *       timeout and adapts as {@code T = 7/8·T + 1/2·measured} on acknowledgement
 *       (AN706 T_RX_ACK).</li>
 *   <li>The {@value #MAX_CONSECUTIVE_ACK_TIMEOUTS}th consecutive ACK timeout transitions
 *       CONNECTED → FAILED exactly once, surfacing one {@link TransportFailureException};
 *       every subsequent send/receive is fast-rejected with
 *       {@link IllegalStateException}. Recovery is {@link #connect()} (reset) or the
 *       {@link PortWatchdog} reopen path.</li>
 *   <li>Corrupt-CRC frames are discarded with a structured WARN and a single NAK per
 *       reject entry — never an exception across the transport boundary.</li>
 * </ul>
 *
 * <p>The consecutive-timeout threshold of 5 is the Doc 08 §3.3 LOCKED value. Reference
 * implementations disagree with each other (bellows: 4; the Silicon Labs ash-host
 * reference and zigbee-herdsman: 6); 5 is a deliberate policy midpoint, not a wire
 * constant — interoperability is unaffected.
 *
 * <p>Not thread-safe: the session runs on the single dedicated serial platform thread
 * ({@code IoType.SERIAL}, Doc 05 §3.2), mirroring {@link CoordinatorTransport}'s
 * contract. Received nRdy flow-control bits are tolerated but not acted on in M9.2.
 *
 * @see AshCodec
 * @see EzspAshTransport
 */
final class AshSession {

    /** Initial adaptive ACK timeout (AN706 T_RX_ACK_INIT, Doc 08 §3.3). */
    static final long ACK_TIMEOUT_INITIAL_MILLIS = 1600;
    /** Adaptive ACK timeout floor (AN706 T_RX_ACK_MIN, Doc 08 §3.3). */
    static final long ACK_TIMEOUT_MIN_MILLIS = 400;
    /** Adaptive ACK timeout ceiling (AN706 T_RX_ACK_MAX, Doc 08 §3.3). */
    static final long ACK_TIMEOUT_MAX_MILLIS = 3200;
    /** Consecutive ACK timeouts before FAILED (Doc 08 §3.3 LOCKED policy value). */
    static final int MAX_CONSECUTIVE_ACK_TIMEOUTS = 5;
    /**
     * RSTACK wait after RST. AN706 T_RSTACK_MAX = 3.2 s, which also satisfies
     * Doc 08 §3.3's "at least 2 s" requirement.
     */
    static final long RSTACK_TIMEOUT_MILLIS = 3200;
    /**
     * NAK-driven retransmissions allowed within one send before the session FAILS.
     * Chosen defensive bound (no §3.3 numeric): without it a babbling NCP that NAKs
     * every retransmission livelocks send() under the pipeline lock — the
     * consecutive-timeout counter never accrues because a NAK resets the ACK timer
     * (adversarial-review finding, 2026-07-03).
     */
    static final int MAX_NAK_RETRANSMITS_PER_SEND = 8;

    private static final Logger log = LoggerFactory.getLogger(AshSession.class);
    private static final int READ_BUFFER_SIZE = 256;

    /** ASH session lifecycle states (Doc 08 §3.3: CONNECTED, FAILED). */
    enum State {
        /** No session: before {@link #connect()} or after {@link #close()}. */
        CLOSED,
        /** Reset handshake complete; DATA may flow. */
        CONNECTED,
        /** Terminal until reset: consecutive-timeout exhaustion or NCP/port failure. */
        FAILED
    }

    private final SerialByteChannel channel;
    private final Clock clock;
    private final AshFrameAccumulator accumulator = new AshFrameAccumulator();
    private final ArrayDeque<AshFrame> parsedFrames = new ArrayDeque<>();
    private final ArrayDeque<byte[]> receivedPayloads = new ArrayDeque<>();
    private final byte[] readBuffer = new byte[READ_BUFFER_SIZE];

    private State state = State.CLOSED;
    private int txFrameNumber;
    private int rxNext;
    private long ackTimeoutMillis = ACK_TIMEOUT_INITIAL_MILLIS;
    private int consecutiveTimeouts;
    private int lastResetCode = -1;
    private boolean inRejectCondition;
    private long retransmitCount;
    private long crcRejectCount;
    private long timeoutCount;
    private String lastOutbound = "none";

    /**
     * Creates a session over {@code channel}. Performs no I/O (INV-RF-03).
     *
     * @param channel the byte channel, never {@code null}
     * @param clock the time source for all deadline arithmetic, never {@code null}
     */
    AshSession(SerialByteChannel channel, Clock clock) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Performs the ASH reset handshake: Cancel + RST, then awaits RSTACK for up to
     * {@value #RSTACK_TIMEOUT_MILLIS} ms. Callable from any state — this is the
     * reset/recovery path for a FAILED session.
     *
     * @throws TransportFailureException if no RSTACK arrives, an ERROR frame arrives
     *                                   instead, or the port is dead
     */
    void connect() {
        accumulator.reset();
        parsedFrames.clear();
        channel.flushInput();

        byte[] rst = AshCodec.emit(new AshFrame.Rst());
        byte[] cancelPlusRst = new byte[rst.length + 1];
        cancelPlusRst[0] = (byte) AshCodec.CANCEL;
        System.arraycopy(rst, 0, cancelPlusRst, 1, rst.length);
        channel.write(cancelPlusRst);
        lastOutbound = "RST";

        Instant deadline = clock.instant().plusMillis(RSTACK_TIMEOUT_MILLIS);
        while (true) {
            AshFrame frame = pumpFrame(deadline);
            if (frame == null) {
                state = State.FAILED;
                throw new TransportFailureException(
                        "ASH reset failed: no RSTACK within " + RSTACK_TIMEOUT_MILLIS
                                + " ms; " + diagnostics());
            }
            if (frame instanceof AshFrame.RstAck rstAck) {
                lastResetCode = rstAck.resetCode();
                state = State.CONNECTED;
                txFrameNumber = 0;
                rxNext = 0;
                ackTimeoutMillis = ACK_TIMEOUT_INITIAL_MILLIS;
                consecutiveTimeouts = 0;
                inRejectCondition = false;
                receivedPayloads.clear();
                log.info("ASH session connected: ashVersion={} resetCode=0x{}",
                        rstAck.version(), Integer.toHexString(rstAck.resetCode()));
                return;
            }
            if (frame instanceof AshFrame.Error error) {
                state = State.FAILED;
                throw new TransportFailureException(String.format(
                        "ASH reset failed: NCP ERROR frame, version=%d code=0x%02X",
                        error.version(), error.errorCode()));
            }
            // Stale DATA/ACK/NAK from a previous session: discarded during reset.
        }
    }

    /**
     * Sends one EZSP frame as an ASH DATA frame and blocks until acknowledged, applying
     * the retransmit protocol (NAK or ACK timeout) and the adaptive timeout band.
     *
     * <p>Inbound DATA frames arriving during the acknowledgement wait are delivered to
     * the receive buffer (their piggyback ackNum may complete this send).
     *
     * @param ezspPayload the EZSP frame bytes, never {@code null}
     * @throws IllegalStateException if the session is not CONNECTED (fast reject)
     * @throws TransportFailureException on the FAILED transition or a dead port
     */
    void send(byte[] ezspPayload) {
        Objects.requireNonNull(ezspPayload, "ezspPayload");
        requireConnected();

        int frameNumber = txFrameNumber;
        writeData(frameNumber, ezspPayload, false);
        Instant sentAt = clock.instant();
        int nakRetransmits = 0;

        while (true) {
            long remaining = ackTimeoutMillis
                    - Duration.between(sentAt, clock.instant()).toMillis();
            if (remaining <= 0) {
                timeoutCount++;
                consecutiveTimeouts++;
                if (consecutiveTimeouts >= MAX_CONSECUTIVE_ACK_TIMEOUTS) {
                    state = State.FAILED;
                    throw new TransportFailureException(
                            MAX_CONSECUTIVE_ACK_TIMEOUTS
                                    + " consecutive ASH ACK timeouts; " + diagnostics());
                }
                ackTimeoutMillis =
                        Math.min(ackTimeoutMillis * 2, ACK_TIMEOUT_MAX_MILLIS);
                writeData(frameNumber, ezspPayload, true);
                retransmitCount++;
                sentAt = clock.instant();
                continue;
            }
            AshFrame frame = pumpFrame(clock.instant().plusMillis(remaining));
            if (frame == null) {
                continue; // deadline re-checked at loop top
            }
            switch (frame) {
                case AshFrame.Data data -> {
                    handleInboundData(data);
                    if (acknowledgesCurrentFrame(data.ackNumber())) {
                        completeSend(sentAt);
                        return;
                    }
                }
                case AshFrame.Ack ack -> {
                    if (acknowledgesCurrentFrame(ack.ackNumber())) {
                        completeSend(sentAt);
                        return;
                    }
                }
                case AshFrame.Nak nak -> {
                    nakRetransmits++;
                    if (nakRetransmits >= MAX_NAK_RETRANSMITS_PER_SEND) {
                        state = State.FAILED;
                        throw new TransportFailureException(
                                "NAK storm: " + nakRetransmits + " NAK-driven "
                                        + "retransmissions of one frame; "
                                        + diagnostics());
                    }
                    writeData(frameNumber, ezspPayload, true);
                    retransmitCount++;
                    sentAt = clock.instant();
                }
                case AshFrame.RstAck rstAck -> {
                    state = State.FAILED;
                    throw new TransportFailureException(String.format(
                            "unexpected NCP reset (RSTACK) while connected: "
                                    + "resetCode=0x%02X; %s",
                            rstAck.resetCode(), diagnostics()));
                }
                case AshFrame.Error error -> {
                    state = State.FAILED;
                    throw new TransportFailureException(String.format(
                            "NCP ERROR frame: version=%d code=0x%02X; %s",
                            error.version(), error.errorCode(), diagnostics()));
                }
                case AshFrame.Rst rst -> {
                    // A host-side frame class; never expected inbound. Discarded.
                }
            }
        }
    }

    /**
     * Returns the next received EZSP payload, waiting up to {@code maxWaitMillis}.
     *
     * @param maxWaitMillis the maximum time to wait
     * @return the next payload, or {@code null} if none arrived within the wait
     * @throws IllegalStateException if the session is not CONNECTED (fast reject)
     * @throws TransportFailureException on NCP failure frames or a dead port
     */
    byte[] receive(long maxWaitMillis) {
        requireConnected();
        if (!receivedPayloads.isEmpty()) {
            return receivedPayloads.poll();
        }
        Instant deadline = clock.instant().plusMillis(maxWaitMillis);
        while (receivedPayloads.isEmpty()) {
            AshFrame frame = pumpFrame(deadline);
            if (frame == null) {
                return null;
            }
            switch (frame) {
                case AshFrame.Data data -> handleInboundData(data);
                case AshFrame.RstAck rstAck -> {
                    state = State.FAILED;
                    throw new TransportFailureException(String.format(
                            "unexpected NCP reset (RSTACK) while connected: "
                                    + "resetCode=0x%02X; %s",
                            rstAck.resetCode(), diagnostics()));
                }
                case AshFrame.Error error -> {
                    state = State.FAILED;
                    throw new TransportFailureException(String.format(
                            "NCP ERROR frame: version=%d code=0x%02X; %s",
                            error.version(), error.errorCode(), diagnostics()));
                }
                case AshFrame.Ack ack -> {
                    // Stray ACK with no send in flight: no action required.
                }
                case AshFrame.Nak nak -> {
                    // Stray NAK with no send in flight: no action required.
                }
                case AshFrame.Rst rst -> {
                    // A host-side frame class; never expected inbound. Discarded.
                }
            }
        }
        return receivedPayloads.poll();
    }

    /**
     * Closes the session and the underlying channel. Idempotent.
     */
    void close() {
        channel.close();
        state = State.CLOSED;
    }

    /** Returns the current session state. */
    State state() {
        return state;
    }

    /** Returns the reset code from the most recent RSTACK, or {@code -1} if none. */
    int lastResetCode() {
        return lastResetCode;
    }

    /** Returns the current adaptive ACK timeout in milliseconds (test/diagnostic). */
    long currentAckTimeoutMillis() {
        return ackTimeoutMillis;
    }

    /** Returns the total number of DATA retransmissions. */
    long retransmitCount() {
        return retransmitCount;
    }

    /** Returns the number of frames discarded for CRC/format corruption. */
    long crcRejectCount() {
        return crcRejectCount;
    }

    private void requireConnected() {
        if (state == State.FAILED) {
            throw new IllegalStateException(
                    "ASH session is FAILED; reset required before further I/O");
        }
        if (state != State.CONNECTED) {
            throw new IllegalStateException(
                    "ASH session is not connected; call connect() first");
        }
    }

    private void writeData(int frameNumber, byte[] payload, boolean retransmitted) {
        channel.write(AshCodec.emit(
                new AshFrame.Data(frameNumber, rxNext, retransmitted, payload)));
        lastOutbound = String.format(
                "DATA(frm=%d, ack=%d, reTx=%b)", frameNumber, rxNext, retransmitted);
    }

    private boolean acknowledgesCurrentFrame(int ackNumber) {
        return ackNumber == ((txFrameNumber + 1) & 0x07);
    }

    private void completeSend(Instant sentAt) {
        long measured = Duration.between(sentAt, clock.instant()).toMillis();
        long adapted = (7 * ackTimeoutMillis) / 8 + measured / 2;
        ackTimeoutMillis = Math.max(ACK_TIMEOUT_MIN_MILLIS,
                Math.min(adapted, ACK_TIMEOUT_MAX_MILLIS));
        consecutiveTimeouts = 0;
        txFrameNumber = (txFrameNumber + 1) & 0x07;
    }

    private void handleInboundData(AshFrame.Data data) {
        if (data.frameNumber() == rxNext) {
            receivedPayloads.add(data.payload());
            rxNext = (rxNext + 1) & 0x07;
            inRejectCondition = false;
            channel.write(AshCodec.emit(new AshFrame.Ack(rxNext, false)));
        } else if (data.frameNumber() == ((rxNext + 7) & 0x07)) {
            // Duplicate of the already-delivered frame (lost ACK): re-acknowledge,
            // never re-deliver (AN706).
            channel.write(AshCodec.emit(new AshFrame.Ack(rxNext, false)));
        } else {
            // Genuinely out-of-sequence: reject condition, NAK (AN706).
            enterRejectCondition("out-of-sequence DATA frame frm="
                    + data.frameNumber() + " expected=" + rxNext);
        }
    }

    private void enterRejectCondition(String reason) {
        if (state == State.CONNECTED && !inRejectCondition) {
            inRejectCondition = true;
            channel.write(AshCodec.emit(new AshFrame.Nak(rxNext, false)));
        }
        log.warn("zigbee.ash_frame_rejected: {} crcRejects={} retransmits={}",
                reason, crcRejectCount, retransmitCount);
    }

    /**
     * Pumps the channel until one frame is available or {@code deadline} passes.
     * Returns {@code null} on deadline expiry.
     */
    private AshFrame pumpFrame(Instant deadline) {
        while (true) {
            AshFrame queued = parsedFrames.poll();
            if (queued != null) {
                return queued;
            }
            long remaining = Duration.between(clock.instant(), deadline).toMillis();
            if (remaining <= 0) {
                return null;
            }
            int n = channel.read(readBuffer, remaining);
            if (n < 0) {
                state = State.FAILED;
                throw new TransportFailureException(
                        "serial read error: port dead or closed; " + diagnostics());
            }
            if (n == 0) {
                continue; // clock has advanced; deadline re-checked
            }
            for (byte[] body : accumulator.accept(readBuffer, n)) {
                switch (AshCodec.parse(body)) {
                    case AshCodec.ParseResult.Parsed parsed ->
                            parsedFrames.add(parsed.frame());
                    case AshCodec.ParseResult.Rejected rejected -> {
                        crcRejectCount++;
                        enterRejectCondition(rejected.reason());
                    }
                }
            }
        }
    }

    private String diagnostics() {
        return String.format(
                "lastFrame=%s retransmits=%d crcRejects=%d timeouts=%d",
                lastOutbound, retransmitCount, crcRejectCount, timeoutCount);
    }
}
