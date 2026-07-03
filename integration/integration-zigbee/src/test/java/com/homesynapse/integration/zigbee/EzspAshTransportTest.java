/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.test.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link EzspAshTransport} tests: the D-M92-1 {@code open(Object)} validation (the
 * frozen Phase-2 {@code Object} parameter stays; the implementation validates
 * {@code instanceof SerialPort} internally), open-performs-the-reset-handshake,
 * close idempotence, and failure-path channel hygiene.
 */
class EzspAshTransportTest {

    private TestClock clock;
    private FakeSerialByteChannel channel;

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        channel = new FakeSerialByteChannel(clock);
    }

    @Test
    @DisplayName("D-M92-1: a non-SerialPort argument is rejected, naming the "
            + "expected type (production opener)")
    void open_rejectsNonSerialPort() {
        EzspAshTransport transport = new EzspAshTransport(clock);

        assertThatThrownBy(() -> transport.open("not a serial port"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("com.fazecast.jSerialComm.SerialPort")
                .hasMessageContaining("java.lang.String");
    }

    @Test
    @DisplayName("a null open argument is rejected")
    void open_nullRejected() {
        EzspAshTransport transport = new EzspAshTransport(clock);

        assertThatThrownBy(() -> transport.open(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("open performs the ASH reset handshake — a returned open() means "
            + "DATA may flow")
    void open_performsResetHandshake() {
        channel.onWrite(written ->
                List.of(AshCodec.emit(new AshFrame.RstAck(2, 0x02))));
        EzspAshTransport transport = new EzspAshTransport(clock, arg -> channel);

        transport.open(new Object());

        assertThat(transport.sessionState()).isEqualTo(AshSession.State.CONNECTED);
        assertThat(transport.lastResetCode()).isEqualTo(0x02);
        assertThat(channel.writes().get(0))
                .containsExactly(0x1A, 0xC0, 0x38, 0xBC, 0x7E);
    }

    @Test
    @DisplayName("a failed reset handshake closes the just-opened channel "
            + "(no leaked port handle)")
    void open_handshakeFailure_closesChannel() {
        EzspAshTransport transport = new EzspAshTransport(clock, arg -> channel);

        assertThatThrownBy(() -> transport.open(new Object()))
                .isInstanceOf(TransportFailureException.class);
        assertThat(channel.isOpen()).isFalse();
        assertThat(transport.sessionState()).isEqualTo(AshSession.State.CLOSED);
    }

    @Test
    @DisplayName("open on an already-open transport is rejected")
    void open_twice_rejected() {
        channel.onWrite(written ->
                List.of(AshCodec.emit(new AshFrame.RstAck(2, 0x02))));
        EzspAshTransport transport = new EzspAshTransport(clock, arg -> channel);
        transport.open(new Object());

        assertThatThrownBy(() -> transport.open(new Object()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already open");
    }

    @Test
    @DisplayName("close is idempotent (frozen CoordinatorTransport contract)")
    void close_idempotent() {
        channel.onWrite(written ->
                List.of(AshCodec.emit(new AshFrame.RstAck(2, 0x02))));
        EzspAshTransport transport = new EzspAshTransport(clock, arg -> channel);
        transport.open(new Object());

        transport.close();
        transport.close(); // no effect, no throw

        assertThat(channel.isOpen()).isFalse();
        assertThat(transport.sessionState()).isEqualTo(AshSession.State.CLOSED);
        assertThatThrownBy(() -> transport.sendFrame(new byte[] {0x00}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not open");
    }
}
