/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 *
 * <p><strong>Built-in configuration model (M9.4-NCFG):</strong> when the handler
 * returns an EMPTY list (no script for the frame), the fake NCP answers
 * {@code setConfigurationValue} (0x0053) with status SUCCESS — remembering the
 * written value — and {@code getConfigurationValue} (0x0052) with status SUCCESS
 * plus the last-written value u16 LE, so read-backs echo writes. An explicit
 * scripted response (e.g. a NAK) always wins, and a {@code null} return still
 * simulates silence. An RST clears the stored values — the real NCP resets to
 * firmware defaults on every launch.
 *
 * <p><strong>M9.4-RPT widening (declared):</strong> the built-in additionally
 * answers an unscripted {@code getEui64} (0x0026) with
 * {@link #COORDINATOR_EUI64} — the reporting binding fetches the coordinator
 * EUI64 before every Bind_req, and every shared-fixture session must have one.
 * Nothing else widened; scripted responses still win.
 */
final class FakeNcp implements Function<byte[], List<byte[]>> {

    /** The fake NCP's own EUI64 (the getEui64 built-in answer; chip-constant). */
    static final long COORDINATOR_EUI64 = 0x00124B00A1B2C3D4L;

    private final AshFrameAccumulator accumulator = new AshFrameAccumulator();
    private final List<byte[]> receivedEzspCommands = new ArrayList<>();
    private final Map<Integer, Integer> configValues = new HashMap<>();

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
                    configValues.clear();   // an NCP reset restores firmware defaults
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
                            if (ezspFrames != null && ezspFrames.isEmpty()) {
                                // Unscripted frame: the built-in config model may
                                // answer (M9.4-NCFG); anything else stays silent.
                                ezspFrames = builtInConfigResponse(command);
                            }
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

    /**
     * The built-in NCP model (M9.4-NCFG; getEui64 widened M9.4-RPT): answers
     * unscripted {@code setConfigurationValue}/{@code getConfigurationValue}
     * extended commands — echoing writes on read-back the way a live NCP that
     * applied them would — and {@code getEui64} with the chip-constant
     * {@link #COORDINATOR_EUI64}. Returns {@code null} for every other frame
     * (silence).
     */
    private List<byte[]> builtInConfigResponse(byte[] command) {
        if (command.length < 5) {
            return null;   // the legacy version frame is never a config command
        }
        int frameId = (command[3] & 0xFF) | ((command[4] & 0xFF) << 8);
        int seq = command[0] & 0xFF;
        if (frameId == EzspCoordinatorProtocol.FRAME_SET_CONFIGURATION_VALUE
                && command.length >= 8) {
            configValues.put(command[5] & 0xFF,
                    (command[6] & 0xFF) | ((command[7] & 0xFF) << 8));
            return List.of(extendedResponse(seq, frameId, new byte[] {0x00}));
        }
        if (frameId == EzspCoordinatorProtocol.FRAME_GET_CONFIGURATION_VALUE
                && command.length >= 6) {
            int value = configValues.getOrDefault(command[5] & 0xFF, 0);
            return List.of(extendedResponse(seq, frameId, new byte[] {
                    0x00, (byte) (value & 0xFF), (byte) ((value >> 8) & 0xFF)}));
        }
        if (frameId == EzspCoordinatorProtocol.FRAME_GET_EUI64) {
            byte[] eui64 = new byte[8];
            for (int i = 0; i < 8; i++) {
                eui64[i] = (byte) (COORDINATOR_EUI64 >> (8 * i));
            }
            return List.of(extendedResponse(seq, frameId, eui64));
        }
        return null;
    }

    /** One extended-format (v8+) EZSP response frame. */
    private static byte[] extendedResponse(int seq, int frameId, byte[] parameters) {
        byte[] frame = new byte[5 + parameters.length];
        frame[0] = (byte) seq;
        frame[1] = (byte) 0x80;
        frame[2] = 0x01;
        frame[3] = (byte) (frameId & 0xFF);
        frame[4] = (byte) ((frameId >> 8) & 0xFF);
        System.arraycopy(parameters, 0, frame, 5, parameters.length);
        return frame;
    }
}
