/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import com.homesynapse.event.bus.SubscriberSnapshot;

import java.util.List;
import java.util.OptionalLong;

/**
 * FIX-2a (A) — the text an await timeout leaves behind, so the next
 * OR-BUS-SILENT-DROP red is a reading instead of a bare
 * {@code "timed out awaiting …"}: the store head, the position the caller was
 * waiting on (when it can name one), and one line per subscriber naming its
 * mode, its persisted checkpoint, its DLQ depth and how far behind the head it
 * rests. Pure: no I/O, no clock, no bus — the caller gathers the snapshots
 * and the head and prints/throws the result.
 *
 * <p>The grammar is a contract (grep-stable {@code subsystem.token: key=value}
 * lines; the first line is the JUnit XML {@code <failure message>}):</p>
 * <pre>
 * bus.await_timeout: what=&lt;what&gt; store_head=&lt;H&gt; awaited=&lt;P|none&gt;
 * bus.await_subscriber: subscriber=&lt;id&gt; mode=&lt;MODE&gt; checkpoint=&lt;C&gt; dlq=&lt;D&gt; behind=&lt;H-C&gt;
 * </pre>
 * <p>One {@code bus.await_subscriber} line per snapshot, in the list's order;
 * lines are {@code \n}-joined with no trailing newline. {@code behind} is the
 * head minus the snapshot's checkpoint — for the atomic-checkpoint projection
 * ({@code state_projection}, AMD-45 §2.2) that number lags delivery by design
 * and is a reading, not a verdict.</p>
 */
final class BusAwaitDiagnostic {

    private BusAwaitDiagnostic() {
    }

    /**
     * Renders the timeout diagnostic.
     *
     * @param what        what the caller was awaiting (the hero test's phrase)
     * @param storeHead   the max {@code globalPosition} in the store (0 when empty)
     * @param awaited     the position the await was gated on, when the caller can name one
     * @param subscribers the bus's {@code subscribers()} at the moment of the timeout
     * @return the diagnostic text, first line first
     */
    static String render(String what, long storeHead, OptionalLong awaited,
            List<SubscriberSnapshot> subscribers) {
        StringBuilder text = new StringBuilder(160 + 96 * subscribers.size());
        text.append("bus.await_timeout: what=").append(what)
                .append(" store_head=").append(storeHead)
                .append(" awaited=")
                .append(awaited.isPresent() ? Long.toString(awaited.getAsLong()) : "none");
        for (SubscriberSnapshot snapshot : subscribers) {
            text.append('\n')
                    .append("bus.await_subscriber: subscriber=").append(snapshot.subscriberId())
                    .append(" mode=").append(snapshot.mode())
                    .append(" checkpoint=").append(snapshot.checkpoint())
                    .append(" dlq=").append(snapshot.dlqDepth())
                    .append(" behind=").append(storeHead - snapshot.checkpoint());
        }
        return text.toString();
    }
}
