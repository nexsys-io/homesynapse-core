/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.app;

import com.homesynapse.api.rest.OpaqueTokenStore;

import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The read-only {@code token status} runtime mode (R-6 TOKEN-OPS, 2026-08-22):
 * {@code homesynapse token status} prints every stored token's public summary
 * (key id, name, created, expires, scopes, site, state) plus the active/revoked
 * counts and the store path. Never a hash, never a raw token, never a write —
 * opening an {@link OpaqueTokenStore} only loads it; this class creates no
 * directory and touches no file.
 *
 * <p>{@code status} is the ONLY runtime verb. The mutating verbs
 * ({@code rotate} · {@code revoke <keyId>} · {@code mint <name>}) belong to the
 * packaged helper {@code homesynapse-token}, which writes the operator request
 * file the service consumes at its next start — the store file has one lawful
 * writer, the running service (see {@code distribution/docs/token-rotation.md}).</p>
 *
 * <p>{@code com.homesynapse.app..} — tests included — is whitelisted by
 * {@code HomeSynapseArchRules.NO_DIRECT_TIME_ACCESS}; the clock is injected anyway
 * (lane discipline, instruction §4c) so the class is testable with a fixed clock
 * and {@code Main.main} stays the single sanctioned {@code Clock.systemUTC()}
 * site.</p>
 */
final class TokenCli {

    /** Exit status for a completed {@code status}. */
    static final int EXIT_OK = 0;

    /** Exit status when the store exists but cannot be read (wrong user, or a corrupt path). */
    static final int EXIT_IO = 1;

    /** Exit status for a usage error (unknown or missing verb). */
    static final int EXIT_USAGE = 2;

    private static final String[] HEADERS =
            {"KEY_ID", "NAME", "CREATED", "EXPIRES", "SCOPES", "SITE", "STATE"};

    private static final String ABSENT = "-";

    private TokenCli() {
        // static entry points only
    }

    /**
     * Runs the CLI against the process streams.
     *
     * @param args      the full argument vector ({@code args[0] == "token"})
     * @param configDir the configuration directory the store lives under
     * @param clock     the injected clock (expiry evaluation)
     * @return the process exit status
     */
    static int run(String[] args, Path configDir, Clock clock) {
        return run(args, configDir, clock, System.out, System.err);
    }

    /**
     * Runs the CLI against explicit streams (the test seam).
     *
     * @param args      the full argument vector ({@code args[0] == "token"})
     * @param configDir the configuration directory the store lives under; never
     *                  {@code null} (need not exist — an absent store is an empty
     *                  table)
     * @param clock     the injected clock; never {@code null}
     * @param out       the table sink; never {@code null}
     * @param err       the usage sink; never {@code null}
     * @return {@link #EXIT_OK} after a printed status, {@link #EXIT_IO} when the store
     *         file exists but cannot be read (one line on {@code err}, no stack trace),
     *         {@link #EXIT_USAGE} otherwise
     */
    static int run(String[] args, Path configDir, Clock clock, PrintStream out, PrintStream err) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(configDir, "configDir");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        if (args.length != 2 || !"token".equals(args[0]) || !"status".equals(args[1])) {
            printUsage(err);
            return EXIT_USAGE;
        }
        // Read-only by construction: the constructor only load()s an existing store.
        OpaqueTokenStore store;
        try {
            store = new OpaqueTokenStore(configDir, clock);
        } catch (UncheckedIOException e) {
            // The store is 0600 inside a 0700 dir: the usual cause is the wrong user.
            err.println("token status: cannot read the token store at "
                    + configDir.resolve("api_tokens") + " (" + e.getCause().getClass().getSimpleName()
                    + "). Run as the service user: sudo homesynapse-token status");
            return EXIT_IO;
        }
        Instant now = clock.instant();
        List<String[]> rows = new ArrayList<>();
        int active = 0;
        int revoked = 0;
        for (OpaqueTokenStore.TokenSummary summary : store.summaries()) {
            String state;
            if (summary.revoked()) {
                state = "revoked";
                revoked++;
            } else if (summary.expiresAt() != null && !now.isBefore(summary.expiresAt())) {
                state = "expired";
            } else {
                state = "active";
                active++;
            }
            rows.add(new String[] {
                summary.keyId(),
                printable(summary.displayName()),
                summary.createdAt().toString(),
                summary.expiresAt() == null ? ABSENT : summary.expiresAt().toString(),
                printable(String.join(",", summary.scopes())),
                summary.siteId() == null ? ABSENT : printable(summary.siteId()),
                state});
        }
        printTable(out, rows);
        Path storePath = store.storePath();
        String storeNote = Files.exists(storePath) ? "" : " (absent — no token minted yet)";
        out.println("active: " + active + "  revoked: " + revoked + "  store: " + storePath
                + storeNote);
        return EXIT_OK;
    }

    private static void printTable(PrintStream out, List<String[]> rows) {
        int[] widths = new int[HEADERS.length];
        for (int i = 0; i < HEADERS.length; i++) {
            widths[i] = HEADERS[i].length();
        }
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                widths[i] = Math.max(widths[i], row[i].length());
            }
        }
        out.println(formatRow(HEADERS, widths));
        for (String[] row : rows) {
            out.println(formatRow(row, widths));
        }
    }

    /** Fixed-width columns, two-space separated, no trailing padding on the last column. */
    private static String formatRow(String[] cells, int[] widths) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                line.append("  ");
            }
            line.append(cells[i]);
            if (i < cells.length - 1) {
                line.append(" ".repeat(widths[i] - cells[i].length()));
            }
        }
        return line.toString();
    }

    /** Operator-controlled strings may carry control characters; keep the table one-line-per-row. */
    private static String printable(String value) {
        StringBuilder cleaned = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            cleaned.append(c < 0x20 || c == 0x7F ? '?' : c);
        }
        return cleaned.toString();
    }

    private static void printUsage(PrintStream err) {
        err.println("usage: homesynapse token status");
        err.println("  status   print every stored token (key id, name, created, expires, scopes,"
                + " site, state)");
        err.println("           and the active/revoked counts. Read-only; never prints a token"
                + " or a hash.");
        err.println("The mutating verbs are the packaged helper's, not the runtime's:");
        err.println("  sudo homesynapse-token rotate | revoke <keyId> | mint <name>");
        err.println("They queue config/token_ops.request and restart the service, which applies"
                + " it at startup.");
        err.println("Procedure: distribution/docs/token-rotation.md");
    }
}
