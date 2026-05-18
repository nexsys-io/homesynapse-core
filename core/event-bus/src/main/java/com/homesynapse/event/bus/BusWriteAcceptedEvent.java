/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event for {@code homesynapse.bus.subscriber.derived_writes.accepted}
 * (AMD-43 §3.6.2).
 *
 * <p>Counter for token-bucket acquisitions that returned immediately
 * (a token was available). Emitted from
 * {@link DerivedWriteRateLimit#acquire()}.</p>
 */
@Name("homesynapse.bus.subscriber.derived_writes.accepted")
@Category("HomeSynapse.Bus")
@Label("Bus Derived Write Accepted")
@StackTrace(false)
final class BusWriteAcceptedEvent extends Event {

    @Label("Subscriber Id")
    String subscriberId;

    BusWriteAcceptedEvent() {
        // Package-private constructor.
    }
}
