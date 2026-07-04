/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A deterministic scripted NCP simulator for protocol-level tests: parses the host's
 * ASH frames (via the same codec the production stack uses, whose correctness is
 * separately pinned by byte vectors in {@code AshCodecTest}), auto-answers RST with
 * RSTACK, ACKs every DATA frame, and delegates EZSP command payloads to a pluggable
 * handler that returns zero or more EZSP response/callback frames.
 *
 * <p>Handler contract: input is the raw EZSP command frame bytes (sequence at offset
 * 0); output frames are each wrapped by the fake NCP into properly numbered ASH DATA
 * frames. A {@code null} return simulates NCP silence (the timeout path — the fake
 * channel advances the test clock).
 */
final class FakeNcp implements Function<byte[], List<byte[]>> {

    private final AshFrameAccumulator accumulator = new AshFrameAccumulator();
    private final List<byte[]> receivedEzspCommands = new ArrayList<>();

    private Function<byte[], List<byte[]>> ezspHandler;
    private int ncpFrameNumber; // our next outbound DATA frame number
    private int hostNext;       // the next host frame number we expect
    private boolean commandInFlight;
    private boolean overlapDetected;
    private int rstackResetCode = 0x02; // power-on

    /** Creates a fake NCP awaiting an RST handshake. */
    FakeNcp() {
    }

    /** Sets the EZSP command handler (command frame bytes → response frames). */
    void onEzspCommand(Function<byte[], List<byte[]>> handler) {
        this.ezspHandler = handler;
    }

    /** Sets the reset code the RSTACK will carry. */
    void rstackResetCode(int code) {
        this.rstackResetCode = code;
    }

    /** Returns every EZSP command frame received, in order. */
    List<byte[]> receivedEzspCommands() {
        return receivedEzspCommands;
    }

    /**
     * True if a second command arrived while one was being handled (D-M92-5).
     *
     * <p>A coarse tripwire: {@code commandInFlight} spans one synchronous
     * {@code apply(...)} only, so it trips on reentrant handler paths, not on all
     * conceivable pipelining bugs — the load-bearing single-in-flight proof is the
     * pipeline lock's structural serialization of every transport access.
     */
    boolean overlapDetected() {
        return overlapDetected;
    }

    @Override
    public List<byte[]> apply(byte[] written) {
        List<byte[]> responses = new ArrayList<>();
        for (byte[] body : accumulator.accept(written, written.length)) {
            if (!(AshCodec.parse(body) instanceof AshCodec.ParseResult.Parsed p)) {
                continue;
            }
            switch (p.frame()) {
                case AshFrame.Rst rst -> {
                    ncpFrameNumber = 0;
                    hostNext = 0;
                    responses.add(AshCodec.emit(
                            new AshFrame.RstAck(0x02, rstackResetCode)));
                }
                case AshFrame.Data data -> {
                    if (commandInFlight) {
                        overlapDetected = true;
                    }
                    commandInFlight = true;
                    try {
                        if (data.frameNumber() == hostNext) {
                            hostNext = (hostNext + 1) & 0x07;
                        }
                        // Acknowledge the host DATA frame.
                        responses.add(AshCodec.emit(new AshFrame.Ack(hostNext, false)));
                        byte[] command = data.payload();
                        receivedEzspCommands.add(command);
                        if (ezspHandler != null) {
                            List<byte[]> ezspFrames = ezspHandler.apply(command);
                            if (ezspFrames != null) {
                                for (byte[] ezsp : ezspFrames) {
                                    responses.add(AshCodec.emit(new AshFrame.Data(
                                            ncpFrameNumber, hostNext, false, ezsp)));
                                    ncpFrameNumber = (ncpFrameNumber + 1) & 0x07;
                                }
                            }
                        }
                    } finally {
                        commandInFlight = false;
                    }
                }
                case AshFrame.Ack ack -> {
                    // Host acknowledged our DATA: nothing to do.
                }
                case AshFrame.Nak nak -> {
                    // The M9.2 protocol tests never NAK the NCP.
                }
                case AshFrame.RstAck rstAck -> {
                    // Host never sends RSTACK.
                }
                case AshFrame.Error error -> {
                    // Host never sends ERROR.
                }
            }
        }
        return responses;
    }
}
