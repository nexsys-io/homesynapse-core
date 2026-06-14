/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import com.homesynapse.event.EventCategory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link EncryptionScope} — the canonical scope-id registry, the
 * MVP default set (OQ-15-2), the event-category → scope mapping (the M6.3
 * contract decision), and the membership test (Doc 15 §3.4, §8.2, §9).
 */
@DisplayName("EncryptionScope (Doc 15 §3.4/§8.2/§9, M6.3)")
class EncryptionScopeTest {

    /** Creates a new test instance. */
    EncryptionScopeTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    @Test
    @DisplayName("the scope-ids match the Doc 15 §9 / OQ-15-2 names verbatim")
    void scopeIdsMatchTheConfirmedSet() {
        assertThat(EncryptionScope.IDENTITY.scopeId()).isEqualTo("identity");
        assertThat(EncryptionScope.PRESENCE_PERSONAL.scopeId())
                .isEqualTo("presence_personal");
    }

    @Test
    @DisplayName("the default encrypted set is exactly [identity, presence_personal]")
    void defaultSetIsTheConfirmedPair() {
        assertThat(EncryptionScope.DEFAULT_ENCRYPTED_SCOPE_IDS)
                .containsExactlyInAnyOrder("identity", "presence_personal");
    }

    @Test
    @DisplayName("PRESENCE-category events map to presence_personal")
    void presenceCategoryMapsToPresencePersonal() {
        assertThat(EncryptionScope.scopeIdFor(List.of(EventCategory.PRESENCE)))
                .contains("presence_personal");
    }

    @Test
    @DisplayName("non-PRESENCE categories map to no scope (plaintext-at-rest)")
    void nonSensitiveCategoriesMapToNoScope() {
        assertThat(EncryptionScope.scopeIdFor(List.of(EventCategory.DEVICE_STATE)))
                .isEmpty();
        assertThat(EncryptionScope.scopeIdFor(
                List.of(EventCategory.SYSTEM, EventCategory.DEVICE_HEALTH)))
                .isEmpty();
        // The contract decision (M6.3): no core event category resolves to
        // the identity scope at MVP — it is reserved for future person-linked
        // identity records. So no current category maps to "identity".
        assertThat(EncryptionScope.scopeIdFor(List.of(EventCategory.AUTOMATION)))
                .isEmpty();
    }

    @Test
    @DisplayName("an empty configured set falls back to the MVP default")
    void emptyConfiguredSetUsesDefault() {
        assertThat(EncryptionScope.isEncrypted("presence_personal", Set.of())).isTrue();
        assertThat(EncryptionScope.isEncrypted("identity", Set.of())).isTrue();
        assertThat(EncryptionScope.isEncrypted("device_state", Set.of())).isFalse();
    }

    @Test
    @DisplayName("a non-empty configured set is authoritative")
    void configuredSetIsAuthoritative() {
        Set<String> configured = Set.of("presence_personal");
        assertThat(EncryptionScope.isEncrypted("presence_personal", configured)).isTrue();
        // identity is in the default but NOT in this configured set ⇒ plaintext.
        assertThat(EncryptionScope.isEncrypted("identity", configured)).isFalse();
    }
}
