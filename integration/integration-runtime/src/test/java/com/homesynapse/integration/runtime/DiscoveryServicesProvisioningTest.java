/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.event.test.InMemoryEventStore;
import com.homesynapse.integration.BackoffParameters;
import com.homesynapse.integration.DataPath;
import com.homesynapse.integration.HealthParameters;
import com.homesynapse.integration.IntegrationAdapter;
import com.homesynapse.integration.IntegrationContext;
import com.homesynapse.integration.IntegrationDescriptor;
import com.homesynapse.integration.IntegrationFactory;
import com.homesynapse.integration.IoType;
import com.homesynapse.integration.IsolationLevel;
import com.homesynapse.integration.RequiredService;
import com.homesynapse.integration.test.StubIntegrationContext;
import com.homesynapse.integration.test.TestAdapter;
import com.homesynapse.test.TestClock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * IR-67 T4 — {@code StandardIntegrationSupervisor.buildContext} provisions the
 * DISCOVERY service family (a {@code DiscoveryServices} over a
 * {@link SupervisorCapabilityPublisher}) into the context's twelfth slot ONLY
 * when the descriptor declares {@link RequiredService#DISCOVERY}; the other
 * four service-gated tails stay null. The fake factory copies the
 * {@code StandardIntegrationSupervisorTest} RecordingFactory form (:416–:419)
 * with the declaration as its parameter — that test is not edited.
 */
@DisplayName("StandardIntegrationSupervisor.buildContext — the DISCOVERY family on declaration (IR-67)")
final class DiscoveryServicesProvisioningTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration TEST_GRACE = Duration.ofMillis(200);

    private StandardIntegrationSupervisor supervisor;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    DiscoveryServicesProvisioningTest() {
    }

    @BeforeEach
    void setUp() {
        TestClock clock = TestClock.at(T0);
        IntegrationContext stubContext = StubIntegrationContext.defaults();
        supervisor = new StandardIntegrationSupervisor(
                new InMemoryEventStore(clock),
                stubContext.entityRegistry(),
                stubContext.stateQueryService(),
                type -> stubContext.configAccess(),
                clock,
                TEST_GRACE);
    }

    @AfterEach
    void tearDown() {
        supervisor.stop();
    }

    @Test
    @DisplayName("T4: a descriptor declaring RequiredService.DISCOVERY receives "
            + "DiscoveryServices carrying a SupervisorCapabilityPublisher; scheduler, "
            + "telemetry, http and security stay null")
    void descriptorDeclaringDiscovery_getsDiscoveryServices() throws Exception {
        DeclaringFactory factory =
                new DeclaringFactory("discovery-a", Set.of(RequiredService.DISCOVERY));

        supervisor.start(List.of(factory)).get(5, TimeUnit.SECONDS);

        IntegrationContext context = factory.capturedContext();
        assertThat(context).isNotNull();
        assertThat(context.discovery()).isNotNull();
        assertThat(context.discovery().capabilityPublisher())
                .isInstanceOf(SupervisorCapabilityPublisher.class);
        assertThat(context.schedulerService()).isNull();
        assertThat(context.telemetryWriter()).isNull();
        assertThat(context.httpClient()).isNull();
        assertThat(context.security()).isNull();
    }

    @Test
    @DisplayName("T4: a descriptor declaring nothing keeps discovery() null (the existing "
            + "fake's form)")
    void descriptorDeclaringNothing_getsNull() throws Exception {
        DeclaringFactory factory = new DeclaringFactory("plain-a", Set.of());

        supervisor.start(List.of(factory)).get(5, TimeUnit.SECONDS);

        assertThat(factory.capturedContext()).isNotNull();
        assertThat(factory.capturedContext().discovery()).isNull();
    }

    private static void blockUntilInterrupted() {
        CountDownLatch never = new CountDownLatch(1);
        try {
            never.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The RecordingFactory form with the descriptor's {@code requiredServices} as a parameter. */
    private static final class DeclaringFactory implements IntegrationFactory {

        private final String integrationType;
        private final Set<RequiredService> requiredServices;
        private final AtomicReference<IntegrationContext> capturedContext =
                new AtomicReference<>();

        private DeclaringFactory(String integrationType, Set<RequiredService> requiredServices) {
            this.integrationType = integrationType;
            this.requiredServices = requiredServices;
        }

        @Override
        public IntegrationDescriptor descriptor() {
            return new IntegrationDescriptor(
                    integrationType, "Declaring " + integrationType, IoType.NETWORK,
                    requiredServices, Set.of(DataPath.DOMAIN), HealthParameters.defaults(),
                    Set.of(), 1, 1, 0, Set.of(), BackoffParameters.defaults(),
                    IsolationLevel.IN_JVM, null);
        }

        @Override
        public IntegrationAdapter create(IntegrationContext context) {
            capturedContext.set(context);
            return TestAdapter.builder()
                    .onRun(DiscoveryServicesProvisioningTest::blockUntilInterrupted)
                    .build();
        }

        IntegrationContext capturedContext() {
            return capturedContext.get();
        }
    }
}
