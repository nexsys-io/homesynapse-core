/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import com.homesynapse.event.EventCategory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The encryption-scope registry (Doc 15 §8.2, M6.3): the canonical scope-id
 * constants, the default {@code encrypted_scopes} set (Doc 15 §9), the
 * event-category → scope-id mapping, and the membership test against a
 * configured set.
 *
 * <h2>Confirmed encrypted set (OQ-15-2 RESOLVED 2026-06-12)</h2>
 *
 * <p>The sensitive-PII scopes are {@link #IDENTITY} and
 * {@link #PRESENCE_PERSONAL}. Doc 15 §9's
 * {@code crypto.encryption.encrypted_scopes} defaults to exactly these two
 * (the {@code [identity, presence_personal]} default carried verbatim from
 * the disposition); every other event category stays plaintext-at-rest at
 * MVP on Doc 15 §3.4's PII-classification grounds.</p>
 *
 * <h2>Event → scope mapping (the contract decision)</h2>
 *
 * <p>Doc 15 §3.4 names the encrypted-set members as "identity and
 * person-linked presence ... presence events {@code presence_signal} /
 * {@code presence_changed} and any person-linked records." Resolved against
 * the live taxonomy (Doc 01 §4.4 / {@code EventCategory}):</p>
 *
 * <ul>
 *   <li>{@link #PRESENCE_PERSONAL} ← every event in the
 *       {@link EventCategory#PRESENCE} category ({@code presence_signal},
 *       {@code presence_changed}). This is a clean, total mapping.</li>
 *   <li>{@link #IDENTITY} ← <strong>no core event type at MVP.</strong> The
 *       core taxonomy ({@code EventTypes} / {@code EventCategory}) carries no
 *       identity category and no person-identity event record; the scope is
 *       defined, configured, and keyed for the future person-linked identity
 *       records Doc 15 §3.4 anticipates, so those records encrypt-on-write
 *       from the moment they exist (INV-PD-07 forward-compatibility). At MVP
 *       it resolves zero events — encrypt-on-write is irreversible, so the
 *       scope must exist now even though nothing populates it yet.</li>
 * </ul>
 *
 * <p>Encryption is on the {@code payload} column only (LTD-04): an event's
 * {@code actor_ref} (a {@code PersonId} on some events) is plaintext metadata
 * and part of the canonical chain input — it does NOT make the event an
 * identity-scope payload. Only an event whose serialized <em>payload</em>
 * carries the sensitive PII maps to an encrypted scope.</p>
 *
 * <h2>Module placement (Doc 15 §3.8)</h2>
 *
 * <p>This type lives in {@code com.homesynapse.config} (a domain module), not
 * in {@code event}: it is key-management/scope-policy, the same side as
 * {@link ScopeKeyManager}. The persistence write path resolves scopes against
 * a {@code Set<String>} of enabled scope-ids ({@code java.base} only) and
 * mirrors the one MVP {@code PRESENCE → presence_personal} edge internally
 * (the same shape-mirror discipline that keeps {@code EncryptedPayload} and
 * {@link ScopeCipherResult} distinct across the persistence/config boundary)
 * — persistence gains no {@code config} dependency.</p>
 */
public enum EncryptionScope {

    /**
     * Person-identity records. No core event type maps here at MVP (the
     * taxonomy has no identity category); the scope is reserved and keyed for
     * future person-linked identity records (Doc 15 §3.4).
     */
    IDENTITY("identity"),

    /**
     * Person-linked presence — every {@link EventCategory#PRESENCE} event
     * ({@code presence_signal}, {@code presence_changed}). Occupancy reveals
     * daily routines; among the most privacy-sensitive categories.
     */
    PRESENCE_PERSONAL("presence_personal", EventCategory.PRESENCE);

    /**
     * The MVP default {@code crypto.encryption.encrypted_scopes} set (Doc 15
     * §9), carried verbatim from the OQ-15-2 disposition. An empty or unset
     * configured set resolves to this default; a scope absent from the
     * configured set is plaintext-at-rest.
     */
    public static final Set<String> DEFAULT_ENCRYPTED_SCOPE_IDS =
            Set.of(IDENTITY.scopeId, PRESENCE_PERSONAL.scopeId);

    private final String scopeId;
    private final Set<EventCategory> categories;

    EncryptionScope(String scopeId, EventCategory... categories) {
        this.scopeId = scopeId;
        this.categories = Set.of(categories);
    }

    /**
     * Returns the wire/config scope-id string for this scope (e.g.
     * {@code "presence_personal"}) — the value that appears in
     * {@code crypto.encryption.encrypted_scopes} and in a {@code dek_ref}'s
     * {@code scope_id:key_version} form.
     *
     * @return the scope-id; never {@code null} or blank
     */
    public String scopeId() {
        return scopeId;
    }

    /**
     * Resolves the encryption scope-id for an event from its consent-scope
     * categories (Doc 01 §4.4), per the canonical mapping documented on this
     * type. At MVP only {@link EventCategory#PRESENCE} resolves
     * ({@code → "presence_personal"}); every other category is plaintext.
     *
     * @param categories the event's non-null category list (from
     *                   {@code EventCategoryMapping})
     * @return the scope-id to encrypt the payload under, or empty for a
     *         plaintext-at-rest event; never {@code null}
     */
    public static Optional<String> scopeIdFor(List<EventCategory> categories) {
        Objects.requireNonNull(categories, "categories must not be null");
        for (EncryptionScope scope : values()) {
            for (EventCategory category : scope.categories) {
                if (categories.contains(category)) {
                    return Optional.of(scope.scopeId);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Tests whether the given scope-id is encrypted under a configured set.
     * An empty {@code configured} set means "use the MVP default"
     * ({@link #DEFAULT_ENCRYPTED_SCOPE_IDS}); a non-empty set is taken as
     * authoritative (Doc 15 §9 — a scope absent from a configured set is
     * plaintext).
     *
     * @param scopeId    the scope-id to test; never {@code null}
     * @param configured the configured {@code encrypted_scopes} set;
     *                   never {@code null}, may be empty (⇒ default)
     * @return {@code true} if the scope is encrypted-at-rest
     */
    public static boolean isEncrypted(String scopeId, Set<String> configured) {
        Objects.requireNonNull(scopeId, "scopeId must not be null");
        Objects.requireNonNull(configured, "configured must not be null");
        Set<String> effective = configured.isEmpty()
                ? DEFAULT_ENCRYPTED_SCOPE_IDS
                : configured;
        return effective.contains(scopeId);
    }
}
