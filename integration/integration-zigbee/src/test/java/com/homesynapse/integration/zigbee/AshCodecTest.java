/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Byte-vector tests for {@link AshCodec}. Every pinned constant was independently
 * re-derived from AN706 and the bellows/zigbee-herdsman reference implementations
 * before pinning (the external-standard discipline): the LFSR keystream
 * {@code 42 21 A8 54 2A 15 B2 59 94}, the CRC-16/CCITT-FALSE check value
 * {@code 0x29B1} for "123456789", the canonical RST wire form {@code C0 38 BC 7E},
 * and the AN706 DATA-frame example {@code 25 42 21 A8 56 A6 09 7E}.
 */
class AshCodecTest {

    private static final byte[] RESERVED_BYTES = {
        0x7E, 0x7D, 0x11, 0x13, 0x18, 0x1A
    };

    @Test
    @DisplayName("randomizer keystream: first nine bytes pinned from AN706 derivation")
    void randomize_keystreamPinned() {
        byte[] keystream = AshCodec.randomize(new byte[9]);

        assertThat(keystream).containsExactly(
                0x42, 0x21, 0xA8, 0x54, 0x2A, 0x15, 0xB2, 0x59, 0x94);
    }

    @Test
    @DisplayName("randomizer is an involution")
    void randomize_isInvolution() {
        byte[] data = new byte[32];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 7 + 3);
        }

        assertThat(AshCodec.randomize(AshCodec.randomize(data))).isEqualTo(data);
    }

    @Test
    @DisplayName("CRC-CCITT-FALSE check value for '123456789' is 0x29B1")
    void crcCcitt_standardCheckValue() {
        byte[] input = "123456789".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

        assertThat(AshCodec.crcCcitt(input)).isEqualTo(0x29B1);
    }

    @Test
    @DisplayName("CRC of the RST control byte matches the canonical 0x38BC")
    void crcCcitt_rstControlByte() {
        assertThat(AshCodec.crcCcitt(new byte[] {(byte) 0xC0})).isEqualTo(0x38BC);
    }

    @Test
    @DisplayName("RST frame emits the canonical wire form C0 38 BC 7E")
    void emit_rstFrame_canonicalWireForm() {
        assertThat(AshCodec.emit(new AshFrame.Rst()))
                .containsExactly(0xC0, 0x38, 0xBC, 0x7E);
    }

    @Test
    @DisplayName("DATA frame emits the AN706 example: 25 42 21 A8 56 A6 09 7E")
    void emit_dataFrame_an706Example() {
        byte[] wire = AshCodec.emit(new AshFrame.Data(
                2, 5, false, new byte[] {0x00, 0x00, 0x00, 0x02}));

        assertThat(wire).containsExactly(
                0x25, 0x42, 0x21, 0xA8, 0x56, 0xA6, 0x09, 0x7E);
    }

    @Test
    @DisplayName("stuff/destuff round-trips every reserved byte; none appears raw")
    void stuffDestuff_everyReservedByte() {
        for (byte reserved : RESERVED_BYTES) {
            byte[] payload = {0x01, reserved, 0x02, reserved};

            byte[] wire = AshCodec.emit(new AshFrame.Data(1, 0, false, payload));
            byte[] body = Arrays.copyOf(wire, wire.length - 1);

            // No raw reserved byte inside the body (0x7D appears only as the escape
            // marker, always followed by a non-reserved substitute value).
            for (int i = 0; i < body.length; i++) {
                int v = body[i] & 0xFF;
                assertThat(v)
                        .as("raw reserved byte 0x%02X at index %d for reserved 0x%02X",
                                v, i, reserved)
                        .isNotIn(0x7E, 0x11, 0x13, 0x18, 0x1A);
                if (v == 0x7D) {
                    int next = body[++i] & 0xFF;
                    assertThat(next).isIn(0x5E, 0x5D, 0x31, 0x33, 0x38, 0x3A);
                }
            }

            AshCodec.ParseResult result = AshCodec.parse(body);
            assertThat(result).isInstanceOf(AshCodec.ParseResult.Parsed.class);
            AshFrame.Data data = (AshFrame.Data)
                    ((AshCodec.ParseResult.Parsed) result).frame();
            assertThat(data.payload()).isEqualTo(payload);
            assertThat(data.frameNumber()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("emit/parse round-trips ACK, NAK (with nRdy), RSTACK, and ERROR")
    void emitParse_controlFrames() {
        assertThat(parsed(AshCodec.emit(new AshFrame.Ack(3, false))))
                .isEqualTo(new AshFrame.Ack(3, false));
        assertThat(parsed(AshCodec.emit(new AshFrame.Nak(5, true))))
                .isEqualTo(new AshFrame.Nak(5, true));
        assertThat(parsed(AshCodec.emit(new AshFrame.RstAck(2, 0x0B))))
                .isEqualTo(new AshFrame.RstAck(2, 0x0B));
        assertThat(parsed(AshCodec.emit(new AshFrame.Error(2, 0x51))))
                .isEqualTo(new AshFrame.Error(2, 0x51));
    }

    @Test
    @DisplayName("RSTACK parse surfaces version and reset code")
    void parse_rstack_surfacesResetCode() {
        AshFrame frame = parsed(AshCodec.emit(new AshFrame.RstAck(2, 0x02)));

        AshFrame.RstAck rstAck = (AshFrame.RstAck) frame;
        assertThat(rstAck.version()).isEqualTo(2);
        assertThat(rstAck.resetCode()).isEqualTo(0x02);
    }

    @Test
    @DisplayName("corrupt CRC yields a typed rejection, never an exception")
    void parse_corruptCrc_rejected() {
        byte[] wire = AshCodec.emit(new AshFrame.Data(
                0, 0, false, new byte[] {0x10, 0x20, 0x30}));
        byte[] body = Arrays.copyOf(wire, wire.length - 1);
        body[1] ^= 0x40; // corrupt a payload byte; CRC no longer matches

        AshCodec.ParseResult result = AshCodec.parse(body);

        assertThat(result).isInstanceOf(AshCodec.ParseResult.Rejected.class);
        assertThat(((AshCodec.ParseResult.Rejected) result).reason())
                .contains("CRC");
    }

    @Test
    @DisplayName("W7: a long (24-byte) payload with reserved bytes round-trips — "
            + "randomize-then-CRC-then-stuff ordering")
    void emitParse_longVector_orderOfOperations() {
        byte[] payload = new byte[24];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 11);
        }
        payload[3] = 0x7E;
        payload[9] = 0x7D;
        payload[15] = 0x11;
        payload[21] = 0x1A;

        byte[] wire = AshCodec.emit(new AshFrame.Data(6, 2, true, payload));

        AshFrame.Data data = (AshFrame.Data) parsed(wire);
        assertThat(data.payload()).isEqualTo(payload);
        assertThat(data.frameNumber()).isEqualTo(6);
        assertThat(data.ackNumber()).isEqualTo(2);
        assertThat(data.retransmitted()).isTrue();
    }

    @Test
    @DisplayName("trailing escape byte yields a typed rejection")
    void parse_trailingEscape_rejected() {
        byte[] wire = AshCodec.emit(new AshFrame.Ack(1, false));
        byte[] body = Arrays.copyOf(wire, wire.length); // keep length, replace tail
        body[body.length - 1] = 0x7D; // flag position replaced by a dangling escape

        AshCodec.ParseResult result = AshCodec.parse(body);

        assertThat(result).isInstanceOf(AshCodec.ParseResult.Rejected.class);
        assertThat(((AshCodec.ParseResult.Rejected) result).reason())
                .contains("escape");
    }

    @Test
    @DisplayName("unknown control byte yields a typed rejection")
    void parse_unknownControl_rejected() {
        int control = 0xE0;
        int crc = AshCodec.crcCcitt(new byte[] {(byte) control});
        byte[] body = {
            (byte) control, (byte) ((crc >> 8) & 0xFF), (byte) (crc & 0xFF)
        };

        AshCodec.ParseResult result = AshCodec.parse(body);

        assertThat(result).isInstanceOf(AshCodec.ParseResult.Rejected.class);
        assertThat(((AshCodec.ParseResult.Rejected) result).reason())
                .contains("control");
    }

    @Test
    @DisplayName("frames shorter than control+CRC are rejected")
    void parse_tooShort_rejected() {
        assertThat(AshCodec.parse(new byte[] {0x12, 0x34}))
                .isInstanceOf(AshCodec.ParseResult.Rejected.class);
    }

    private static AshFrame parsed(byte[] wire) {
        byte[] body = Arrays.copyOf(wire, wire.length - 1);
        AshCodec.ParseResult result = AshCodec.parse(body);
        assertThat(result).isInstanceOf(AshCodec.ParseResult.Parsed.class);
        return ((AshCodec.ParseResult.Parsed) result).frame();
    }
}
