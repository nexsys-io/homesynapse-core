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
 * JFR event for {@code homesynapse.bus.publisher.blocked.count} (AMD-43 §3.6.2).
 *
 * <p>Instant event with no payload beyond the JFR timestamp — counters are
 * derived by aggregating event occurrences in the JFR stream.</p>
 */
@Name("homesynapse.bus.publisher.blocked.count")
@Category("HomeSynapse.Bus")
@Label("Bus Publisher Blocked")
@StackTrace(false)
final class BusPublisherBlockedEvent extends Event {

    BusPublisherBlockedEvent() {
        // Package-private constructor.
    }
}
