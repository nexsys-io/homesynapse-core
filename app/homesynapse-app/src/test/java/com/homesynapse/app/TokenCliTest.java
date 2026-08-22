/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.app;

import com.homesynapse.api.rest.ApiKeyClaims;
import com.homesynapse.api.rest.OpaqueTokenStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the read-only {@code token status} runtime mode ({@link TokenCli},
 * R-6 TOKEN-OPS). A fixed clock is injected: {@code com.homesynapse.app..}
 * (tests included) is whitelisted by {@code NO_DIRECT_TIME_ACCESS}, so this is
 * lane discipline rather than an enforced rule — the CLI is designed to take the
 * clock rather than source it. The store is seeded through the real
 * {@link OpaqueTokenStore} in a temp dir.
 */
@DisplayName("TokenCli -- `homesynapse token status` (read-only)")
final class TokenCliTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-08-22T12:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path configDir;

    TokenCliTest() {
    }

    private record Run(int exit, String out, String err) {
    }

    private Run run(String... args) {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        int exit;
        try (PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8)) {
            exit = TokenCli.run(args, configDir, FIXED_CLOCK, out, err);
        }
        return new Run(exit, outBytes.toString(StandardCharsets.UTF_8),
                errBytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("status prints the fixed-width table (one row per stored token, revoked rows "
            + "included) and the active/revoked/store trailer — never a token or a hash")
    void statusPrintsTableAndTrailer() {
        OpaqueTokenStore store = new OpaqueTokenStore(configDir, FIXED_CLOCK);
        String live = store.mint("Companion app", List.of(ApiKeyClaims.SCOPE_ALL), null);
        String dead = store.mint("old laptop", List.of("entities:read"), "site-1");
        String liveKey = store.validate(live).orElseThrow().keyId();
        String deadKey = store.validate(dead).orElseThrow().keyId();
        store.revoke(deadKey);

        Run result = run("token", "status");

        assertThat(result.exit()).isEqualTo(TokenCli.EXIT_OK);
        assertThat(result.err()).isEmpty();
        List<String> lines = result.out().lines().toList();
        assertThat(lines).hasSize(4);
        assertThat(lines.get(0)).startsWith("KEY_ID")
                .contains("NAME", "CREATED", "EXPIRES", "SCOPES", "SITE", "STATE");
        String liveRow = lines.stream().filter(l -> l.startsWith(liveKey)).findFirst().orElseThrow();
        assertThat(liveRow).contains("Companion app", "2026-08-22T12:00:00Z", "*")
                .endsWith("active");
        String deadRow = lines.stream().filter(l -> l.startsWith(deadKey)).findFirst().orElseThrow();
        assertThat(deadRow).contains("old laptop", "entities:read", "site-1").endsWith("revoked");
        // Fixed-width: STATE starts at the same column on every data row.
        assertThat(liveRow.indexOf("active")).isEqualTo(deadRow.indexOf("revoked"));
        assertThat(lines.get(3)).isEqualTo(
                "active: 1  revoked: 1  store: " + configDir.resolve("api_tokens"));
        assertThat(result.out()).doesNotContain(live, dead);
        assertThat(result.out()).doesNotContainPattern("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("status over a directory with no store prints the header, an empty table, and "
            + "an absent-store trailer — exit 0")
    void statusOnEmptyDirPrintsEmptyTable() {
        Run result = run("token", "status");

        assertThat(result.exit()).isEqualTo(TokenCli.EXIT_OK);
        List<String> lines = result.out().lines().toList();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).startsWith("KEY_ID");
        assertThat(lines.get(1))
                .startsWith("active: 0  revoked: 0  store: " + configDir.resolve("api_tokens"))
                .endsWith("(absent — no token minted yet)");
    }

    @Test
    @DisplayName("status performs no writes: no store, no artifact, no request file, no temp "
            + "sibling appears in the config dir")
    void statusPerformsNoWrites() throws Exception {
        Run result = run("token", "status");

        assertThat(result.exit()).isEqualTo(TokenCli.EXIT_OK);
        try (Stream<Path> entries = Files.list(configDir)) {
            assertThat(entries).isEmpty();
        }
    }

    @Test
    @DisplayName("a store that exists but cannot be read is one Register-C line on stderr and "
            + "exit 1 — never a stack trace")
    void statusOnUnreadableStorePrintsOneLine() throws Exception {
        // A DIRECTORY at the store path: readAllLines fails on every platform, so this
        // pins the arm without POSIX permission bits (the production case is the wrong user).
        Files.createDirectories(configDir.resolve("api_tokens"));

        Run result = run("token", "status");

        assertThat(result.exit()).isEqualTo(TokenCli.EXIT_IO);
        assertThat(result.out()).isEmpty();
        assertThat(result.err().lines().toList()).hasSize(1);
        assertThat(result.err())
                .startsWith("token status: cannot read the token store at ")
                .contains("sudo homesynapse-token status")
                .doesNotContain("Exception:", "\tat ");
    }

    @Test
    @DisplayName("any verb other than `status` — including the helper's rotate/revoke/mint — "
            + "prints the usage block to stderr and exits 2")
    void unknownVerbExits2() {
        for (String[] args : new String[][] {
                {"token"}, {"token", "rotate"}, {"token", "revoke", "abc"},
                {"token", "mint", "x"}, {"token", "status", "extra"}, {"token", "--help"}}) {
            Run result = run(args);

            assertThat(result.exit()).as(String.join(" ", args)).isEqualTo(TokenCli.EXIT_USAGE);
            assertThat(result.out()).as(String.join(" ", args)).isEmpty();
            assertThat(result.err()).as(String.join(" ", args))
                    .startsWith("usage: homesynapse token status")
                    .contains("sudo homesynapse-token rotate | revoke <keyId> | mint <name>");
        }
    }
}
