/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link EzspCodec} tests: the legacy (format-invariant, W8) version frames, the
 * extended v8+ frame layout with its frame-control bit semantics, and the D-M92-4
 * version-variant status width seam — synthetic v13 AND v14 response decodes.
 */
class EzspCodecTest {

    @Test
    @DisplayName("legacy version command encodes [seq, 0x00, 0x00, desiredVersion]")
    void legacyVersionCommand_encode() {
        assertThat(EzspCodec.encodeLegacyVersionCommand(0x00, 13))
                .containsExactly(0x00, 0x00, 0x00, 0x0D);
        assertThat(EzspCodec.encodeLegacyVersionCommand(0xA7, 14))
                .containsExactly(0xA7, 0x00, 0x00, 0x0E);
    }

    @Test
    @DisplayName("legacy version response decodes version, stack type, and the "
            + "little-endian stack version")
    void legacyVersionResponse_decode() {
        byte[] frame = {0x01, (byte) 0x80, 0x00, 0x0D, 0x02, 0x30, 0x74};

        EzspCodec.LegacyVersionResponse response =
                EzspCodec.decodeLegacyVersionResponse(frame);

        assertThat(response.sequence()).isEqualTo(1);
        assertThat(response.protocolVersion()).isEqualTo(13);
        assertThat(response.stackType()).isEqualTo(2);
        assertThat(response.stackVersion()).isEqualTo(0x7430);
    }

    @Test
    @DisplayName("legacy decode tolerates the callbackPending bit (0x04) on the "
            + "frame control — only the direction bit is required")
    void legacyVersionResponse_toleratesCallbackPending() {
        byte[] frame = {0x01, (byte) 0x84, 0x00, 0x0E, 0x02, 0x00, (byte) 0x80};

        assertThat(EzspCodec.decodeLegacyVersionResponse(frame).protocolVersion())
                .isEqualTo(14);
    }

    @Test
    @DisplayName("legacy decode rejects a command-direction frame")
    void legacyVersionResponse_commandDirection_rejected() {
        byte[] frame = {0x01, 0x00, 0x00, 0x0D, 0x02, 0x30, 0x74};

        assertThatThrownBy(() -> EzspCodec.decodeLegacyVersionResponse(frame))
                .isInstanceOf(EzspFormatException.class);
    }

    @Test
    @DisplayName("W8: the version frame codec is format-invariant — static, "
            + "independent of any negotiated-version instance")
    void legacyVersionFrames_formatInvariant() {
        // Instances for different negotiated versions exist, yet the version frame
        // bytes are produced by static methods with no version parameter at all.
        EzspCodec.forVersion(13);
        EzspCodec.forVersion(14);

        assertThat(EzspCodec.encodeLegacyVersionCommand(5, 13))
                .containsExactly(0x05, 0x00, 0x00, 0x0D);
    }

    @Test
    @DisplayName("extended v8+ command encodes [seq, 0x00, 0x01, idLow, idHigh, params]")
    void extendedCommand_encode() {
        byte[] frame = EzspCodec.forVersion(13)
                .encodeCommand(0x2A, 0x0022, new byte[] {0x3C});

        assertThat(frame).containsExactly(0x2A, 0x00, 0x01, 0x22, 0x00, 0x3C);
    }

    @Test
    @DisplayName("extended response decodes sequence, 16-bit LE frame ID, and params")
    void extendedResponse_decode() {
        byte[] frame = {0x2A, (byte) 0x80, 0x01, 0x22, 0x00, 0x00};

        EzspCodec.Decoded decoded = EzspCodec.forVersion(13).decode(frame);

        assertThat(decoded.sequence()).isEqualTo(0x2A);
        assertThat(decoded.frame().frameId()).isEqualTo(0x0022);
        assertThat(decoded.frame().isCallback()).isFalse();
        assertThat(decoded.frame().parameters()).containsExactly(0x00);
    }

