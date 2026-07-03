/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import com.homesynapse.integration.CommandEnvelope;
import com.homesynapse.integration.CommandHandler;
import com.homesynapse.integration.DataPath;
import com.homesynapse.integration.HealthParameters;
import com.homesynapse.integration.IntegrationAdapter;
import com.homesynapse.integration.IntegrationContext;
import com.homesynapse.integration.IntegrationDescriptor;
import com.homesynapse.integration.IntegrationFactory;
import com.homesynapse.integration.IoType;
import com.homesynapse.integration.PermanentIntegrationException;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The M9.1 recording fake — the composition-root command sink that proves the
 * integration spine end-to-end without a real protocol adapter (there is no
 * serial/Zigbee code until M9.2+). Its {@link CommandHandler} appends every
 * received {@link CommandEnvelope} to a thread-safe list the tests assert on;
 * its {@code run()} blocks until the supervisor interrupts it (a healthy,
 * long-lived adapter), and {@code initialize()} is instant (INV-RF-03).
 *
 * <p>The descriptor declares {@code IoType.NETWORK} deliberately (W6): the
 * fake exercises the virtual-thread hosting path only — no platform-thread
 * serial path runs before M9.2. The {@code failingAtInitialize(...)} variant
 * is the INV-RF-01 sibling for the two-fake isolation gate (T20).</p>
 */
final class RecordingIntegrationFactory implements IntegrationFactory {

    /** The fake's integration type — the tests derive its stable id from this. */
    static final String INTEGRATION_TYPE = "fake";

    private final String integrationType;
    private final boolean failInitialize;
    private final List<CommandEnvelope> recorded = new CopyOnWriteArrayList<>();

    private RecordingIntegrationFactory(String integrationType, boolean failInitialize) {
        this.integrationType = integrationType;
        this.failInitialize = failInitialize;
    }

    /** The recording fake ({@code integrationType = "fake"}). */
    static RecordingIntegrationFactory recording() {
        return new RecordingIntegrationFactory(INTEGRATION_TYPE, false);
    }

    /** A sibling whose {@code initialize()} throws {@link PermanentIntegrationException} (T20). */
    static RecordingIntegrationFactory failingAtInitialize(String integrationType) {
        return new RecordingIntegrationFactory(integrationType, true);
    }

    /** Every {@link CommandEnvelope} the fake's handler has received, in arrival order. */
    List<CommandEnvelope> recordedCommands() {
        return recorded;
    }

    @Override
    public IntegrationDescriptor descriptor() {
        return new IntegrationDescriptor(
                integrationType,
                "Recording Fake Integration",
                IoType.NETWORK,
                Set.of(),                       // no RequiredServices — the 5 nullable tails stay null
                Set.of(DataPath.DOMAIN),
                HealthParameters.defaults(),
                Set.of(),
                1);
    }

    @Override
    public IntegrationAdapter create(IntegrationContext context)
            throws PermanentIntegrationException {
        return new RecordingAdapter();
    }

    private final class RecordingAdapter implements IntegrationAdapter {

        private final CountDownLatch stopSignal = new CountDownLatch(1);

        private RecordingAdapter() {
        }

        @Override
        public void initialize() throws PermanentIntegrationException {
            if (failInitialize) {
                throw new PermanentIntegrationException(
                        "Integration '" + integrationType + "' configured to fail initialize");
            }
        }

        @Override
        public void run() throws Exception {
            // A healthy adapter blocks in its processing loop; the supervisor's
            // interrupt lands here as InterruptedException -> SHUTDOWN_SIGNAL.
            stopSignal.await();
        }

        @Override
        public void close() {
            stopSignal.countDown();
        }

        @Override
        public CommandHandler commandHandler() {
            return recorded::add;
        }
    }
}
