/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

/**
 * Per-section reload-reaction seam (AMD-66): a subsystem registers its intent
 * for its own configuration section and classifies the runtime impact of a
 * change to that section.
 *
 * <p>Listeners are registered with {@code ConfigurationService} at composition
 * time — a constructor-injected map keyed by {@link #sectionPath()}, no
 * {@code ServiceLoader} (DEC-M3-16). At most one listener per section path;
 * a duplicate registration is a construction-time error (AMD-66 §2.4).</p>
 *
 * <p>When no listener is registered for a changed section, the reload
 * pipeline falls back to the per-property {@code x-reload} JSON Schema
 * classification, whose locked default for unannotated properties is
 * {@link ReloadClassification#PROCESS_RESTART} (AMD-66 §2.3).</p>
 *
 * <h2>Invocation Contract</h2>
 *
 * <p>The reload pipeline (M6.4) invokes listeners synchronously, before any
 * configuration observability event is published (Doc 06 §3.3 ordering,
 * AMD-66-INV-02). A throwing listener fails the reload candidate — the
 * active {@link ConfigModel} is preserved per the §3.3
 * reject-and-keep-prior-good-state semantics — and is surfaced as a reload
 * issue.</p>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Implementations must be safe for invocation from the reload pipeline's
 * thread. Implementations must be side-effect-free with respect to the
 * {@code ConfigModel} (INV-CE-01 — the YAML file is the sole source of
 * truth); they classify, they do not mutate configuration
 * (AMD-66-INV-01).</p>
 *
 * @see ReloadClassification
 * @see ConfigSection
 * @see ConfigurationService
 */
public interface ConfigurationChangeListener {

    /**
     * Returns the dotted section path this listener reacts to
     * (matches {@link ConfigSection#path()}).
     *
     * @return the dotted section path (e.g., {@code "event_bus"},
     *         {@code "integrations.zigbee"}); never {@code null}
     */
    String sectionPath();

    /**
     * Invoked synchronously by the reload pipeline, BEFORE any
     * {@code config_changed} observability event is published (Doc 06 §3.3),
     * when the listener's section changed between the active and the
     * candidate {@link ConfigModel}. Returns the aggregated runtime-impact
     * classification for this section's change.
     *
     * <p>Implementations must be side-effect-free with respect to the
     * {@code ConfigModel} (INV-CE-01 — the file is the sole source of truth);
     * they classify, they do not mutate configuration.</p>
     *
     * @param previous  the section from the active model; never {@code null}
     * @param candidate the section from the candidate model; never {@code null}
     * @return the aggregated runtime-impact classification for this section's
     *         change; never {@code null}
     */
    ReloadClassification onSectionChanged(ConfigSection previous, ConfigSection candidate);
}
