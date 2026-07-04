/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.device.ConfirmationMode;

import java.util.List;
import java.util.Set;

/**
 * The Wave-1 MEASURED characterization values, transcribed from the bench corpus
 * (nexsys-bench@5ceff3b: {@code corpus/devices/philips-hue-white-a19.md} +
 * {@code corpus/devices/sonoff-snzb-03p-motion.md}, BENCH_CAPTURE 2026-07-01).
 *
 * <p>These constants are the single in-test source of truth for the AMD-97
 * characterization tests, the profile-loader round-trip tests, and the §J
 * fixture-replay gate. The corpus files are the acceptance spec; this class cites
 * them, never reinterprets them (pointer-not-copy: on divergence the corpus wins).
 */
final class MeasuredCorpusValues {

    /** Hue LCA017 identity (measured 2026-07-01, live interview). */
    static final String HUE_MANUFACTURER = "Signify Netherlands B.V.";
    static final String HUE_MODEL = "LCA017";
    static final int HUE_ENDPOINT = 11;

    /** SNZB-03P identity (measured 2026-07-01, live interview). */
    static final String SNZB_MANUFACTURER = "eWeLink";
    static final String SNZB_MODEL = "SNZB-03P";
    static final int SNZB_ENDPOINT = 1;

    /** The bundled first-party profile ids (bare namespace — Doc 18 §3.5(b)). */
    static final String HUE_PROFILE_ID = "philips_hue_white_color_a19";
    static final String SNZB_PROFILE_ID = "sonoff_snzb_03p";

    /**
     * The composable degrade set measured for the Hue confirmable capabilities:
     * no authoritative report within timeout ⇒ UNCONFIRMED; explicit NACK ⇒ FAILED;
     * idempotent no-change commands confirm from cache/readback (measured caveat 1).
     */
    static final Set<DegradeRule> HUE_CONFIRMABLE_DEGRADE = Set.of(
            DegradeRule.NO_REPORT_TIMEOUT_TO_UNCONFIRMED,
            DegradeRule.NACK_TO_FAILED,
            DegradeRule.CONFIRM_FROM_CACHE_OR_READBACK);

    /** on_off: CONFIRMABLE via VERIFIED_REPORTS/ON_CHANGE, measured 293-701 ms. */
    static final ConfirmationCharacterization HUE_ON_OFF =
            new ConfirmationCharacterization(
                    "on_off",
                    ConfirmationMode.EXACT_MATCH,
                    "OnOff/0x0000",
                    ReportsAuthoritative.VERIFIED_REPORTS,
                    ReportingPosture.ON_CHANGE,
                    Confirmability.CONFIRMABLE,
                    5000L,
                    HUE_CONFIRMABLE_DEGRADE,
                    "measured latency 293-701 ms; no-change commands produce no report");

    /** brightness: CONFIRMABLE, measured 353-686 ms; settled-value tolerance. */
    static final ConfirmationCharacterization HUE_BRIGHTNESS =
            new ConfirmationCharacterization(
                    "brightness",
                    ConfirmationMode.TOLERANCE,
                    "LevelControl/0x0000",
                    ReportsAuthoritative.VERIFIED_REPORTS,
                    ReportingPosture.ON_CHANGE,
                    Confirmability.CONFIRMABLE,
                    5000L,
                    HUE_CONFIRMABLE_DEGRADE,
                    "measured latency 353-686 ms; fade transients precede the settled value");

    /**
     * color_temperature: CONFIRMABLE but SLOW (full color-attribute batch at ~10 s
     * min-interval; measured 6.7-8.4 s command-to-report; ±1-mired re-derivation
     * makes EXACT_MATCH false-fail — the measured TOLERANCE validation).
     */
    static final ConfirmationCharacterization HUE_COLOR_TEMPERATURE =
            new ConfirmationCharacterization(
                    "color_temperature",
                    ConfirmationMode.TOLERANCE,
                    "ColorControl/0x0007",
                    ReportsAuthoritative.VERIFIED_REPORTS,
                    ReportingPosture.ON_CHANGE,
                    Confirmability.CONFIRMABLE,
                    15000L,
                    HUE_CONFIRMABLE_DEGRADE,
                    "batched reports at ~10 s min-interval; timeout must exceed the "
                            + "Color cluster report min-interval; drift ±1 mired");

    /**
     * effect (color_loop): the write-only honesty proof — SUCCESS ACK, then zero
     * effect-state reports; color_loop_active 0x4002 readable on demand but NEVER
     * reported. UNCONFIRMABLE-by-report ⇒ immediate honest UNCONFIRMED.
     */
    static final ConfirmationCharacterization HUE_EFFECT =
            new ConfirmationCharacterization(
                    "effect",
                    ConfirmationMode.DISABLED,
                    "ColorControl/0x4002",
                    ReportsAuthoritative.READBACK_ONLY,
                    ReportingPosture.NONE,
                    Confirmability.UNCONFIRMABLE,
                    0L,
                    Set.of(DegradeRule.IMMEDIATE_UNCONFIRMED),
                    "color_loop_set ACKs SUCCESS then never reports effect state; "
                            + "0x4002 readable on demand (readback demonstrated)");

    /** identify: the strict UNCONFIRMABLE class — no reportable attribute at all. */
    static final ConfirmationCharacterization HUE_IDENTIFY =
            new ConfirmationCharacterization(
                    "identify",
                    ConfirmationMode.DISABLED,
                    null,
                    ReportsAuthoritative.NONE,
                    ReportingPosture.NONE,
                    Confirmability.UNCONFIRMABLE,
                    0L,
                    Set.of(DegradeRule.IMMEDIATE_UNCONFIRMED),
                    "DefaultResponse SUCCESS +90 ms, then no report, ever");

    /** The measured Hue 5-block, in corpus order. */
    static final List<ConfirmationCharacterization> HUE_CONFIRMATION_BLOCK = List.of(
            HUE_ON_OFF, HUE_BRIGHTNESS, HUE_COLOR_TEMPERATURE, HUE_EFFECT, HUE_IDENTIFY);

    /** SNZB-03P: read-only sensor — the measured EMPTY confirmation block. */
    static final List<ConfirmationCharacterization> SNZB_CONFIRMATION_BLOCK = List.of();

    /**
     * The measured Hue EP-11 endpoint signature (profile 0x0104, device type 0x010D,
     * in-clusters incl. the Touchlink/manufacturer deltas, out genOta).
     */
    static final EndpointSignature HUE_EP11_SIGNATURE = new EndpointSignature(
            0x0104,
            0x010D,
            Set.of(0x0000, 0x0003, 0x0004, 0x0005, 0x0006, 0x0008, 0x0300,
                    0x1000, 0xFC01, 0xFC04),
            Set.of(0x0019));

    /**
     * The measured SNZB-03P EP-1 endpoint signature (profile 0x0104, device type
     * 0x0107, dual-path in-clusters incl. IAS Zone 0x0500 + eWeLink 0xFC57).
     */
    static final EndpointSignature SNZB_EP1_SIGNATURE = new EndpointSignature(
            0x0104,
            0x0107,
            Set.of(0x0000, 0x0001, 0x0003, 0x0020, 0x0406, 0x0500, 0xFC57),
            Set.of(0x0003, 0x0019));

    private MeasuredCorpusValues() {
    }
}
