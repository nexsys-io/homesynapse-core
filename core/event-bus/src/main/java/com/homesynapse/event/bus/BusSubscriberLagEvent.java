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
 * JFR event for subscriber lag (AMD-43 §3.6.2).
 *
 * <p>Carries both {@code homesynapse.bus.subscriber.lag.events} and
 * {@code homesynapse.bus.subscriber.lag.millis} in a single JFR event.
 * The six logical metrics map to six JFR event classes; subscriber lag
 * combines two metric names because they share the same observation
 * point (post-delivery in the LIVE loop).</p>
 */
@Name("homesynapse.bus.subscriber.lag")
@Category("HomeSynapse.Bus")
@Label("Bus Subscriber Lag")
@StackTrace(false)
final class BusSubscriberLagEvent extends Event {

    @Label("Subscriber Id")
    String subscriberId;

    @Label("Lag (events)")
    long lagEvents;

    @Label("Lag (milliseconds)")
    long lagMillis;

    BusSubscriberLagEvent() {
        // Package-private constructor.
    }
}