    @Test
    @DisplayName("callbackType bits identify callbacks: async (0x10) and sync (0x08)")
    void decode_callbackFlags() {
        byte[] async = {0x00, (byte) 0x90, 0x01, 0x48, 0x00, 0x0F, (byte) 0xB0};
        byte[] sync = {0x00, (byte) 0x88, 0x01, 0x06, 0x00};

        assertThat(EzspCodec.forVersion(13).decode(async).frame().isCallback())
                .isTrue();
        assertThat(EzspCodec.forVersion(13).decode(sync).frame().isCallback())
                .isTrue();
    }

    @Test
    @DisplayName("command-direction extended frames are rejected")
    void decode_commandDirection_rejected() {
        byte[] frame = {0x01, 0x00, 0x01, 0x05, 0x00};

        assertThatThrownBy(() -> EzspCodec.forVersion(13).decode(frame))
                .isInstanceOf(EzspFormatException.class)
                .hasMessageContaining("direction");
    }

    @Test
    @DisplayName("an unsupported frame format version is rejected")
    void decode_wrongFormatVersion_rejected() {
        byte[] frame = {0x01, (byte) 0x80, 0x02, 0x05, 0x00};

        assertThatThrownBy(() -> EzspCodec.forVersion(13).decode(frame))
                .isInstanceOf(EzspFormatException.class)
                .hasMessageContaining("format version");
    }

    @Test
    @DisplayName("truncated extended frames are rejected")
    void decode_tooShort_rejected() {
        assertThatThrownBy(() ->
                EzspCodec.forVersion(13).decode(new byte[] {0x01, (byte) 0x80, 0x01}))
                .isInstanceOf(EzspFormatException.class)
                .hasMessageContaining("short");
    }

    @Test
    @DisplayName("v13 status seam: 1-byte EmberStatus (synthetic v13 decode)")
    void statusSeam_v13_singleByte() {
        EzspCodec codec = EzspCodec.forVersion(13);

        assertThat(codec.statusWidthBytes()).isEqualTo(1);
        assertThat(codec.decodeStatus(new byte[] {(byte) 0x93}, 0)).isEqualTo(0x93);

        // Synthetic v13 permitJoining response: single status byte.
        EzspCodec.Decoded decoded = codec.decode(
                new byte[] {0x07, (byte) 0x80, 0x01, 0x22, 0x00, 0x00});
        assertThat(codec.decodeStatus(decoded.frame().parameters(), 0)).isZero();
    }

    @Test
    @DisplayName("v14 status seam: 32-bit little-endian sl_status_t "
            + "(synthetic v14 decode)")
    void statusSeam_v14_fourByteLittleEndian() {
        EzspCodec codec = EzspCodec.forVersion(14);

        assertThat(codec.statusWidthBytes()).isEqualTo(4);
        assertThat(codec.decodeStatus(new byte[] {0x0B, 0x00, 0x00, 0x00}, 0))
                .isEqualTo(0x0B);
        assertThat(codec.decodeStatus(
                new byte[] {0x01, 0x00, 0x02, 0x00}, 0)).isEqualTo(0x00020001);

        // Synthetic v14 formNetwork response: SL_STATUS_OK as 4 bytes.
        EzspCodec.Decoded decoded = codec.decode(new byte[] {
            0x08, (byte) 0x80, 0x01, 0x1E, 0x00, 0x00, 0x00, 0x00, 0x00
        });
        assertThat(codec.decodeStatus(decoded.frame().parameters(), 0)).isZero();
    }

    @Test
    @DisplayName("a status field shorter than the version's width is rejected")
    void decodeStatus_tooShort_rejected() {
        assertThatThrownBy(() ->
                EzspCodec.forVersion(14).decodeStatus(new byte[] {0x00}, 0))
                .isInstanceOf(EzspFormatException.class);
    }

    @Test
    @DisplayName("callbackPending is surfaced from bit 2 of the response control")
    void decode_callbackPendingBit() {
        byte[] frame = {0x01, (byte) 0x84, 0x01, 0x05, 0x00};

        assertThat(EzspCodec.forVersion(13).decode(frame).callbackPending()).isTrue();
    }
}
