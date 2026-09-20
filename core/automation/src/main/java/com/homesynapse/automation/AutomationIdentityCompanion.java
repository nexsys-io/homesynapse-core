/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.Map;
import java.util.Optional;

/**
 * The bytes behind the automation identity companion ({@code automations.ids.yaml},
 * Doc 07 §4.1; AMD-93 §2.3) — the I/O seam of {@link CompanionAutomationIdentityStore}.
 *
 * <p>The automation module owns the companion's POLICY (the document shape, minting,
 * retention, fail-closed validation, write-once-if-changed) and stays both
 * filesystem-free ({@code NO_DIRECT_FILESYSTEM_IN_CORE}) and YAML-library-free; an
 * implementation outside the core layer owns the bytes. The composition root supplies
 * the file-backed implementation; a test supplies a {@code Map} holder.</p>
 *
 * <p><strong>The document</strong> crossing this seam is a tree of string-keyed maps
 * whose leaves are {@link String} and {@link Integer} scalars — the store defines and
 * validates its shape; an implementation neither interprets nor reorders it. The store
 * hands {@link #replace} maps that iterate in a deterministic (sorted) order, and an
 * implementation writes them in that order, so an unchanged document is byte-stable.</p>
 *
 * <p><strong>{@code toString()}</strong> names the backing location (the file path).
 * The store prints it as {@code path=} in its log lines and in the message of a
 * fail-closed load.</p>
 *
 * <p>Implementations need not be thread-safe: a load bracket is run by one thread, and
 * the store calls this seam from that thread, never while holding its lock.</p>
 */
public interface AutomationIdentityCompanion {

    /**
     * Reads the companion document.
     *
     * @return the document, or empty when no companion exists (the first boot);
     *         never {@code null}
     * @throws IllegalStateException if a companion exists and cannot be read or
     *         parsed — the message carries the location and the cause. The caller
     *         fails closed: identities are never re-minted over a companion that
     *         exists and cannot be read.
     */
    Optional<Map<String, Object>> read();

    /**
     * Atomically replaces the companion with {@code document}: after a failure the
     * previous companion is intact.
     *
     * @param document the complete new document, never {@code null}
     * @throws IllegalStateException if the companion cannot be written — the message
     *         carries the location and the cause
     */
    void replace(Map<String, Object> document);
}
