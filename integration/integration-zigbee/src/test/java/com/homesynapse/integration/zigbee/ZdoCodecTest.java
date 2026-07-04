/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ZdoCodec} tests: ZDP request encoding and response parsing for the
 * interview steps (Node_Desc 0x0002/0x8002, Active_EP 0x0005/0x8005,
 * Simple_Desc 0x0004/0x8004). Byte layouts follow the ZDP specification:
 * little-endian multi-byte fields, a ZDO transaction sequence number first.
 */
class ZdoCodecTest {

    @Nested
    @DisplayName("request encoding")
    class Requests {

        @Test
        @DisplayName("Node_Desc_req: [tsn][nwk LE]")
        void nodeDescRequest() {
            assertThat(ZdoCodec.encodeAddressRequest(0x42, 0x6B9A))
                    .containsExactly(0x42, 0x9A, 0x6B);
        }

        @Test
        @DisplayName("Simple_Desc_req: [tsn][nwk LE][endpoint]")
        void simpleDescRequest() {
            assertThat(ZdoCodec.encodeSimpleDescriptorRequest(0x17, 0x260F, 11))
                    .containsExactly(0x17, 0x0F, 0x26, 0x0B);
        }
    }

    @Nested
    @DisplayName("Node_Desc_rsp parsing")
    class NodeDescriptorResponse {

        @Test
        @DisplayName("parses logical type, manufacturer code, buffer size, MAC capabilities")
        void parsesFields() {
            // tsn=1, status=0, nwk=0x6B9A, then the 13-byte node descriptor:
            // byte0 = logical type 2 (end device), byte1 = 0x40, byte2 = MAC cap 0x80,
            // mfr code 0x1286 LE, max buffer 82, then trailing descriptor bytes.
            byte[] message = {
                    0x01, 0x00, (byte) 0x9A, 0x6B,
                    0x02, 0x40, (byte) 0x80,
                    (byte) 0x86, 0x12,
                    82,
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
            };

            Optional<NodeDescriptor> descriptor =
                    ZdoCodec.parseNodeDescriptorResponse(message);

            assertThat(descriptor).isPresent();
            assertThat(descriptor.get().deviceType()).isEqualTo(2);
            assertThat(descriptor.get().manufacturerCode()).isEqualTo(0x1286);
            assertThat(descriptor.get().maxBufferSize()).isEqualTo(82);
            assertThat(descriptor.get().macCapabilityFlags()).isEqualTo(0x80);
        }

        @Test
        @DisplayName("a non-zero ZDP status is empty, never an exception")
        void nonZeroStatusIsEmpty() {
            byte[] failure = {0x01, (byte) 0x80, (byte) 0x9A, 0x6B};

            assertThat(ZdoCodec.parseNodeDescriptorResponse(failure)).isEmpty();
        }

        @Test
        @DisplayName("a truncated response is empty, never an exception")
        void truncatedIsEmpty() {
            assertThat(ZdoCodec.parseNodeDescriptorResponse(new byte[] {0x01, 0x00}))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Active_EP_rsp parsing")
    class ActiveEndpointsResponse {

        @Test
        @DisplayName("parses the endpoint list (the Hue 11 + 242 case)")
        void parsesEndpointList() {
            byte[] message = {
                    0x02, 0x00, 0x0F, 0x26,
                    0x02,
                    0x0B, (byte) 0xF2
            };

            Optional<List<Integer>> endpoints =
                    ZdoCodec.parseActiveEndpointsResponse(message);

            assertThat(endpoints).contains(List.of(11, 242));
        }

        @Test
        @DisplayName("failure status is empty")
        void failureStatusIsEmpty() {
            assertThat(ZdoCodec.parseActiveEndpointsResponse(
                    new byte[] {0x02, (byte) 0x81, 0x0F, 0x26})).isEmpty();
        }
    }

    @Nested
    @DisplayName("Simple_Desc_rsp parsing")
    class SimpleDescriptorResponse {

        @Test
        @DisplayName("parses endpoint, profile, device type, and both cluster lists")
        void parsesDescriptor() {
            // tsn, status 0, nwk 0x6B9A, length, then: ep=1, profile 0x0104 LE,
            // deviceType 0x0107 LE, version 1, inCount 3 [0x0000, 0x0001, 0x0406],
            // outCount 1 [0x0019].
            byte[] message = {
                    0x03, 0x00, (byte) 0x9A, 0x6B,
                    16,
                    0x01,
                    0x04, 0x01,
                    0x07, 0x01,
                    0x01,
                    0x03, 0x00, 0x00, 0x01, 0x00, 0x06, 0x04,
                    0x01, 0x19, 0x00
            };

            Optional<EndpointDescriptor> descriptor =
                    ZdoCodec.parseSimpleDescriptorResponse(message);

            assertThat(descriptor).isPresent();
            assertThat(descriptor.get().endpointId()).isEqualTo(1);
            assertThat(descriptor.get().profileId()).isEqualTo(0x0104);
            assertThat(descriptor.get().deviceTypeId()).isEqualTo(0x0107);
            assertThat(descriptor.get().inputClusters())
                    .containsExactly(0x0000, 0x0001, 0x0406);
            assertThat(descriptor.get().outputClusters()).containsExactly(0x0019);
        }

        @Test
        @DisplayName("truncated cluster lists are empty, never an exception")
        void truncatedClusterListIsEmpty() {
            byte[] message = {
                    0x03, 0x00, (byte) 0x9A, 0x6B,
                    16,
                    0x01, 0x04, 0x01, 0x07, 0x01, 0x01,
                    0x03, 0x00, 0x00
            };

            assertThat(ZdoCodec.parseSimpleDescriptorResponse(message)).isEmpty();
        }
    }

    @Test
    @DisplayName("Device_annce parsing: [tsn][nwk LE][ieee LE][capability]")
    void deviceAnnounceParsing() {
        byte[] message = {
                0x07,
                (byte) 0x9A, 0x6B,
                0x78, 0x56, 0x34, 0x12, 0x00, 0x4B, 0x12, 0x00,
                (byte) 0x80
        };

        Optional<ZdoCodec.DeviceAnnounce> announce =
                ZdoCodec.parseDeviceAnnounce(message);

        assertThat(announce).isPresent();
        assertThat(announce.get().networkAddress()).isEqualTo(0x6B9A);
        assertThat(announce.get().ieeeAddress().value())
                .isEqualTo(0x00124B0012345678L);
        assertThat(announce.get().macCapabilityFlags()).isEqualTo(0x80);
    }
}
