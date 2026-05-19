/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * On-device integration tests for HomeSynapse Core (M3.4).
 *
 * <p>This module deliberately exports no production code — its sole purpose
 * is to host long-running integration tests that exercise the real production
 * stack ({@code InProcessEventBus} + {@code SqliteEventStore} +
 * {@code StateProjection}) under Pi-4-equivalent JVM constraints. The test
 * classes live on the test classpath (unnamed module) per the convention
 * plugin's default test execution model.</p>
 *
 * <p>Tests are excluded from {@code ./gradlew check}. Run via:</p>
 * <pre>{@code
 *   ./gradlew :testing:integration-tests:test -PpiProfile=throttled
 * }</pre>
 */
module com.homesynapse.it {
    // Intentionally empty — tests run on classpath, no production exports.
}
