/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus.test;

import com.homesynapse.event.bus.SubscriberReadConnectionFactory;
import com.homesynapse.event.bus.SubscriberReadExecutor;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Recording stub for {@link SubscriberReadConnectionFactory} used in contract tests.
 *
 * <p>Records each {@link #create(String)} invocation, including the subscriber ID.
 * The returned {@link SubscriberReadExecutor} executes tasks synchronously on the
 * calling thread (no dedicated platform thread).</p>
 *
 * <p>This enables {@code INV_SUB_ISO_02} assertions: verify that the bus called
 * {@code create()} once per subscriber with the correct IDs.</p>
 *
 * @see EventBusContractTest
 */
public class RecordingReadConnectionFactory implements SubscriberReadConnectionFactory {

    private final CopyOnWriteArrayList<String> subscriberIds = new CopyOnWriteArrayList<>();

    /**
     * Creates a new recording factory.
     */
    public RecordingReadConnectionFactory() {
        // Explicit constructor.
    }

    @Override
    public SubscriberReadExecutor create(String subscriberId) {
        subscriberIds.add(subscriberId);
        return new SynchronousReadExecutor();
    }

    /**
     * Returns the number of times {@link #create(String)} was called.
     *
     * @return the create call count
     */
    public int createCallCount() {
        return subscriberIds.size();
    }

    /**
     * Returns the subscriber IDs passed to {@link #create(String)} in call order.
     *
     * @return the list of subscriber IDs
     */
    public List<String> subscriberIds() {
        return List.copyOf(subscriberIds);
    }

    /**
     * Resets the recording state.
     */
    public void reset() {
        subscriberIds.clear();
    }

    /**
     * Synchronous read executor that runs tasks on the calling thread.
     */
    private static final class SynchronousReadExecutor implements SubscriberReadExecutor {

        /** Creates a new synchronous executor. */
        SynchronousReadExecutor() {
            // Package-private.
        }

        @Override
        public <T> T executeRead(Callable<T> task) throws Exception {
            return task.call();
        }

        @Override
        public void close() {
            // No resources to release.
        }
    }
}
