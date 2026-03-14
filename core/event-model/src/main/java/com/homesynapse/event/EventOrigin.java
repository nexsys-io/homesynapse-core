/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.event;

/**
 * Semantic source category of an event, based on evidence from the producer.
 *
 * <p>The origin field records <em>why</em> an event exists — what kind of stimulus
 * caused it (Doc 01 §3.9). Origin is evidence-based: the value is set only when the
 * producer has direct, protocol-level evidence of the origin. If evidence is
 * insufficient, the origin is {@link #UNKNOWN}.</p>
 *
 * <p><strong>{@link #UNKNOWN} is the default.</strong> The system never guesses origin
 * from heuristics. Hubitat's physical/digital distinction demonstrated that attempting
 * to infer physical interaction without protocol evidence leads to unreliable metadata.</p>
 *
 * <p><strong>There is no REPLAY origin value.</strong> The processing mode (Doc 01 §3.7)
 * governs replay behavior, not event-level tagging. A replayed event retains its original
 * origin — a physical button press that caused a {@code state_reported} event at 3:00 AM
 * is still {@link #PHYSICAL} when replayed at 6:00 AM.</p>
 *
 * @see ProcessingMode
 * @see <a href="Doc 01 §3.9">Event Origin Model</a>
 */
public enum EventOrigin {

    /**
     * A human physically interacted with a device.
     *
     * <p>Evidence required: protocol-level indicator such as a ZCL frame type (Zigbee)
     * or button press notification (Z-Wave).</p>
     */
    PHYSICAL,

    /**
     * A user issued a command through the HomeSynapse UI or API.
     *
     * <p>Evidence required: API request carries an authenticated user identity.</p>
     */
    USER_COMMAND,

    /**
     * An automation rule produced this event.
     *
     * <p>Evidence required: the Automation Engine sets this when executing actions.</p>
     */
    AUTOMATION,

    /**
     * The device generated this event without external stimulus.
     *
     * <p>Examples: battery level reports, firmware update notifications, scheduled
     * periodic reports initiated by the device itself.</p>
     */
    DEVICE_AUTONOMOUS,

    /**
     * The integration adapter generated this event during its own processing.
     *
     * <p>Examples: adapter initialization, polling cycle completion, error handling.
     * The adapter self-identifies as the source.</p>
     */
    INTEGRATION,

    /**
     * The HomeSynapse core runtime generated this event.
     *
     * <p>Examples: startup, shutdown, migration, retention execution. Core services
     * self-identify as the source.</p>
     */
    SYSTEM,

    /**
     * The origin cannot be determined with confidence.
     *
     * <p>Default value. Assigned when none of the other origins can be established
     * with protocol-level evidence. This is not an error — it is an honest
     * representation of uncertainty.</p>
     */
    UNKNOWN
}
