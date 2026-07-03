/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.integration.PermanentIntegrationException;
import com.homesynapse.test.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TransportProbe} tests: the Doc 08 §3.3 five-step sequence with its exact
 * budgets, deterministic under the scripted fake channel and test clock — every
 * timeout path executes without real waits.
 */
class TransportProbeTest {

    private static final String PORT = "/dev/ttyUSB0";
    /** SYS_PING SRSP: FE 02 61 01 79 06 FCS(0x1D = 02^61^01^79^06). */
    private static final byte[] SYS_PING_SRSP = {
        (byte) 0xFE, 0x02, 0x61, 0x01, 0x79, 0x06, 0x1D
    };

    private TestClock clock;
    private FakeSerialByteChannel channel;

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        channel = new FakeSerialByteChannel(clock);
    }

    @Test
    @DisplayName("step 2: a valid SYS_PING SRSP yields the ZNP verdict")
    void znpAnswers_znpVerdict() throws Exception {
        channel.onWrite(written ->
                Arrays.equals(written, TransportProbe.ZNP_SYS_PING_SREQ)
                        ? List.of(SYS_PING_SRSP) : List.of());

        TransportProbe.Kind kind = TransportProbe.detect(channel, PORT, clock);

        assertThat(kind).isEqualTo(TransportProbe.Kind.ZNP);
        assertThat(channel.writes().get(0))
                .containsExactly(0xFE, 0x00, 0x21, 0x01, 0x20);
    }

    @Test
    @DisplayName("an SRSP with a corrupt FCS is not accepted — detection proceeds "
            + "to the ASH step and finds EZSP")
    void srspBadFcs_fallsThroughToAsh() throws Exception {
        byte[] corrupt = SYS_PING_SRSP.clone();
        corrupt[corrupt.length - 1] ^= 0x01;
        channel.onWrite(written -> {
            if (Arrays.equals(written, TransportProbe.ZNP_SYS_PING_SREQ)) {
                return List.of(corrupt);
            }
            if (isAshRst(written)) {
                return List.of(AshCodec.emit(new AshFrame.RstAck(2, 0x02)));
            }
            return List.of();
        });

        assertThat(TransportProbe.detect(channel, PORT, clock))
                .isEqualTo(TransportProbe.Kind.EZSP);
    }

    @Test
    @DisplayName("steps 3-4: ZNP silence then RSTACK yields the EZSP verdict")
    void silenceThenRstack_ezspVerdict() throws Exception {
        Instant start = clock.peek();
        channel.onWrite(written -> isAshRst(written)
                ? List.of(AshCodec.emit(new AshFrame.RstAck(2, 0x02)))
                : List.of());

        TransportProbe.Kind kind = TransportProbe.detect(channel, PORT, clock);

        assertThat(kind).isEqualTo(TransportProbe.Kind.EZSP);
        // The ZNP step consumed its full 4 s budget, plus the 200 ms settle.
        long elapsed = clock.peek().toEpochMilli() - start.toEpochMilli();
        assertThat(elapsed).isGreaterThanOrEqualTo(4200);
        assertThat(elapsed).isLessThan(TransportProbe.TOTAL_BUDGET_MILLIS);
    }

    @Test
    @DisplayName("step 5: an ERROR frame triggers exactly one RST retry, which "
            + "may still succeed")
    void errorThenRstack_retryPath() throws Exception {
        int[] rstCount = {0};
        channel.onWrite(written -> {
            if (isAshRst(written)) {
                rstCount[0]++;
                return rstCount[0] == 1
                        ? List.of(AshCodec.emit(new AshFrame.Error(2, 0x51)))
                        : List.of(AshCodec.emit(new AshFrame.RstAck(2, 0x02)));
            }
            return List.of();
        });

        assertThat(TransportProbe.detect(channel, PORT, clock))
                .isEqualTo(TransportProbe.Kind.EZSP);
        assertThat(rstCount[0]).isEqualTo(2);
    }

    @Test
    @DisplayName("total silence: structured failure with the (a)/(b)/(c) guidance "
            + "inside the 10 s budget")
    void totalSilence_structuredFailure() {
        Instant start = clock.peek();

        assertThatThrownBy(() -> TransportProbe.detect(channel, PORT, clock))
                .isInstanceOf(PermanentIntegrationException.class)
                .hasMessageContaining(PORT)
                .hasMessageContaining("connected and powered")
                .hasMessageContaining("permissions")
                .hasMessageContaining("integrations.zigbee.adapter_type")
                .satisfies(e -> assertThat(
                        ((PermanentIntegrationException) e).errorCode())
                        .isEqualTo("zigbee.auto_detect_failed"));

        // Silence path: 4 s ZNP + 0.2 s settle + 4 s ASH — within the 10 s budget.
        long elapsed = clock.peek().toEpochMilli() - start.toEpochMilli();
        assertThat(elapsed).isEqualTo(8200);
    }

    @Test
    @DisplayName("the adapter_type override bypasses all probing — zero I/O")
    void overrideBypass_zeroIo() throws Exception {
        Instant start = clock.peek();

        TransportProbe.Kind kind = TransportProbe.detect(
                channel, PORT, clock, TransportProbe.Kind.ZNP);

        assertThat(kind).isEqualTo(TransportProbe.Kind.ZNP);
        assertThat(channel.writes()).isEmpty();
        assertThat(clock.peek()).isEqualTo(start);
    }

    /** The Cancel-prefixed RST wire form: 1A C0 38 BC 7E. */
    private static boolean isAshRst(byte[] written) {
        return written.length == 5 && (written[0] & 0xFF) == 0x1A
                && (written[1] & 0xFF) == 0xC0;
    }
}
