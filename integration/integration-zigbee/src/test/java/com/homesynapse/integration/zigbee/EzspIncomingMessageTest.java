/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link EzspIncomingMessage#parse} against LITERAL bytes (LINK-READ T6): the
 * {@code incomingMessageHandler} (0x0045) layout of the class Javadoc —
 * {@code [type u8][EmberApsFrame 11][lastHopLqi u8][lastHopRssi s8][sender u16 LE]
 * [bindingIndex u8][addressIndex u8][length u8][message…]} — with the two link
 * bytes the hardware-free rig cannot contradict (IR-37): {@code lastHopLqi} is
 * UNSIGNED ({@code 0xFF → 255}) and {@code lastHopRssi} is SIGNED
 * ({@code 0xC4 → −60}).
 *
 * <p>The values are the spec layout carrying the bench-measured range (the
 * SNZB-03P walk-test fixture records LQI 160–164, RSSI −59 to −60 dBm); no raw
 * 0x0045 frame is filed in the corpus, so none is quoted here.
 *
 * <p><strong>What this pins:</strong> at HEAD the RSSI decode is
 * {@code int lastHopRssi = parameters[13];} — a {@code byte} widened to
 * {@code int}, which sign-extends. A future {@code & 0xFF} on that line is the
 * defect this test exists to catch: the wire byte {@code 0xC4} would then read
 * 196, which {@link LinkReading} refuses to construct.
 */
@DisplayName("EzspIncomingMessage — the 0x0045 literal-bytes parse (LINK-READ T6)")
class EzspIncomingMessageTest {

    /** A ZCL Report Attributes frame: occupancy (0x0406 attr 0x0000, map8) = 1. */
    private static final byte[] OCCUPANCY_REPORT =
            {0x18, 0x2A, 0x0A, 0x00, 0x00, 0x18, 0x01};

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    EzspIncomingMessageTest() {
    }

    /**
     * The callback parameters, byte by byte: HA profile 0x0104, cluster 0x0406,
     * source endpoint 1, destination endpoint 1, APS options 0x0140, group 0,
     * APS sequence 0x5C, LQI 0xFF, RSSI 0xC4, sender 0x6B9A, no binding, no
     * address-table index, 7 message bytes.
     */
    private static byte[] literalParameters() {
        return new byte[] {
            0x00,                                       // type: EMBER_INCOMING_UNICAST
            0x04, 0x01,                                 // profileId 0x0104 LE
            0x06, 0x04,                                 // clusterId 0x0406 LE
            0x01,                                       // sourceEndpoint
            0x01,                                       // destinationEndpoint
            0x40, 0x01,                                 // options LE
            0x00, 0x00,                                 // groupId LE
            0x5C,                                       // APS sequence
            (byte) 0xFF,                                // lastHopLqi   u8
            (byte) 0xC4,                                // lastHopRssi  s8
            (byte) 0x9A, 0x6B,                          // sender 0x6B9A LE
            (byte) 0xFF,                                // bindingIndex
            (byte) 0xFF,                                // addressIndex
            0x07,                                       // message length
            0x18, 0x2A, 0x0A, 0x00, 0x00, 0x18, 0x01    // the APS payload
        };
    }

    @Test
    @DisplayName("T6: the literal bytes parse field by field — lastHopLqi 0xFF → 255 "
            + "(unsigned) and lastHopRssi 0xC4 → −60 (signed)")
    void literalBytes_parseFieldByField_signedRssi() {
        EzspIncomingMessage message =
                EzspIncomingMessage.parse(literalParameters()).orElseThrow();

        assertThat(message.profileId()).isEqualTo(0x0104);
        assertThat(message.clusterId()).isEqualTo(0x0406);
        assertThat(message.sourceEndpoint()).isEqualTo(1);
        assertThat(message.destinationEndpoint()).isEqualTo(1);
        assertThat(message.lastHopLqi())
                .as("lastHopLqi is u8: 0xFF is 255, never −1")
                .isEqualTo(255);
        assertThat(message.lastHopRssi())
                .as("lastHopRssi is s8: 0xC4 is −60 dBm, never 196")
                .isEqualTo(-60);
        assertThat(message.sender()).isEqualTo(0x6B9A);
        assertThat(message.message()).containsExactly(OCCUPANCY_REPORT);
    }

