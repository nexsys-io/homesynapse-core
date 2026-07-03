/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Accumulates raw serial bytes into complete (still-stuffed) ASH frame bodies.
 *
 * <p>Handles the AN706 stream-level control bytes: the Flag ({@code 0x7E}) delimits a
 * frame; a Substitute ({@code 0x18}) invalidates the in-progress frame until the next
 * Flag; a Cancel ({@code 0x1A}) discards the in-progress partial frame; XON/XOFF are
 * silently discarded. Escape sequences are left intact — {@link AshCodec#parse(byte[])}
 * destuffs.
 *
 * <p>Not thread-safe: single-threaded access by the transport thread.
 *
 * @see AshCodec
 * @see AshSession
 */
final class AshFrameAccumulator {

    /**
     * Defensive bound on a single stuffed frame body (review hardening H1,
     * 2026-07-03). The AN706 worst case is ~262 stuffed bytes (control 1 +
     * data 128 + CRC 2, fully escaped); anything larger is line noise or a
     * babbling NCP and must not grow the heap — the in-progress bytes are
     * discarded and the stream resynchronizes at the next Flag.
     */
    static final int MAX_STUFFED_BODY_BYTES = 512;

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(64);
    private boolean substituted;

    /** Creates an empty accumulator. */
    AshFrameAccumulator() {
    }

    /**
     * Consumes {@code length} bytes from {@code chunk}, returning any complete frame
     * bodies (stuffed bytes between Flags) that finished within this chunk.
     *
     * @param chunk the received bytes, never {@code null}
     * @param length the number of valid bytes at the start of {@code chunk}
     * @return zero or more complete stuffed frame bodies, in arrival order
     */
    List<byte[]> accept(byte[] chunk, int length) {
        List<byte[]> frames = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            int b = chunk[i] & 0xFF;
            if (b == AshCodec.FLAG) {
                if (substituted) {
                    substituted = false;
                    buffer.reset();
                } else if (buffer.size() > 0) {
                    frames.add(buffer.toByteArray());
                    buffer.reset();
                }
                // Back-to-back flags produce an empty body: ignored.
            } else if (b == AshCodec.CANCEL) {
                buffer.reset();
                substituted = false;
            } else if (b == AshCodec.SUBSTITUTE) {
                substituted = true;
            } else if (b == AshCodec.XON || b == AshCodec.XOFF) {
                // Software flow control bytes: discarded from the stream (AN706).
            } else if (substituted) {
                // The in-progress frame is already invalidated — skip until the
                // next Flag resynchronizes (also keeps invalidated garbage from
                // accumulating).
            } else if (buffer.size() >= MAX_STUFFED_BODY_BYTES) {
                // H1 bound: flagless byte storm — discard and resync at the
                // next Flag, exactly like a Substitute-invalidated frame.
                substituted = true;
                buffer.reset();
            } else {
                buffer.write(b);
            }
        }
        return frames;
    }

    /** Discards any partially accumulated frame state. */
    void reset() {
        buffer.reset();
        substituted = false;
    }
}
