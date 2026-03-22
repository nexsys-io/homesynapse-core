/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * Cross-cutting test infrastructure for HomeSynapse Core.
 *
 * <p>This module provides shared fixtures consumed by all module test suites:
 * <ul>
 *   <li>{@link com.homesynapse.test.TestClock} — Controllable clock for
 *       deterministic time-dependent tests</li>
 *   <li>{@link com.homesynapse.test.SynchronousEventBus} — Inline event delivery
 *       for deterministic subscriber ordering</li>
 *   <li>{@link com.homesynapse.test.NoRealIoExtension} — JUnit 5 extension
 *       preventing accidental network I/O</li>
 *   <li>{@link com.homesynapse.test.RealIo} — Annotation to exempt specific
 *       tests from I/O guards</li>
 * </ul>
 *
 * @see com.homesynapse.test.assertions
 */
package com.homesynapse.test;
