/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

/**
 * Decoded ASH (Asynchronous Serial Host) frame — the data-link layer beneath EZSP
 * (AN706, Doc 08 §3.3).
 *
 * <p>The six variants mirror AN706's control-byte taxonomy. Only {@link Data} carries a
 * payload subject to randomization; {@link RstAck} and {@link Error} carry a two-byte
 * un-randomized data field of {@code [ashVersion, code]}.
 *
 * <p>Thread-safe: immutable variants ({@code Data} defensively copies its payload).
 *
 * @see AshCodec
 * @see AshSession
 */
sealed interface AshFrame {

    /**
     * DATA frame — control byte {@code (frameNumber << 4) | (reTx << 3) | ackNumber}.
     *
     * @param frameNumber the 3-bit transmit sequence number (0–7)
     * @param ackNumber the 3-bit acknowledgement number: the frame number the sender
     *                  expects to receive NEXT (piggyback acknowledgement)
     * @param retransmitted {@code true} if the reTx bit is set (this is a retransmission)
     * @param payload the de-randomized EZSP frame bytes; defensively copied
     */
    record Data(int frameNumber, int ackNumber, boolean retransmitted, byte[] payload)
            implements AshFrame {

        /**
         * Creates a DATA frame with validation and defensive copy.
         *
         * @param frameNumber must be 0–7 (3-bit)
         * @param ackNumber must be 0–7 (3-bit)
         * @param retransmitted the reTx bit
         * @param payload the EZSP payload bytes (defensively copied), never {@code null}
         */
        public Data {
            if (frameNumber < 0 || frameNumber > 7) {
                throw new IllegalArgumentException(
                        "frameNumber must be 0-7, got " + frameNumber);
            }
            if (ackNumber < 0 || ackNumber > 7) {
                throw new IllegalArgumentException(
                        "ackNumber must be 0-7, got " + ackNumber);
            }
            payload = payload.clone();
        }

        /**
         * Returns a defensive copy of the payload bytes.
         *
         * @return a copy of the payload, never {@code null}
         */
        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }

    /**
     * ACK frame — control byte {@code 0b1000_0000 | ackNumber} (+ nRdy bit {@code 0x08}).
     *
     * @param ackNumber the frame number the sender expects next
     * @param notReady the nRdy flow-control bit
     */
    record Ack(int ackNumber, boolean notReady) implements AshFrame {
    }

    /**
     * NAK frame — control byte {@code 0b1010_0000 | ackNumber} (+ nRdy bit {@code 0x08}).
     * Signals the reject condition: the sender should retransmit from {@code ackNumber}.
     *
     * @param ackNumber the frame number the sender expects next
     * @param notReady the nRdy flow-control bit
     */
    record Nak(int ackNumber, boolean notReady) implements AshFrame {
    }

    /** RST frame — control byte {@code 0xC0}, empty data field. */
    record Rst() implements AshFrame {
    }

    /**
     * RSTACK frame — control byte {@code 0xC1}, data field {@code [version, resetCode]}.
     *
     * <p>The reset code discriminates flow-control mismatch from firmware crash and is
     * surfaced in diagnostics (Doc 08 §3.3).
     *
     * @param version the ASH protocol version (2 for ASHv2)
     * @param resetCode the NCP reset cause code
     */
    record RstAck(int version, int resetCode) implements AshFrame {
    }

    /**
     * ERROR frame — control byte {@code 0xC2}, data field {@code [version, errorCode]}.
     * The NCP is in the FAILED state and must be reset.
     *
     * @param version the ASH protocol version
     * @param errorCode the NCP error code
     */
    record Error(int version, int errorCode) implements AshFrame {
    }
}
