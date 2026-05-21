/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.homesynapse.event.bus.SubscriberReadConnectionFactory;
import com.homesynapse.event.bus.SubscriberReadExecutor;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * Production {@link SubscriberReadConnectionFactory} that opens a dedicated
 * SQLite read connection per subscriber (INV-SUB-ISO-02, AMD-26/27, M3.6d-b
 * Gap 3).
 *
 * <p>Each call to {@link #create(String)} opens a new SQLite read connection
 * to the same database file, applies the LTD-03 connection PRAGMAs
 * (rendered by {@link DatabaseExecutor#connectionPragmas(DeploymentProfile)}
 * — the single source of PRAGMA truth), and wraps the connection in a
 * {@link SqliteSubscriberReadExecutor} bound to a dedicated daemon
 * platform thread named {@code "hs-sub-read-<subscriberId>"}.</p>
 *
 * <p><strong>Connection isolation.</strong> The per-subscriber connection is
 * never shared with the {@link DatabaseExecutor}'s read pool. SQLite's WAL
 * mode permits concurrent readers, and each subscriber's checkpoint cadence
 * is owned by its own connection so a slow subscriber cannot stall
 * {@code SqliteEventStore}'s pool or any other subscriber.</p>
 *
 * <p>Package-private — composition wiring constructs the factory and exposes
 * it through the public {@link SubscriberReadConnectionFactory} interface to
 * {@code InProcessEventBus}.</p>
 *
 * @see SqliteSubscriberReadExecutor
 * @see DatabaseExecutor#connectionPragmas(DeploymentProfile)
 */
final class SqliteSubscriberReadConnectionFactory implements SubscriberReadConnectionFactory {

    private final Path dbPath;
    private final DeploymentProfile profile;

    /**
     * Constructs the factory.
     *
     * @param dbPath  full path to the SQLite database file; never {@code null}
     * @param profile deployment profile supplying the PRAGMA values; never
     *                {@code null}
     */
    SqliteSubscriberReadConnectionFactory(Path dbPath, DeploymentProfile profile) {
        this.dbPath = Objects.requireNonNull(dbPath, "dbPath");
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    @Override
    public SubscriberReadExecutor create(String subscriberId) {
        Objects.requireNonNull(subscriberId, "subscriberId");

        String jdbcUrl = "jdbc:sqlite:" + dbPath;
        Connection connection;
        try {
            connection = DriverManager.getConnection(jdbcUrl);
        } catch (SQLException e) {
            throw new RuntimeException(
                    "Failed to open subscriber read connection: subscriberId="
                            + subscriberId + ", path=" + dbPath, e);
        }

        try (Statement stmt = connection.createStatement()) {
            for (String pragma : DatabaseExecutor.connectionPragmas(profile)) {
                stmt.execute("PRAGMA " + pragma);
            }
        } catch (SQLException e) {
            closeQuietly(connection);
            throw new RuntimeException(
                    "Failed to apply PRAGMAs to subscriber read connection: subscriberId="
                            + subscriberId, e);
        }

        return new SqliteSubscriberReadExecutor(connection, subscriberId);
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Already failing — discard secondary error.
        }
    }
}
