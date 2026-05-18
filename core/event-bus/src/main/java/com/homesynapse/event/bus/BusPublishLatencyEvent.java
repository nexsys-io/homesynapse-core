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
 * JFR event for {@code homesynapse.bus.publish.latency} (AMD-43 §3.6.2).
 *
 * <p>{@code @StackTrace(false)} is essential — without it, every
 * {@link #commit()} captures a stack trace and the per-emit cost dominates
 * on Pi 4 at 500+ samples/sec.</p>
 */
@Name("homesynapse.bus.publish.latency")
@Category("HomeSynapse.Bus")
@Label("Bus Publish Latency")
@StackTrace(false)
final class BusPublishLatencyEvent extends Event {

    @Label("Latency (microseconds)")
    long latencyMicros;

    BusPublishLatencyEvent() {
        // Package-private constructor.
    }
}
