/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.homesynapse.device.InMemoryDeviceRegistry;
import com.homesynapse.event.EventStore;
import com.homesynapse.persistence.PersistenceConfig;
import com.homesynapse.persistence.PersistenceFactory;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.test.TestClock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * REGISTRY-COLD-1 (IR-148) K-4 — THE REAL-STORE COMPARISON, OPT-IN: on a COPY of a real event
 * store (BACKUP-1's copy of the Pi's 1.65 M-row store is the one that matters — BC10's
 * precondition), the registries rebuilt the NEW way ({@code rebuildFromIndex}, the four types
 * through {@code idx_events_type} up to H) must EQUAL the registries rebuilt by the full replay
 * (the OLD way: every row of the {@code readFrom} page walk, filtered, to {@code onEvent}).
 *
 * <p>It runs only when {@code -Dhomesynapse.registryCold.store=<path>} names the store, or the
 * environment variable {@code HOMESYNAPSE_REGISTRY_COLD_STORE} does: the Gradle test task forwards
 * the environment to the forked test JVM but not a {@code -D} from the {@code gradlew} line (the
 * FIX-2b-i passthrough note in {@code build-logic}), so on a desk the variable is the path that
 * reaches the test. Otherwise JUnit {@code Assumptions} skip it — CI runs it SKIPPED.</p>
 *
 * <p>The given file and any {@code -wal} beside it are copied into the test's {@code @TempDir}
 * (deleted after the run — a ~830 MB copy needs the space) and the COPY is opened: opening runs
 * the migrations and the PRAGMAs, so the source is never written. The source must be a file no
 * running Core has open — a file-level copy of an open WAL database is not a consistent snapshot;
 * the Pi's live store is not such a file. The store is opened with the Pi's own profile
 * ({@link PersistenceConfig#HOME_DEFAULT}) and no cipher: the four registry types are plaintext
 * scopes, but the OLD way reads EVERY row, so a store holding an encrypted-scope row
 * ({@code presence_signal} / {@code presence_changed}) fails that walk loudly with
 * {@code PayloadDecryptionException} rather than comparing against a silent skip.</p>
 *
 * <p>Both ways print their rows and wall-clock milliseconds. The stopwatch is the one the
 * MEASURE-2b read-path IT uses ({@code MeasureReadPathIT.stopwatchNanos}): wall time of a read,
 * never domain time — the domain clock stays the frozen {@link TestClock}.</p>
 */
@DisplayName("RegistryRealStoreComparisonIT — K-4 (opt-in): on a copy of a real store, the"
        + " registries rebuilt from the type index equal the registries rebuilt by the full replay")
final class RegistryRealStoreComparisonIT {

    static final String STORE_PROPERTY = "homesynapse.registryCold.store";
    static final String STORE_ENV = "HOMESYNAPSE_REGISTRY_COLD_STORE";

    /** Reads never bind a home id; the copy is never written. */
    private static final HomeId READ_ONLY_HOME_ID =
            HomeId.of(Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAAK"));

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    RegistryRealStoreComparisonIT() {
    }

    @Test
    @DisplayName("K-4: the real store, copied and opened — rebuilt the old way and the new way,"
            + " the device and entity registries are EQUAL record for record; rows and ms printed")
    void realStore_rebuiltBothWays_registriesEqual(@TempDir Path tempDir) throws Exception {
        String given = System.getProperty(STORE_PROPERTY, System.getenv(STORE_ENV));
        assumeTrue(given != null && !given.isBlank(), "opt-in only: -D" + STORE_PROPERTY
                + "=<path> (or the environment variable " + STORE_ENV + ")");
        Path source = Path.of(given);
        assumeTrue(Files.isRegularFile(source), "not a file: " + source);

        Path copy = tempDir.resolve(RegistryColdBootIT.DB_FILE);
        Files.copy(source, copy);
        Path sourceWal = source.resolveSibling(source.getFileName() + "-wal");
        long walBytes = 0L;
        if (Files.isRegularFile(sourceWal)) {
            Files.copy(sourceWal, tempDir.resolve(RegistryColdBootIT.DB_FILE + "-wal"));
            walBytes = Files.size(sourceWal);
        }
        System.out.println("registry-cold.k4: source=" + source + " bytes=" + Files.size(source)
                + " wal_bytes=" + walBytes);

        try (PersistenceFactory factory = PersistenceFactory.start(copy,
                PersistenceConfig.HOME_DEFAULT, TestClock.createDefault(), READ_ONLY_HOME_ID,
                RegistryColdBootIT.eventClasses(), null)) {
            EventStore store = factory.eventStore();
            long head = store.latestPosition();

            long oldStart = stopwatchNanos();
            RegistryColdBootIT.OldWay old =
                    RegistryColdBootIT.rebuildTheOldWay(store, new InMemoryDeviceRegistry());
            long oldMillis = (stopwatchNanos() - oldStart) / 1_000_000L;

            long newStart = stopwatchNanos();
            RegistryColdBootIT.NewWay fresh =
                    RegistryColdBootIT.rebuildTheNewWay(store, head, new InMemoryDeviceRegistry());
            long newMillis = (stopwatchNanos() - newStart) / 1_000_000L;

            System.out.println("registry-cold.k4: head=" + head
                    + " old_way: rows_walked=" + old.rowsWalked() + " parked=" + old.parked()
                    + " suspended=" + old.suspended() + " ms=" + oldMillis
                    + " | new_way: rows_passed=" + fresh.passed() + " skipped=" + fresh.skipped()
                    + " ms=" + newMillis
                    + " | devices=" + fresh.devices().size()
                    + " entities=" + fresh.entities().size());

            assertThat(old.suspended()).as("the full replay must itself complete").isFalse();
            assertThat(fresh.devices())
                    .as("device registry: the new way equals the full replay, record for record")
                    .containsExactlyInAnyOrderElementsOf(old.devices());
            assertThat(fresh.entities())
                    .as("entity registry: the new way equals the full replay, record for record")
                    .containsExactlyInAnyOrderElementsOf(old.entities());
            assertThat(fresh.devices())
                    .as("a real store carries at least one registered device — an empty "
                            + "comparison compares nothing")
                    .isNotEmpty();
        }
    }

    // MEASURE-2b stopwatch (MeasureReadPathIT.stopwatchNanos): wall time of a read; not domain
    // time (the domain clock is the frozen TestClock).
    private static long stopwatchNanos() {
        return System.nanoTime();
    }
}
