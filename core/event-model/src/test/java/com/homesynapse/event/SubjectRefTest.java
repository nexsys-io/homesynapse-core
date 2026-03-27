/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.PersonId;
import com.homesynapse.platform.identity.SystemId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SubjectRef} — subject identity with type discriminator.
 */
@DisplayName("SubjectRef")
class SubjectRefTest {

    private static final Ulid ULID_A = new Ulid(0x0191B3C4D5E6F708L, 0x0123456789ABCDEFL);

    // ── Construction ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Direct construction")
    class DirectConstructionTests {

        @Test
        @DisplayName("construction with each SubjectType value")
        void constructWithEachType() {
            for (SubjectType type : SubjectType.values()) {
                var ref = new SubjectRef(ULID_A, type);
                assertThat(ref.id()).isEqualTo(ULID_A);
                assertThat(ref.type()).isEqualTo(type);
            }
        }

        @Test
        @DisplayName("null id throws NullPointerException")
        void nullId() {
            assertThatNullPointerException().isThrownBy(() ->
                    new SubjectRef(null, SubjectType.ENTITY))
                    .withMessageContaining("id");
        }

        @Test
        @DisplayName("null type throws NullPointerException")
        void nullType() {
            assertThatNullPointerException().isThrownBy(() ->
                    new SubjectRef(ULID_A, null))
                    .withMessageContaining("type");
        }
    }

    // ── Factory methods ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("entity(EntityId) creates ENTITY ref")
        void entityFactory() {
            var entityId = EntityId.of(ULID_A);
            var ref = SubjectRef.entity(entityId);

            assertThat(ref.id()).isEqualTo(ULID_A);
            assertThat(ref.type()).isEqualTo(SubjectType.ENTITY);
        }

        @Test
        @DisplayName("device(DeviceId) creates DEVICE ref")
        void deviceFactory() {
            var deviceId = DeviceId.of(ULID_A);
            var ref = SubjectRef.device(deviceId);

            assertThat(ref.id()).isEqualTo(ULID_A);
            assertThat(ref.type()).isEqualTo(SubjectType.DEVICE);
        }

        @Test
        @DisplayName("integration(IntegrationId) creates INTEGRATION ref")
        void integrationFactory() {
            var integrationId = IntegrationId.of(ULID_A);
            var ref = SubjectRef.integration(integrationId);

            assertThat(ref.id()).isEqualTo(ULID_A);
            assertThat(ref.type()).isEqualTo(SubjectType.INTEGRATION);
        }

        @Test
        @DisplayName("automation(AutomationId) creates AUTOMATION ref")
        void automationFactory() {
            var automationId = AutomationId.of(ULID_A);
            var ref = SubjectRef.automation(automationId);

            assertThat(ref.id()).isEqualTo(ULID_A);
            assertThat(ref.type()).isEqualTo(SubjectType.AUTOMATION);
        }

        @Test
        @DisplayName("system(SystemId) creates SYSTEM ref")
        void systemFactory() {
            var systemId = SystemId.of(ULID_A);
            var ref = SubjectRef.system(systemId);

            assertThat(ref.id()).isEqualTo(ULID_A);
            assertThat(ref.type()).isEqualTo(SubjectType.SYSTEM);
        }

        @Test
        @DisplayName("person(PersonId) creates PERSON ref")
        void personFactory() {
            var personId = PersonId.of(ULID_A);
            var ref = SubjectRef.person(personId);

            assertThat(ref.id()).isEqualTo(ULID_A);
            assertThat(ref.type()).isEqualTo(SubjectType.PERSON);
        }

        @Test
        @DisplayName("entity(null) throws NullPointerException")
        void entityNullThrows() {
            assertThatNullPointerException().isThrownBy(() ->
                    SubjectRef.entity(null));
        }

        @Test
        @DisplayName("device(null) throws NullPointerException")
        void deviceNullThrows() {
            assertThatNullPointerException().isThrownBy(() ->
                    SubjectRef.device(null));
        }

        @Test
        @DisplayName("integration(null) throws NullPointerException")
        void integrationNullThrows() {
            assertThatNullPointerException().isThrownBy(() ->
                    SubjectRef.integration(null));
        }

        @Test
        @DisplayName("automation(null) throws NullPointerException")
        void automationNullThrows() {
            assertThatNullPointerException().isThrownBy(() ->
                    SubjectRef.automation(null));
        }

        @Test
        @DisplayName("system(null) throws NullPointerException")
        void systemNullThrows() {
            assertThatNullPointerException().isThrownBy(() ->
                    SubjectRef.system(null));
        }

        @Test
        @DisplayName("person(null) throws NullPointerException")
        void personNullThrows() {
            assertThatNullPointerException().isThrownBy(() ->
                    SubjectRef.person(null));
        }
    }

    // ── toString ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("toString returns type:id format")
    void toStringFormat() {
        var ref = SubjectRef.entity(EntityId.of(ULID_A));
        assertThat(ref.toString()).startsWith("entity:");
        assertThat(ref.toString()).contains(ULID_A.toString());
    }

    // ── Equals / hashCode ────────────────────────────────────────────────

    @Nested
    @DisplayName("equals / hashCode")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("same id and type are equal")
        void sameIdAndType() {
            var a = new SubjectRef(ULID_A, SubjectType.ENTITY);
            var b = new SubjectRef(ULID_A, SubjectType.ENTITY);
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("same id with different type are not equal")
        void sameIdDifferentType() {
            var entity = new SubjectRef(ULID_A, SubjectType.ENTITY);
            var device = new SubjectRef(ULID_A, SubjectType.DEVICE);
            assertThat(entity).isNotEqualTo(device);
        }
    }
}
