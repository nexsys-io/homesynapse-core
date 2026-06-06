/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.device.Capability;
import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.integration.test.StubIntegrationContext;
import com.homesynapse.platform.identity.EntityId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Verifies the AMD-59/60 evolution of {@link IntegrationContext}: the
 * 10 &rarr; 12 component growth ({@code security}, {@code discovery}), their
 * nullability, and the 10-arg convenience constructor that defaults both to
 * {@code null}.
 */
@DisplayName("IntegrationContext")
class IntegrationContextTest {

    /** Default constructor required by JUnit 5 under {@code -Xlint:all -Werror}. */
    IntegrationContextTest() {
        // Defaults are sufficient.
    }

    /** A no-op {@link CapabilityPublisher} (three abstract methods, so not a lambda). */
    private static CapabilityPublisher noopPublisher() {
        return new CapabilityPublisher() {
            @Override
            public void publishAdded(EntityId entityId, CapabilityInstance instance) {
                // no-op
            }

            @Override
            public void publishAdded(EntityId entityId, Class<? extends Capability> capability) {
                // no-op
            }

            @Override
            public void publishRemoved(
                    EntityId entityId, String capabilityId, CapabilityRemovalReason reason) {
                // no-op
            }
        };
    }

    @Test
    @DisplayName("record has exactly 12 components")
    void hasTwelveComponents() {
        assertThat(IntegrationContext.class.getRecordComponents()).hasSize(12);
    }

    @Test
    @DisplayName("security is component 11 and discovery is component 12")
    void securityThenDiscoveryAppended() {
        var components = IntegrationContext.class.getRecordComponents();
        assertThat(components[10].getName()).isEqualTo("security");
        assertThat(components[11].getName()).isEqualTo("discovery");
    }

    @Test
    @DisplayName("the 10-arg convenience ctor defaults security and discovery to null")
    void tenArgConvenienceCtor_defaultsNull() {
        IntegrationContext base = StubIntegrationContext.defaults();

        IntegrationContext ctx = new IntegrationContext(
                base.integrationId(),
                base.integrationType(),
                base.eventPublisher(),
                base.entityRegistry(),
                base.stateQueryService(),
                base.healthReporter(),
                base.configAccess(),
                base.schedulerService(),
                base.telemetryWriter(),
                base.httpClient());

        assertThat(ctx.security()).isNull();
        assertThat(ctx.discovery()).isNull();
    }

    @Test
    @DisplayName("the stub defaults leave security and discovery null")
    void stubDefaults_securityAndDiscoveryNull() {
        IntegrationContext ctx = StubIntegrationContext.defaults();

        assertThat(ctx.security()).isNull();
        assertThat(ctx.discovery()).isNull();
    }

    @Test
    @DisplayName("builder().security(...) applies the override")
    void builderSecurityOverride() {
        SecurityServices security = new SecurityServices((Map<String, String> secrets) -> { });

        IntegrationContext ctx = StubIntegrationContext.builder().security(security).build();

        assertThat(ctx.security()).isSameAs(security);
        assertThat(ctx.discovery()).isNull();
    }

    @Test
    @DisplayName("builder().discovery(...) applies the override")
    void builderDiscoveryOverride() {
        DiscoveryServices discovery = new DiscoveryServices(noopPublisher());

        IntegrationContext ctx = StubIntegrationContext.builder().discovery(discovery).build();

        assertThat(ctx.discovery()).isSameAs(discovery);
        assertThat(ctx.security()).isNull();
    }
}
