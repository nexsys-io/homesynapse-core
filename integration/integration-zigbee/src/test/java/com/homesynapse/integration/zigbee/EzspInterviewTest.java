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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EzspCoordinatorProtocol#interview(IEEEAddress)} end-to-end over the full
 * deterministic stack (protocol → transport → ASH → {@link FakeNcp}): the ZDO
 * lookup/descriptor walk, the EP-11 Basic read, the PARTIAL path when a sleepy
 * device stops answering, and the §G pending-callback bound.
 */
class EzspInterviewTest {

    private static final long HUE_IEEE = 0x0017880109AB12CDL;
    private static final int HUE_NWK = 0x260F;

    private TestClock clock;
    private FakeSerialByteChannel channel;
    private FakeNcp ncp;
    private EzspCoordinatorProtocol protocol;
    private boolean silentBasicRead;

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        channel = new FakeSerialByteChannel(clock);
        ncp = new FakeNcp();
        channel.onWrite(ncp);
        ncp.onEzspCommand(this::interviewCapableHandler);
        EzspAshTransport transport = new EzspAshTransport(clock, arg -> channel);
        transport.open(new Object());
        protocol = new EzspCoordinatorProtocol(transport,
                new RecordingNetworkParameterStore(), clock);
        try {
            protocol.startSession();
        } catch (PermanentIntegrationException e) {
            throw new AssertionError("startSession failed unexpectedly", e);
        }
    }

    @Test
    @DisplayName("interview walks lookup → node-desc → active-EPs → simple-desc → Basic and returns COMPLETE")
    void interviewCompletes() {
        InterviewResult result = protocol.interview(new IEEEAddress(HUE_IEEE));

        assertThat(result.interviewStatus()).isEqualTo(InterviewStatus.COMPLETE);
        assertThat(result.networkAddress()).isEqualTo(HUE_NWK);
        assertThat(result.manufacturerName()).isEqualTo("Signify Netherlands B.V.");
        assertThat(result.modelIdentifier()).isEqualTo("LCA017");
        assertThat(result.powerSource()).isEqualTo(1);
        assertThat(result.nodeDescriptor().manufacturerCode()).isEqualTo(0x100B);
        assertThat(result.endpoints()).hasSize(1);
        EndpointDescriptor endpoint = result.endpoints().get(0);
        assertThat(endpoint.endpointId()).isEqualTo(11);
        assertThat(endpoint.deviceTypeId()).isEqualTo(0x010D);
        assertThat(endpoint.inputClusters()).contains(0x0006, 0x0008, 0x0300);
    }

    @Test
    @DisplayName("the Green Power endpoint 242 is never descriptor-queried")
    void greenPowerNeverQueried() {
        protocol.interview(new IEEEAddress(HUE_IEEE));

        for (byte[] command : ncp.receivedEzspCommands()) {
            if (isLegacyVersion(command)) {
                continue;
            }
            if (frameIdOf(command) == EzspCoordinatorProtocol.FRAME_SEND_UNICAST) {
                byte[] parameters = extendedParameters(command);
                int cluster = (parameters[5] & 0xFF) | ((parameters[6] & 0xFF) << 8);
                if (cluster == ZdoCodec.CLUSTER_SIMPLE_DESC_REQ) {
                    int requestedEndpoint = parameters[19] & 0xFF;
                    assertThat(requestedEndpoint).isNotEqualTo(242);
                }
            }
        }
    }

    @Test
    @DisplayName("a silent Basic read yields PARTIAL with empty identity — through the frozen surface")
    void silentBasicReadYieldsPartial() {
        silentBasicRead = true;

        InterviewResult result = protocol.interview(new IEEEAddress(HUE_IEEE));

        assertThat(result.interviewStatus()).isEqualTo(InterviewStatus.PARTIAL);
        assertThat(result.manufacturerName()).isEmpty();
        assertThat(result.endpoints()).hasSize(1);
    }

    @Test
    @DisplayName("§G: the pending-callback queue is bounded — overflow drops oldest and counts")
    void callbackQueueBounded() {
        ncp.onEzspCommand(command -> {
            if (isLegacyVersion(command)) {
                return List.of(new byte[] {
                        command[0], (byte) 0x80, 0x00, 13, 0x02, 0x30, 0x74});
            }
            if (frameIdOf(command) == 0x0005) { // nop: flood callbacks, then pong
                List<byte[]> frames = new ArrayList<>();
                for (int i = 0;
                        i < EzspCoordinatorProtocol.MAX_PENDING_CALLBACKS + 6; i++) {
                    frames.add(new byte[] {
                            0x00, (byte) 0x90, 0x01, 0x48, 0x00, 0x0B, -60});
                }
                frames.add(extendedResponse(command[0] & 0xFF, 0x0005, new byte[0]));
                return frames;
            }
            return List.of();
        });

        assertThat(protocol.ping()).isTrue();

        assertThat(protocol.drainPendingCallbacks())
                .hasSize(EzspCoordinatorProtocol.MAX_PENDING_CALLBACKS);
        assertThat(protocol.droppedCallbacks()).isEqualTo(6);
        assertThat(protocol.drainPendingCallbacks())
                .as("drain clears the queue")
                .isEmpty();
    }

    // ── the scripted NCP ────────────────────────────────────────────────────

    private List<byte[]> interviewCapableHandler(byte[] command) {
        if (isLegacyVersion(command)) {
            return List.of(new byte[] {
                    command[0], (byte) 0x80, 0x00, 13, 0x02, 0x30, 0x74});
        }
        int seq = command[0] & 0xFF;
        int frameId = frameIdOf(command);
        if (frameId == EzspCoordinatorProtocol.FRAME_LOOKUP_NODE_ID_BY_EUI64) {
            return List.of(extendedResponse(seq, frameId,
                    new byte[] {(byte) (HUE_NWK & 0xFF), (byte) (HUE_NWK >> 8)}));
        }
        if (frameId == EzspCoordinatorProtocol.FRAME_SEND_UNICAST) {
            byte[] parameters = extendedParameters(command);
            int profile = (parameters[3] & 0xFF) | ((parameters[4] & 0xFF) << 8);
            int cluster = (parameters[5] & 0xFF) | ((parameters[6] & 0xFF) << 8);
            byte[] message = new byte[parameters[15] & 0xFF];
            System.arraycopy(parameters, 16, message, 0, message.length);
            // ZDO carries the tsn first; a ZCL frame carries it after the
            // frame-control byte.
            int tsn = profile == EzspCoordinatorProtocol.ZDO_PROFILE_ID
                    ? message[0] & 0xFF : message[1] & 0xFF;

            List<byte[]> frames = new ArrayList<>();
            frames.add(extendedResponse(seq, frameId,
                    new byte[] {0x00, parameters[13]}));
            byte[] reply = zdoOrZclReply(profile, cluster, message, tsn);
            if (reply != null) {
                frames.add(incomingMessage(profile,
                        profile == EzspCoordinatorProtocol.ZDO_PROFILE_ID
                                ? cluster | 0x8000 : cluster,
                        reply));
            }
            return frames;
        }
        return List.of();
    }

    private byte[] zdoOrZclReply(int profile, int cluster, byte[] request, int tsn) {
        if (profile == EzspCoordinatorProtocol.ZDO_PROFILE_ID) {
            return switch (cluster) {
                case ZdoCodec.CLUSTER_NODE_DESC_REQ -> new byte[] {
                        (byte) tsn, 0x00, (byte) (HUE_NWK & 0xFF), (byte) (HUE_NWK >> 8),
                        0x01, 0x40, (byte) 0x8E,
                        0x0B, 0x10,
                        82,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
                case ZdoCodec.CLUSTER_ACTIVE_EP_REQ -> new byte[] {
                        (byte) tsn, 0x00, (byte) (HUE_NWK & 0xFF), (byte) (HUE_NWK >> 8),
                        0x02, 0x0B, (byte) 0xF2};
                case ZdoCodec.CLUSTER_SIMPLE_DESC_REQ -> new byte[] {
                        (byte) tsn, 0x00, (byte) (HUE_NWK & 0xFF), (byte) (HUE_NWK >> 8),
                        18,
                        0x0B,
                        0x04, 0x01,
                        0x0D, 0x01,
                        0x01,
                        0x04, 0x00, 0x00, 0x06, 0x00, 0x08, 0x00, 0x00, 0x03,
                        0x01, 0x19, 0x00};
                default -> null;
            };
        }
        if (profile == EzspCoordinatorProtocol.HA_PROFILE_ID && cluster == 0x0000) {
            if (silentBasicRead) {
                return null;
            }
            String manufacturer = "Signify Netherlands B.V.";
            String model = "LCA017";
            String build = "0x01000D08";
            // ZCL header (3) + string records (attr 2 + status 1 + type 1 +
            // len 1 + chars) + the enum8 record (attr 2 + status 1 + type 1 + 1).
            byte[] reply = new byte[3
                    + 5 + manufacturer.length()
                    + 5 + model.length()
                    + 5
                    + 5 + build.length()];
            int i = 0;
            reply[i++] = 0x18;
            reply[i++] = (byte) tsn;
            reply[i++] = 0x01;
            i = stringRecord(reply, i, 0x0004, manufacturer);
            i = stringRecord(reply, i, 0x0005, model);
            reply[i++] = 0x07;
            reply[i++] = 0x00;
            reply[i++] = 0x00;
            reply[i++] = 0x30;
            reply[i++] = 0x01;
            stringRecord(reply, i, 0x4000, build);
            return reply;
        }
        return null;
    }

    private static int stringRecord(byte[] buffer, int offset, int attributeId,
            String value) {
        buffer[offset++] = (byte) (attributeId & 0xFF);
        buffer[offset++] = (byte) ((attributeId >> 8) & 0xFF);
        buffer[offset++] = 0x00;
        buffer[offset++] = 0x42;
        buffer[offset++] = (byte) value.length();
        for (char c : value.toCharArray()) {
            buffer[offset++] = (byte) c;
        }
        return offset;
    }

    private static byte[] incomingMessage(int profile, int cluster, byte[] message) {
        byte[] parameters = new byte[19 + message.length];
        parameters[0] = 0x00;
        parameters[1] = (byte) (profile & 0xFF);
        parameters[2] = (byte) ((profile >> 8) & 0xFF);
        parameters[3] = (byte) (cluster & 0xFF);
        parameters[4] = (byte) ((cluster >> 8) & 0xFF);
        parameters[5] = profile == EzspCoordinatorProtocol.ZDO_PROFILE_ID
                ? 0 : (byte) 11;
        parameters[6] = profile == EzspCoordinatorProtocol.ZDO_PROFILE_ID
                ? 0 : (byte) 1;
        parameters[12] = (byte) 176;
        parameters[13] = (byte) -56;
        parameters[14] = (byte) (HUE_NWK & 0xFF);
        parameters[15] = (byte) (HUE_NWK >> 8);
        parameters[16] = (byte) 0xFF;
        parameters[17] = (byte) 0xFF;
        parameters[18] = (byte) message.length;
        System.arraycopy(message, 0, parameters, 19, message.length);

        byte[] frame = new byte[5 + parameters.length];
        frame[0] = 0x00;
        frame[1] = (byte) 0x90;
        frame[2] = 0x01;
        frame[3] = (byte) EzspCoordinatorProtocol.FRAME_INCOMING_MESSAGE_HANDLER;
        frame[4] = 0x00;
        System.arraycopy(parameters, 0, frame, 5, parameters.length);
        return frame;
    }

    private static boolean isLegacyVersion(byte[] command) {
        return command.length == 4 && command[1] == 0x00 && command[2] == 0x00;
    }

    private static int frameIdOf(byte[] extendedCommand) {
        return (extendedCommand[3] & 0xFF) | ((extendedCommand[4] & 0xFF) << 8);
    }

    private static byte[] extendedParameters(byte[] extendedCommand) {
        byte[] parameters = new byte[extendedCommand.length - 5];
        System.arraycopy(extendedCommand, 5, parameters, 0, parameters.length);
        return parameters;
    }

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