    @Test
    @DisplayName("T6: the parsed pair constructs a reading; the UNSIGNED misdecode of "
            + "the same byte (196) does not — fromWire is empty and the constructor "
            + "throws (the wire domain is u8 / s8, never clamped)")
    void parsedPairConstructsAReading_theUnsignedMisdecodeDoesNot() {
        EzspIncomingMessage message =
                EzspIncomingMessage.parse(literalParameters()).orElseThrow();

        assertThat(LinkReading.fromWire(message.lastHopLqi(), message.lastHopRssi()))
                .contains(new LinkReading(255, -60));

        int misdecoded = 0xC4;   // what `parameters[13] & 0xFF` would hand over
        assertThat(LinkReading.fromWire(255, misdecoded)).isEmpty();
        assertThatThrownBy(() -> new LinkReading(255, misdecoded))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rssiDbm")
                .hasMessageContaining("196");
    }

    @Test
    @DisplayName("T6: the type carries the WIRE domain and nothing narrower — every "
            + "u8 / s8 corner constructs (a positive RSSI included); one past each "
            + "corner throws, and fromWire answers empty without throwing")
    void wireDomainCorners() {
        assertThat(new LinkReading(0, -128).rssiDbm()).isEqualTo(-128);
        assertThat(new LinkReading(255, 127).lqi()).isEqualTo(255);
        assertThat(LinkReading.fromWire(0, -128)).contains(new LinkReading(0, -128));
        assertThat(LinkReading.fromWire(255, 127)).contains(new LinkReading(255, 127));
        assertThat(LinkReading.fromWire(164, 5))
                .as("a positive s8 is legal on the wire — implausibility is the "
                        + "ingestion unit's WARN, never a type invariant")
                .contains(new LinkReading(164, 5));

        for (int[] outside : new int[][] {{-1, -60}, {256, -60}, {164, -129},
            {164, 128}}) {
            assertThat(LinkReading.fromWire(outside[0], outside[1]))
                    .as("fromWire(%d, %d)", outside[0], outside[1])
                    .isEmpty();
            assertThatThrownBy(() -> new LinkReading(outside[0], outside[1]))
                    .as("new LinkReading(%d, %d)", outside[0], outside[1])
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("T6: EVERY byte pair the wire can carry (256 × 256) parses to a pair "
            + "fromWire constructs — no value a frame can deliver reaches a throw")
    void everyWireBytePair_parsesToAConstructibleReading() {
        byte[] parameters = literalParameters();
        for (int lqiByte = 0; lqiByte <= 0xFF; lqiByte++) {
            for (int rssiByte = 0; rssiByte <= 0xFF; rssiByte++) {
                parameters[12] = (byte) lqiByte;
                parameters[13] = (byte) rssiByte;
                EzspIncomingMessage message =
                        EzspIncomingMessage.parse(parameters).orElseThrow();

                assertThat(LinkReading.fromWire(message.lastHopLqi(),
                        message.lastHopRssi()))
                        .as("wire bytes lqi=0x%02X rssi=0x%02X", lqiByte, rssiByte)
                        .contains(new LinkReading(lqiByte, (byte) rssiByte));
            }
        }
    }

    @Test
    @DisplayName("a truncated callback parses to empty — dropped at the boundary, "
            + "never an ingestion-loop exception")
    void truncatedParametersParseToEmpty() {
        byte[] full = literalParameters();
        byte[] headerOnly = Arrays.copyOf(full, 18);
        byte[] shortPayload = Arrays.copyOf(full, full.length - 1);

        assertThat(EzspIncomingMessage.parse(headerOnly)).isEmpty();
        assertThat(EzspIncomingMessage.parse(shortPayload)).isEmpty();
    }
}
