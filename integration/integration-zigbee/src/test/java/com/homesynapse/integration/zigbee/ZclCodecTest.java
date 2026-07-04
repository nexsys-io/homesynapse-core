/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ZclCodec} tests: ZCL frame-header parsing, Report Attributes /
 * Read Attributes Response record decoding, and Read Attributes encoding —
 * the shared surface beneath the interview Basic read and the ingestion path.
 */
class ZclCodecTest {

    @Nested
    @DisplayName("header parsing")
    class Headers {

        @Test
        @DisplayName("a global non-manufacturer frame: [fc][tsn][cmd]")
        void globalHeader() {
            byte[] payload = {0x18, 0x2A, 0x0A, 0x00, 0x00, 0x18, 0x01};

            Optional<ZclCodec.ZclHeader> header = ZclCodec.parseHeader(payload);

            assertThat(header).isPresent();
            assertThat(header.get().commandId()).isEqualTo(0x0A);
            assertThat(header.get().transactionSequence()).isEqualTo(0x2A);
            assertThat(header.get().clusterSpecific()).isFalse();
            assertThat(header.get().manufacturerCode()).isEqualTo(-1);
            assertThat(header.get().payloadOffset()).isEqualTo(3);
        }

        @Test
        @DisplayName("a manufacturer-specific frame carries the code before the tsn")
        void manufacturerSpecificHeader() {
            byte[] payload = {0x1C, 0x5F, 0x11, 0x2A, 0x0A};

            Optional<ZclCodec.ZclHeader> header = ZclCodec.parseHeader(payload);

            assertThat(header).isPresent();
            assertThat(header.get().manufacturerCode()).isEqualTo(0x115F);
            assertThat(header.get().transactionSequence()).isEqualTo(0x2A);
            assertThat(header.get().payloadOffset()).isEqualTo(5);
        }

        @Test
        @DisplayName("a cluster-specific frame is flagged (the IAS notification path)")
        void clusterSpecificHeader() {
            byte[] payload = {0x19, 0x2A, 0x00};

            Optional<ZclCodec.ZclHeader> header = ZclCodec.parseHeader(payload);

            assertThat(header).isPresent();
            assertThat(header.get().clusterSpecific()).isTrue();
            assertThat(header.get().commandId()).isEqualTo(0x00);
        }

        @Test
        @DisplayName("a truncated header is empty, never an exception")
        void truncatedHeaderIsEmpty() {
            assertThat(ZclCodec.parseHeader(new byte[] {0x18})).isEmpty();
        }
    }

    @Nested
    @DisplayName("attribute record decoding")
    class AttributeRecords {

        @Test
        @DisplayName("Report Attributes: bitmap8 occupancy (the SNZB walk-test shape)")
        void occupancyReport() {
            // [attr 0x0000 LE][type 0x18 bitmap8][value 1]
            byte[] payload = {0x18, 0x2A, 0x0A, 0x00, 0x00, 0x18, 0x01};

            Map<Integer, Object> attributes =
                    ZclCodec.parseAttributeReports(payload, 3);

            assertThat(attributes).containsExactly(Map.entry(0x0000, 1L));
        }

        @Test
        @DisplayName("uint16 color temperature mireds")
        void colorTemperatureReport() {
            // attr 0x0007, type 0x21 uint16, value 447 = 0x01BF LE
            byte[] payload = {0x18, 0x2A, 0x0A, 0x07, 0x00, 0x21, (byte) 0xBF, 0x01};

            Map<Integer, Object> attributes =
                    ZclCodec.parseAttributeReports(payload, 3);

            assertThat(attributes).containsExactly(Map.entry(0x0007, 447L));
        }

        @Test
        @DisplayName("boolean on/off")
        void booleanReport() {
            byte[] payload = {0x18, 0x2A, 0x0A, 0x00, 0x00, 0x10, 0x01};

            Map<Integer, Object> attributes =
                    ZclCodec.parseAttributeReports(payload, 3);

            assertThat(attributes).containsExactly(Map.entry(0x0000, Boolean.TRUE));
        }

