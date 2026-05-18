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
 * JFR event for {@code homesynapse.bus.writer.queue.depth} (AMD-43 §3.6.2).
 *
 * <p>Gauge sample emitted on every publish notification with a guaranteed
 * fresh observation of the writer queue (DEC-M3-14 — read via injected
 * {@code IntSupplier}).</p>
 */
@Name("homesynapse.bus.writer.queue.depth")
@Category("HomeSynapse.Bus")
@Label("Bus Writer Queue Depth")
@StackTrace(false)
final class BusWriterQueueDepthEvent extends Event {

    @Label("Depth")
    int depth;

    BusWriterQueueDepthEvent() {
        // Package-private constructor.
    }
}
