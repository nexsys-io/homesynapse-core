/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.io.ByteArrayOutputStream;

/**
 * Pure byte-level ASH framing codec: byte stuffing, data randomization, CRC-CCITT,
 * frame assembly and parsing (AN706, Doc 08 §3.3).
 *
 * <p>Transmit pipeline (W7 order-of-operations): randomize (DATA payload only) →
 * compute CRC over control byte + randomized data → byte-stuff ALL frame bytes
 * (control + data + both CRC bytes) → append the un-stuffed Flag {@code 0x7E}.
 * Receive inverts: destuff → verify CRC → de-randomize.
 *
 * <p>Every constant cites AN706 as distilled by Doc 08 §3.3 (AMD-96 currency); the
 * randomizer keystream, CRC parameters, and control-byte layouts were independently
 * re-derived against the bellows/zigbee-herdsman reference implementations before
 * being pinned in {@code AshCodecTest}.
 *
 * <p>Pure and total: no I/O, no clock. Corrupt input yields a typed
 * {@link ParseResult.Rejected}, never an exception across the transport boundary.
 *
 * <p>Thread-safe: stateless static methods.
 *
 * @see AshFrame
 * @see AshFrameAccumulator
 */
final class AshCodec {

    /** Frame delimiter (AN706). Never byte-stuffed. */
    static final int FLAG = 0x7E;
    /** Escape byte (AN706): emitted as {@code 0x7D, byte ^ 0x20}. */
    static final int ESCAPE = 0x7D;
    /** XON software flow control byte (AN706); discarded from the receive stream. */
    static final int XON = 0x11;
    /** XOFF software flow control byte (AN706); discarded from the receive stream. */
    static final int XOFF = 0x13;
    /** Substitute byte (AN706): invalidates the in-progress frame until the next Flag. */
    static final int SUBSTITUTE = 0x18;
    /** Cancel byte (AN706): discards the receiver's partial frame; sent before RST. */
    static final int CANCEL = 0x1A;

    private static final int CONTROL_RST = 0xC0;
    private static final int CONTROL_RSTACK = 0xC1;
    private static final int CONTROL_ERROR = 0xC2;
    /** ASH data randomization LFSR seed (AN706). */
    private static final int LFSR_SEED = 0x42;
    /** ASH data randomization LFSR feedback term (AN706). */
    private static final int LFSR_FEEDBACK = 0xB8;
    /** CRC16-CCITT-FALSE polynomial (AN706). */
    private static final int CRC_POLYNOMIAL = 0x1021;
    /** CRC16-CCITT-FALSE initial value (AN706). */
    private static final int CRC_INITIAL = 0xFFFF;

    private AshCodec() {
    }

    /**
     * Result of parsing an accumulated (still-stuffed) frame body.
     *
     * <p>{@link Rejected} carries the reason for a typed rejection (CRC mismatch,
     * malformed escape, unknown control byte). The session layer logs and discards —
     * the retransmit protocol recovers (Doc 08 §3.3).
     */
    sealed interface ParseResult {

        /**
         * A structurally valid, CRC-verified frame.
         *
         * @param frame the decoded frame
         */
        record Parsed(AshFrame frame) implements ParseResult {
        }

        /**
         * A typed rejection of a corrupt or malformed frame.
         *
         * @param reason the rejection cause, Register C voice
         */
        record Rejected(String reason) implements ParseResult {
        }
    }

