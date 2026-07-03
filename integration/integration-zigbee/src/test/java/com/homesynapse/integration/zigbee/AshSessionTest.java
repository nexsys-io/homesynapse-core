/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.test.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AshSession} state-machine tests: reset handshake gate, stop-and-wait window,
 * retransmit on NAK and on ACK timeout, the adaptive timeout band edges
 * (0.4 / 1.6 / 3.2 s), the five-consecutive-timeouts FAILED transition (exactly once),
 * post-FAILED fast-reject, and the surfaced RSTACK reset code.
 *
 * <p>All timing is deterministic: the fake channel advances the injected
 * {@link TestClock} on empty reads — zero real waits, zero sleeping.
 */
class AshSessionTest {

    private TestClock clock;
    private FakeSerialByteChannel channel;
    private AshSession session;

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        channel = new FakeSerialByteChannel(clock);
        session = new AshSession(channel, clock);
    }

    @Test
    @DisplayName("INV-RF-03: construction performs no I/O")
    void constructor_performsNoIo() {
        FakeSerialByteChannel guarded = new FakeSerialByteChannel(clock);
        guarded.throwOnAnyIo();

        new AshSession(guarded, clock); // must not touch the channel
    }

    @Test
    @DisplayName("connect sends Cancel+RST and surfaces the RSTACK reset code")
    void connect_handshakeAndResetCode() {
        // connect() flushes stale input first, so the RSTACK must arrive reactively.
        channel.onWrite(written ->
                List.of(AshCodec.emit(new AshFrame.RstAck(2, 0x0B))));

        session.connect();

        assertThat(channel.writes().get(0))
                .containsExactly(0x1A, 0xC0, 0x38, 0xBC, 0x7E);
        assertThat(session.state()).isEqualTo(AshSession.State.CONNECTED);
        assertThat(session.lastResetCode()).isEqualTo(0x0B);
    }

    @Test
    @DisplayName("connect times out after 3200 ms (≥ 2 s per §3.3) without RSTACK")
    void connect_timeout() {
        Instant start = clock.peek();

        assertThatThrownBy(() -> session.connect())
                .isInstanceOf(TransportFailureException.class)
                .hasMessageContaining("RSTACK");
        assertThat(clock.peek().toEpochMilli() - start.toEpochMilli())
                .isGreaterThanOrEqualTo(AshSession.RSTACK_TIMEOUT_MILLIS);
        assertThat(session.state()).isEqualTo(AshSession.State.FAILED);
    }

    @Test
    @DisplayName("connect fails on an ERROR frame, surfacing the error code")
    void connect_errorFrame() {
        channel.onWrite(written ->
                List.of(AshCodec.emit(new AshFrame.Error(2, 0x51))));

        assertThatThrownBy(() -> session.connect())
                .isInstanceOf(TransportFailureException.class)
                .hasMessageContaining("0x51");
    }

    @Test
    @DisplayName("DATA is gated on the reset handshake: send before connect rejects")
    void send_beforeConnect_rejected() {
        assertThatThrownBy(() -> session.send(new byte[] {0x01}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("connect");
    }

    @Test
    @DisplayName("stop-and-wait: acknowledged sends advance the 3-bit sequence")
    void send_advancesSequence() {
        connect();

        channel.enqueue(AshCodec.emit(new AshFrame.Ack(1, false)));
        session.send(new byte[] {0x10});
        channel.enqueue(AshCodec.emit(new AshFrame.Ack(2, false)));
        session.send(new byte[] {0x20});

        List<AshFrame.Data> sent = sentDataFrames();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).frameNumber()).isEqualTo(0);
        assertThat(sent.get(1).frameNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("the 3-bit frame number wraps 7 -> 0 on the 9th acknowledged send "
            + "(review hardening H2 — mod-8 arithmetic pinned across the boundary)")
    void send_frameNumberWrapsMod8() {
        connect();

        for (int i = 0; i < 9; i++) {
            channel.enqueue(AshCodec.emit(new AshFrame.Ack((i + 1) & 0x07, false)));
            session.send(new byte[] {(byte) i});
        }

        List<AshFrame.Data> sent = sentDataFrames();
        assertThat(sent).hasSize(9);
        assertThat(sent.get(7).frameNumber()).isEqualTo(7);
        assertThat(sent.get(8).frameNumber()).isEqualTo(0);
        assertThat(session.retransmitCount()).isZero();
    }

    @Test
    @DisplayName("a flagless byte storm is bounded by the accumulator and the stream "
            + "resynchronizes at the next Flag (review hardening H1)")
    void accumulator_byteStorm_boundedAndResyncs() {
        AshFrameAccumulator accumulator = new AshFrameAccumulator();
        byte[] storm = new byte[4096];
        java.util.Arrays.fill(storm, (byte) 0x55);

        // No Flag ever arrives: nothing completes, and the buffer must not grow
        // past the bound (the storm is 8x the cap).
        assertThat(accumulator.accept(storm, storm.length)).isEmpty();

        // A Flag resynchronizes; the next well-formed frame parses cleanly.
        assertThat(accumulator.accept(new byte[] {(byte) AshCodec.FLAG}, 1)).isEmpty();
        byte[] rstack = AshCodec.emit(new AshFrame.RstAck(2, 0x02));
        assertThat(accumulator.accept(rstack, rstack.length)).hasSize(1);
    }

    @Test
    @DisplayName("a piggyback ackNum on inbound DATA completes the send and delivers")
    void send_piggybackAck() {
        connect();
        byte[] inbound = {0x77, 0x35};
        channel.enqueue(AshCodec.emit(new AshFrame.Data(0, 1, false, inbound)));

        session.send(new byte[] {0x10});

        assertThat(session.receive(0)).isEqualTo(inbound);
        // The inbound DATA was acknowledged.
        assertThat(sentFrames()).anySatisfy(frame -> {
            assertThat(frame).isInstanceOf(AshFrame.Ack.class);
            assertThat(((AshFrame.Ack) frame).ackNumber()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("NAK triggers an immediate retransmission with the reTx bit")
    void send_retransmitOnNak() {
        connect();
        channel.enqueue(AshCodec.emit(new AshFrame.Nak(0, false)));
        channel.enqueue(AshCodec.emit(new AshFrame.Ack(1, false)));

        session.send(new byte[] {0x10});

        List<AshFrame.Data> sent = sentDataFrames();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).retransmitted()).isFalse();
        assertThat(sent.get(1).retransmitted()).isTrue();
        assertThat(sent.get(1).frameNumber()).isEqualTo(0);
        assertThat(session.retransmitCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("ACK timeout doubles the adaptive timeout and retransmits; the 5th "
            + "consecutive timeout fails EXACTLY once, then fast-rejects")
    void send_fiveConsecutiveTimeouts_failedExactlyOnce() {
        connect();
        Instant start = clock.peek();

        assertThatThrownBy(() -> session.send(new byte[] {0x10}))
                .isInstanceOf(TransportFailureException.class)
                .hasMessageContaining("5 consecutive");

        // Waits: 1600 (initial) + 3200 ×4 (doubled, capped at the 3.2 s band edge).
        assertThat(clock.peek().toEpochMilli() - start.toEpochMilli())
                .isEqualTo(1600 + 4 * 3200);
        assertThat(session.state()).isEqualTo(AshSession.State.FAILED);
        assertThat(session.currentAckTimeoutMillis())
                .isEqualTo(AshSession.ACK_TIMEOUT_MAX_MILLIS);
        // 4 retransmissions happened (the 5th timeout fails instead of retransmitting).
        assertThat(session.retransmitCount()).isEqualTo(4);

        // Post-FAILED: fast reject with IllegalStateException — one failure signal,
        // no failure storms.
        assertThatThrownBy(() -> session.send(new byte[] {0x11}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAILED");
        assertThatThrownBy(() -> session.receive(10))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a NAK storm hits the per-send retransmission bound and fails "
            + "the session (no livelock under the pipeline lock)")
    void send_nakStorm_failsSession() {
        connect();
        channel.onWrite(written -> {
            AshFrame frame = lastFrameOf(written);
            if (frame instanceof AshFrame.Data) {
                return List.of(AshCodec.emit(new AshFrame.Nak(0, false)));
            }
            return List.of();
        });

        assertThatThrownBy(() -> session.send(new byte[] {0x10}))
                .isInstanceOf(TransportFailureException.class)
                .hasMessageContaining("NAK storm");
        assertThat(session.state()).isEqualTo(AshSession.State.FAILED);
        assertThat(session.retransmitCount())
                .isEqualTo(AshSession.MAX_NAK_RETRANSMITS_PER_SEND - 1);
    }

    @Test
    @DisplayName("adaptive band floor: fast acknowledgements converge the timeout "
            + "to 0.4 s and never below")
    void send_adaptiveTimeout_floorsAt400() {
        connect();
        channel.onWrite(written -> {
            AshFrame frame = lastFrameOf(written);
            if (frame instanceof AshFrame.Data data) {
                return List.of(AshCodec.emit(
                        new AshFrame.Ack((data.frameNumber() + 1) & 0x07, false)));
            }
            return List.of();
        });

        assertThat(session.currentAckTimeoutMillis())
                .isEqualTo(AshSession.ACK_TIMEOUT_INITIAL_MILLIS);
        for (int i = 0; i < 30; i++) {
            session.send(new byte[] {(byte) i});
        }

        assertThat(session.currentAckTimeoutMillis())
                .isEqualTo(AshSession.ACK_TIMEOUT_MIN_MILLIS);
    }

    @Test
    @DisplayName("in-sequence DATA delivers and is acknowledged")
    void receive_inSequence_deliversAndAcks() {
        connect();
        byte[] payload = {0x01, 0x02, 0x03};
        channel.enqueue(AshCodec.emit(new AshFrame.Data(0, 0, false, payload)));

        assertThat(session.receive(100)).isEqualTo(payload);
        assertThat(sentFrames()).anySatisfy(frame -> {
            assertThat(frame).isInstanceOf(AshFrame.Ack.class);
            assertThat(((AshFrame.Ack) frame).ackNumber()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("a duplicate (retransmitted) DATA frame is re-acknowledged, "
            + "never re-delivered")
    void receive_duplicate_reAckedNotRedelivered() {
        connect();
        byte[] payload = {0x42};
        channel.enqueue(AshCodec.emit(new AshFrame.Data(0, 0, false, payload)));
        assertThat(session.receive(100)).isEqualTo(payload);
        channel.clearWrites();

        channel.enqueue(AshCodec.emit(new AshFrame.Data(0, 0, true, payload)));

        assertThat(session.receive(50)).isNull(); // no re-delivery
        assertThat(sentFrames()).anySatisfy(frame -> {
            assertThat(frame).isInstanceOf(AshFrame.Ack.class);
            assertThat(((AshFrame.Ack) frame).ackNumber()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("a genuinely out-of-sequence DATA frame triggers the reject "
            + "condition (NAK), not a re-ACK")
    void receive_outOfSequence_naks() {
        connect();
        channel.enqueue(AshCodec.emit(
                new AshFrame.Data(3, 0, false, new byte[] {0x01})));

        assertThat(session.receive(50)).isNull();
        assertThat(sentFrames()).anySatisfy(frame -> {
            assertThat(frame).isInstanceOf(AshFrame.Nak.class);
            assertThat(((AshFrame.Nak) frame).ackNumber()).isEqualTo(0);
        });
    }

    @Test
    @DisplayName("corrupt-CRC frames are discarded and counted; a NAK signals the "
            + "reject condition; valid traffic then resumes")
    void receive_corruptCrc_discardedCountedNakked() {
        connect();
        byte[] wire = AshCodec.emit(new AshFrame.Data(0, 0, false, new byte[] {0x10}));
        byte[] corrupted = wire.clone();
        corrupted[1] ^= 0x40;
        channel.enqueue(corrupted);

        assertThat(session.receive(50)).isNull();
        assertThat(session.crcRejectCount()).isEqualTo(1);
        assertThat(sentFrames()).anyMatch(frame -> frame instanceof AshFrame.Nak);

        byte[] payload = {0x20};
        channel.enqueue(AshCodec.emit(new AshFrame.Data(0, 0, false, payload)));
        assertThat(session.receive(100)).isEqualTo(payload);
    }

    @Test
    @DisplayName("an unexpected RSTACK while connected is a transport failure")
    void receive_unexpectedRstack_fails() {
        connect();
        channel.enqueue(AshCodec.emit(new AshFrame.RstAck(2, 0x02)));

        assertThatThrownBy(() -> session.receive(100))
                .isInstanceOf(TransportFailureException.class)
                .hasMessageContaining("RSTACK");
        assertThat(session.state()).isEqualTo(AshSession.State.FAILED);
    }

    @Test
    @DisplayName("a dead port (read error) is a transport failure, and connect() "
            + "is the recovery path")
    void receive_portDeath_failsThenReconnectRecovers() {
        connect();
        channel.markDead();

        assertThatThrownBy(() -> session.receive(100))
                .isInstanceOf(TransportFailureException.class)
                .hasMessageContaining("read error");
        assertThat(session.state()).isEqualTo(AshSession.State.FAILED);
    }

    @Test
    @DisplayName("close is idempotent")
    void close_idempotent() {
        connect();

        session.close();
        session.close();

        assertThat(session.state()).isEqualTo(AshSession.State.CLOSED);
        assertThat(channel.isOpen()).isFalse();
    }

    private void connect() {
        // connect() flushes stale input first, so the RSTACK must arrive reactively.
        channel.onWrite(written ->
                List.of(AshCodec.emit(new AshFrame.RstAck(2, 0x02))));
        session.connect();
        channel.onWrite(null);
        channel.clearWrites();
    }

    private List<AshFrame> sentFrames() {
        AshFrameAccumulator accumulator = new AshFrameAccumulator();
        byte[] all = channel.allWrittenBytes();
        return accumulator.accept(all, all.length).stream()
                .map(AshCodec::parse)
                .filter(r -> r instanceof AshCodec.ParseResult.Parsed)
                .map(r -> ((AshCodec.ParseResult.Parsed) r).frame())
                .toList();
    }

    private List<AshFrame.Data> sentDataFrames() {
        return sentFrames().stream()
                .filter(f -> f instanceof AshFrame.Data)
                .map(f -> (AshFrame.Data) f)
                .toList();
    }

    private static AshFrame lastFrameOf(byte[] written) {
        AshFrameAccumulator accumulator = new AshFrameAccumulator();
        List<byte[]> bodies = accumulator.accept(written, written.length);
        if (bodies.isEmpty()) {
            return null;
        }
        AshCodec.ParseResult result = AshCodec.parse(bodies.get(bodies.size() - 1));
        return result instanceof AshCodec.ParseResult.Parsed parsed
                ? parsed.frame() : null;
    }
}
