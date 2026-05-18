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
 * JFR event for {@code homesynapse.bus.subscriber.derived_writes.parked}
 * (AMD-43 §3.6.2).
 *
 * <p>Counter for token-bucket acquisitions that had to park waiting for a
 * refill. Emitted from {@link DerivedWriteRateLimit#acquire()}.</p>
 */
@Name("homesynapse.bus.subscriber.derived_writes.parked")
@Category("HomeSynapse.Bus")
@Label("Bus Derived Write Parked")
@StackTrace(false)
final class BusWriteParkedEvent extends Event {

    @Label("Subscriber Id")
    String subscriberId;

    BusWriteParkedEvent() {
        // Package-private constructor.
    }
}
