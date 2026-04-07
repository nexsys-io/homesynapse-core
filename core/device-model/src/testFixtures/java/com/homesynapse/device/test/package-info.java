/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * Test fixtures for the device-model module.
 *
 * <p>Contains reusable factory classes for creating {@link com.homesynapse.device.Device},
 * {@link com.homesynapse.device.Entity}, {@link com.homesynapse.device.Capability}, and
 * {@link com.homesynapse.device.CapabilityInstance} instances with sensible defaults.
 * Downstream modules consume these fixtures via
 * {@code testImplementation(testFixtures(project(":core:device-model")))} to construct
 * test data without manually populating every record field.</p>
 *
 * <ul>
 *   <li>{@link com.homesynapse.device.test.TestDeviceFactory} — Builder for 14-field
 *       {@code Device} records with sensible defaults and full customization.</li>
 *   <li>{@link com.homesynapse.device.test.TestEntityFactory} — Builder for 11-field
 *       {@code Entity} records with convenience methods for common entity types
 *       ({@code light()}, {@code sensor()}, {@code binarySensor()}).</li>
 *   <li>{@link com.homesynapse.device.test.TestCapabilityFactory} — Static factories
 *       for all 16 capability types in the sealed hierarchy, plus
 *       {@code CapabilityInstance} converters.</li>
 * </ul>
 */
package com.homesynapse.device.test;