        @Test
        @DisplayName("multiple records in one report")
        void multipleRecords() {
            byte[] payload = {
                    0x18, 0x2A, 0x0A,
                    0x00, 0x00, 0x20, (byte) 0xC8,
                    0x21, 0x00, 0x20, 0x1C
            };

            Map<Integer, Object> attributes =
                    ZclCodec.parseAttributeReports(payload, 3);

            assertThat(attributes)
                    .containsEntry(0x0000, 200L)
                    .containsEntry(0x0021, 28L);
        }

        @Test
        @DisplayName("Read Attributes Response: per-record status; failed records skipped")
        void readAttributesResponse() {
            // record 1: attr 0x0004, status 0, type 0x42 string len 7 "eWeLink"
            // record 2: attr 0x0099, status 0x86 UNSUPPORTED (no type/value follows)
            // record 3: attr 0x0007, status 0, type 0x30 enum8, value 3
            byte[] payload = {
                    0x18, 0x2A, 0x01,
                    0x04, 0x00, 0x00, 0x42, 7, 'e', 'W', 'e', 'L', 'i', 'n', 'k',
                    (byte) 0x99, 0x00, (byte) 0x86,
                    0x07, 0x00, 0x00, 0x30, 0x03
            };

            Map<Integer, Object> attributes =
                    ZclCodec.parseReadAttributesResponse(payload, 3);

            assertThat(attributes)
                    .containsEntry(0x0004, "eWeLink")
                    .containsEntry(0x0007, 3L)
                    .doesNotContainKey(0x0099);
        }

        @Test
        @DisplayName("an unknown attribute data type stops parsing gracefully")
        void unknownTypeStopsGracefully() {
            byte[] payload = {
                    0x18, 0x2A, 0x0A,
                    0x00, 0x00, 0x20, 0x64,
                    0x01, 0x00, (byte) 0xEE, 0x01, 0x02
            };

            Map<Integer, Object> attributes =
                    ZclCodec.parseAttributeReports(payload, 3);

            assertThat(attributes).containsExactly(Map.entry(0x0000, 100L));
        }

        @Test
        @DisplayName("int16 decodes signed (temperature semantics)")
        void signedInt16() {
            // attr 0x0000, type 0x29 int16, value -500 = 0xFE0C LE
            byte[] payload = {0x18, 0x2A, 0x0A, 0x00, 0x00, 0x29, 0x0C, (byte) 0xFE};

            Map<Integer, Object> attributes =
                    ZclCodec.parseAttributeReports(payload, 3);

            assertThat(attributes).containsExactly(Map.entry(0x0000, -500L));
        }
    }

    @Test
    @DisplayName("Read Attributes encoding: [fc 0x00][tsn][cmd 0x00][attr ids LE]")
    void readAttributesEncoding() {
        byte[] frame = ZclCodec.encodeReadAttributes(0x2A,
                new int[] {0x0004, 0x0005, 0x0007, 0x4000});

        assertThat(frame).containsExactly(
                0x00, 0x2A, 0x00,
                0x04, 0x00, 0x05, 0x00, 0x07, 0x00, 0x00, 0x40);
    }

    @Test
    @DisplayName("F-9: a bool marker other than 0x00/0x01 is NOT a value observation — the record drops, the rest of the frame survives")
    void invalidBoolMarker_recordDroppedFrameKept() {
        // attr 0x0000 bool with the 0xFF invalid marker, then attr 0x4001 bool true:
        // the first record is dropped (never an observation — it could otherwise
        // false-CONFIRM a turn_on); the second still decodes.
        byte[] payload = {
                0x18, 0x2A, 0x0A,
                0x00, 0x00, 0x10, (byte) 0xFF,
                0x01, 0x40, 0x10, 0x01
        };

        Map<Integer, Object> attributes =
                ZclCodec.parseAttributeReports(payload, 3);

        assertThat(attributes).containsExactly(Map.entry(0x4001, Boolean.TRUE));
    }

    @Test
    @DisplayName("F-9: an invalid bool marker in a Read Attributes Response drops the same way")
    void invalidBoolMarker_readResponse_dropped() {
        // attr 0x0000, status SUCCESS, bool 0x02 (invalid marker).
        byte[] payload = {0x18, 0x2A, 0x01, 0x00, 0x00, 0x00, 0x10, 0x02};

        Map<Integer, Object> attributes =
                ZclCodec.parseReadAttributesResponse(payload, 3);

        assertThat(attributes).isEmpty();
    }
}
