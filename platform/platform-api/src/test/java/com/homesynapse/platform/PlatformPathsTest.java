/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the {@link PlatformPaths} interface.
 *
 * <p>Since {@code PlatformPaths} is an interface with no implementations yet,
 * these tests verify the API surface exists and is implementable by using a
 * minimal test double. Phase 3 implementations ({@code LinuxSystemPaths},
 * {@code LocalPaths}) will extend or replicate these contract assertions.</p>
 *
 * <h3>Method inventory (6 methods):</h3>
 * <ul>
 *   <li>{@code binaryDir()} — read-only runtime image location</li>
 *   <li>{@code configDir()} — writable configuration directory</li>
 *   <li>{@code dataDir()} — writable persistent data directory</li>
 *   <li>{@code logDir()} — writable log directory</li>
 *   <li>{@code backupDir()} — writable pre-update snapshot directory</li>
 *   <li>{@code tempDir()} — writable temp directory (cleaned on startup)</li>
 * </ul>
 */
@DisplayName("PlatformPaths")
class PlatformPathsTest {

    /** Minimal test implementation that returns fixed paths. */
    private static final PlatformPaths TEST_PATHS = new PlatformPaths() {
        private final Path binary = Path.of("/opt/homesynapse");
        private final Path config = Path.of("/etc/homesynapse");
        private final Path data = Path.of("/var/lib/homesynapse");
        private final Path log = Path.of("/var/log/homesynapse");
        private final Path backup = Path.of("/var/lib/homesynapse/backups");
        private final Path temp = Path.of("/var/lib/homesynapse/tmp");

        @Override public Path binaryDir() { return binary; }
        @Override public Path configDir() { return config; }
        @Override public Path dataDir() { return data; }
        @Override public Path logDir() { return log; }
        @Override public Path backupDir() { return backup; }
        @Override public Path tempDir() { return temp; }
    };

    @Nested
    @DisplayName("Interface implementability")
    class ImplementabilityTests {

        @Test
        @DisplayName("test implementation can be instantiated")
        void testImplInstantiable() {
            assertThat(TEST_PATHS).isNotNull();
        }

        @Test
        @DisplayName("test implementation is an instance of PlatformPaths")
        void testImplIsCorrectType() {
            assertThat(TEST_PATHS).isInstanceOf(PlatformPaths.class);
        }
    }

    @Nested
    @DisplayName("Method return values")
    class MethodReturnTests {

        @Test
        @DisplayName("binaryDir() returns configured path")
        void binaryDir() {
            assertThat(TEST_PATHS.binaryDir()).isEqualTo(Path.of("/opt/homesynapse"));
        }

        @Test
        @DisplayName("configDir() returns configured path")
        void configDir() {
            assertThat(TEST_PATHS.configDir()).isEqualTo(Path.of("/etc/homesynapse"));
        }

        @Test
        @DisplayName("dataDir() returns configured path")
        void dataDir() {
            assertThat(TEST_PATHS.dataDir()).isEqualTo(Path.of("/var/lib/homesynapse"));
        }

        @Test
        @DisplayName("logDir() returns configured path")
        void logDir() {
            assertThat(TEST_PATHS.logDir()).isEqualTo(Path.of("/var/log/homesynapse"));
        }

        @Test
        @DisplayName("backupDir() returns configured path")
        void backupDir() {
            assertThat(TEST_PATHS.backupDir()).isEqualTo(Path.of("/var/lib/homesynapse/backups"));
        }

        @Test
        @DisplayName("tempDir() returns configured path")
        void tempDir() {
            assertThat(TEST_PATHS.tempDir()).isEqualTo(Path.of("/var/lib/homesynapse/tmp"));
        }
    }

    @Nested
    @DisplayName("All paths are non-null")
    class NonNullTests {

        @Test
        @DisplayName("all 6 path methods return non-null")
        void allPathsNonNull() {
            assertThat(TEST_PATHS.binaryDir()).isNotNull();
            assertThat(TEST_PATHS.configDir()).isNotNull();
            assertThat(TEST_PATHS.dataDir()).isNotNull();
            assertThat(TEST_PATHS.logDir()).isNotNull();
            assertThat(TEST_PATHS.backupDir()).isNotNull();
            assertThat(TEST_PATHS.tempDir()).isNotNull();
        }
    }

    @Nested
    @DisplayName("All paths are distinct")
    class DistinctTests {

        @Test
        @DisplayName("all 6 directories are different paths")
        void allPathsDistinct() {
            Path[] paths = {
                    TEST_PATHS.binaryDir(),
                    TEST_PATHS.configDir(),
                    TEST_PATHS.dataDir(),
                    TEST_PATHS.logDir(),
                    TEST_PATHS.backupDir(),
                    TEST_PATHS.tempDir()
            };
            // Verify no duplicates
            for (int i = 0; i < paths.length; i++) {
                for (int j = i + 1; j < paths.length; j++) {
                    assertThat(paths[i])
                            .as("path %d should differ from path %d", i, j)
                            .isNotEqualTo(paths[j]);
                }
            }
        }
    }

    @Nested
    @DisplayName("Idempotency")
    class IdempotencyTests {

        @Test
        @DisplayName("repeated calls return the same Path instance")
        void repeatedCallsSameInstance() {
            // PlatformPaths contract: paths are resolved once and cached
            assertThat(TEST_PATHS.binaryDir()).isSameAs(TEST_PATHS.binaryDir());
            assertThat(TEST_PATHS.configDir()).isSameAs(TEST_PATHS.configDir());
            assertThat(TEST_PATHS.dataDir()).isSameAs(TEST_PATHS.dataDir());
            assertThat(TEST_PATHS.logDir()).isSameAs(TEST_PATHS.logDir());
            assertThat(TEST_PATHS.backupDir()).isSameAs(TEST_PATHS.backupDir());
            assertThat(TEST_PATHS.tempDir()).isSameAs(TEST_PATHS.tempDir());
        }
    }
}