    /**
     * XORs {@code data} with the AN706 LFSR keystream (seed {@code 0x42}).
     *
     * <p>An involution: {@code randomize(randomize(x))} equals {@code x}. Applies to
     * DATA-frame payloads ONLY (W7) — control bytes, CRC bytes, and the RSTACK/ERROR
     * data fields are never randomized.
     *
     * @param data the bytes to (de-)randomize, never {@code null}
     * @return a new array with the keystream applied
     */
    static byte[] randomize(byte[] data) {
        byte[] out = new byte[data.length];
        int lfsr = LFSR_SEED;
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ lfsr);
            if ((lfsr & 0x01) == 0) {
                lfsr = lfsr >> 1;
            } else {
                lfsr = (lfsr >> 1) ^ LFSR_FEEDBACK;
            }
        }
        return out;
    }

    /**
     * Computes CRC16-CCITT-FALSE (polynomial {@code 0x1021}, init {@code 0xFFFF}, no
     * reflection, no final XOR) over {@code bytes} — the control byte plus the
     * (randomized, unstuffed) data field.
     *
     * @param bytes the input bytes, never {@code null}
     * @return the 16-bit CRC
     */
    static int crcCcitt(byte[] bytes) {
        int crc = CRC_INITIAL;
        for (byte b : bytes) {
            crc ^= (b & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ CRC_POLYNOMIAL;
                } else {
                    crc = crc << 1;
                }
                crc &= 0xFFFF;
            }
        }
        return crc;
    }

    /**
     * Byte-stuffs {@code raw}: each reserved byte is emitted as {@code 0x7D} followed
     * by the byte XOR {@code 0x20}.
     *
     * @param raw the unstuffed frame bytes, never {@code null}
     * @return the stuffed bytes
     */
    static byte[] stuff(byte[] raw) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length + 4);
        for (byte b : raw) {
            int v = b & 0xFF;
            if (v == FLAG || v == ESCAPE || v == XON || v == XOFF
                    || v == SUBSTITUTE || v == CANCEL) {
                out.write(ESCAPE);
                out.write(v ^ 0x20);
            } else {
                out.write(v);
            }
        }
        return out.toByteArray();
    }

    /**
     * Inverts {@link #stuff(byte[])}.
     *
     * @param stuffed the stuffed frame bytes, never {@code null}
     * @return the destuffed bytes, or {@code null} if the escape sequence is malformed
     *         (trailing escape)
     */
    static byte[] destuff(byte[] stuffed) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(stuffed.length);
        for (int i = 0; i < stuffed.length; i++) {
            int v = stuffed[i] & 0xFF;
            if (v == ESCAPE) {
                i++;
                if (i >= stuffed.length) {
                    return null;
                }
                out.write((stuffed[i] & 0xFF) ^ 0x20);
            } else {
                out.write(v);
            }
        }
        return out.toByteArray();
    }

    /**
     * Assembles the full wire form of {@code frame}: control byte + (randomized) data
     * + big-endian CRC, stuffed, terminated with the Flag.
     *
     * @param frame the frame to emit, never {@code null}
     * @return the wire bytes including the trailing Flag
     */
    static byte[] emit(AshFrame frame) {
        int control;
        byte[] data;
        switch (frame) {
            case AshFrame.Data d -> {
                control = (d.frameNumber() << 4)
                        | (d.retransmitted() ? 0x08 : 0)
                        | d.ackNumber();
                data = randomize(d.payload());
            }
            case AshFrame.Ack a -> {
                control = 0x80 | (a.notReady() ? 0x08 : 0) | a.ackNumber();
                data = new byte[0];
            }
            case AshFrame.Nak n -> {
                control = 0xA0 | (n.notReady() ? 0x08 : 0) | n.ackNumber();
                data = new byte[0];
            }
            case AshFrame.Rst r -> {
                control = CONTROL_RST;
                data = new byte[0];
            }
            case AshFrame.RstAck ra -> {
                control = CONTROL_RSTACK;
                data = new byte[] {(byte) ra.version(), (byte) ra.resetCode()};
            }
            case AshFrame.Error e -> {
                control = CONTROL_ERROR;
                data = new byte[] {(byte) e.version(), (byte) e.errorCode()};
            }
        }
        byte[] unstuffed = new byte[1 + data.length + 2];
        unstuffed[0] = (byte) control;
        System.arraycopy(data, 0, unstuffed, 1, data.length);
        byte[] crcInput = new byte[1 + data.length];
        System.arraycopy(unstuffed, 0, crcInput, 0, crcInput.length);
        int crc = crcCcitt(crcInput);
        unstuffed[unstuffed.length - 2] = (byte) ((crc >> 8) & 0xFF);
        unstuffed[unstuffed.length - 1] = (byte) (crc & 0xFF);

        byte[] stuffed = stuff(unstuffed);
        byte[] wire = new byte[stuffed.length + 1];
        System.arraycopy(stuffed, 0, wire, 0, stuffed.length);
        wire[wire.length - 1] = (byte) FLAG;
        return wire;
    }

    /**
     * Parses one accumulated frame body (the still-stuffed bytes between Flags, as
     * produced by {@link AshFrameAccumulator}).
     *
     * @param stuffedBody the stuffed frame bytes without the Flag, never {@code null}
     * @return the parse result — never throws for corrupt input
     */
    static ParseResult parse(byte[] stuffedBody) {
        byte[] raw = destuff(stuffedBody);
        if (raw == null) {
            return new ParseResult.Rejected("malformed escape sequence: trailing 0x7D");
        }
        if (raw.length < 3) {
            return new ParseResult.Rejected(
                    "frame too short: " + raw.length + " bytes, minimum 3");
        }
        byte[] covered = new byte[raw.length - 2];
        System.arraycopy(raw, 0, covered, 0, covered.length);
        int expected = crcCcitt(covered);
        int actual = ((raw[raw.length - 2] & 0xFF) << 8) | (raw[raw.length - 1] & 0xFF);
        if (expected != actual) {
            return new ParseResult.Rejected(String.format(
                    "CRC mismatch: computed 0x%04X, received 0x%04X", expected, actual));
        }
        int control = raw[0] & 0xFF;
        byte[] data = new byte[covered.length - 1];
        System.arraycopy(raw, 1, data, 0, data.length);

        if ((control & 0x80) == 0) {
            return new ParseResult.Parsed(new AshFrame.Data(
                    (control >> 4) & 0x07,
                    control & 0x07,
                    (control & 0x08) != 0,
                    randomize(data)));
        }
        if (control == CONTROL_RST) {
            return new ParseResult.Parsed(new AshFrame.Rst());
        }
        if (control == CONTROL_RSTACK) {
            if (data.length < 2) {
                return new ParseResult.Rejected("RSTACK data field too short");
            }
            return new ParseResult.Parsed(
                    new AshFrame.RstAck(data[0] & 0xFF, data[1] & 0xFF));
        }
        if (control == CONTROL_ERROR) {
            if (data.length < 2) {
                return new ParseResult.Rejected("ERROR data field too short");
            }
            return new ParseResult.Parsed(
                    new AshFrame.Error(data[0] & 0xFF, data[1] & 0xFF));
        }
        // Deliberate Postel-style tolerance: bits 7-5 classify ACK/NAK (AN706
        // leaves bit 4 reserved-zero; a set bit 4 on a CRC-valid frame is accepted
        // rather than rejected).
        if ((control & 0xE0) == 0x80) {
            return new ParseResult.Parsed(
                    new AshFrame.Ack(control & 0x07, (control & 0x08) != 0));
        }
        if ((control & 0xE0) == 0xA0) {
            return new ParseResult.Parsed(
                    new AshFrame.Nak(control & 0x07, (control & 0x08) != 0));
        }
        return new ParseResult.Rejected(
                String.format("unknown control byte 0x%02X", control));
    }
}
