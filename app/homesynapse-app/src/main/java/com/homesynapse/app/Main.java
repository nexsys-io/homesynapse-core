/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.app;

import com.homesynapse.config.ScopeCipherResult;
import com.homesynapse.config.ScopeKeyManager;
import com.homesynapse.lifecycle.HomeSynapseConfig;
import com.homesynapse.lifecycle.HomeSynapseCore;
import com.homesynapse.lifecycle.SystemLifecycleManager;
import com.homesynapse.persistence.EncryptedPayload;
import com.homesynapse.persistence.PayloadCipher;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.platform.identity.UlidFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;

/**
 * Application entry point for HomeSynapse Core (AB-3).
 *
 * <p>{@code main()} is the composition-root host: it resolves the runtime
 * directories, constructs {@link HomeSynapseCore} held as a
 * {@link SystemLifecycleManager} (PD-1), registers a SIGTERM shutdown hook that
 * calls {@link SystemLifecycleManager#shutdown(String)}, and calls
 * {@link SystemLifecycleManager#start()} on the platform main thread (LTD-19).</p>
 *
 * <p><strong>AB-3 boundary.</strong> The boot opens <em>no</em> HTTP surface
 * (core-review C1 — {@code HomeSynapseCore.exposeHttpSurface()} is the AB-1 seam,
 * not called here) and leaves the at-rest payload cipher <em>inert</em> (the
 * {@link #payloadCipher(Path, Clock)} adapter is built only when AB-4 activates
 * encryption; AB-3 passes no cipher).</p>
 */
public final class Main {

    private Main() {
        // Entry point only — no instantiation
    }

    public static void main(String[] args) throws Exception {
        // Composition-root entry point: Clock.systemUTC() is whitelisted here —
        // com.homesynapse.app is excluded from the NO_DIRECT_TIME_ACCESS rule, so
        // this is the single sanctioned place to source the real clock.
        Clock clock = Clock.systemUTC();

        Path baseDir = resolveBaseDir();
        Path configDir = baseDir.resolve("config");
        Path dbPath = baseDir.resolve("data").resolve("homesynapse-events.db");
        Files.createDirectories(configDir);
        Files.createDirectories(dbPath.getParent());

        // Durable installation identity (AMD-34): home_id is stamped on every
        // persisted event, so it MUST be stable across restarts. Read the
        // home_id file if present, else mint one and persist it.
        HomeId homeId = resolveHomeId(configDir, clock);

        // AB-3 boundary: the at-rest payload cipher stays INERT — the five-arg
        // ctor is reserved for AB-4, which will pass payloadCipher(configDir, clock).
        SystemLifecycleManager manager = new HomeSynapseCore(
                dbPath, configDir, HomeSynapseConfig.HOME_DEFAULT, clock, homeId);

        CountDownLatch shutdownLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                manager.shutdown("SIGTERM");
            } catch (Exception e) {
                System.err.println("HomeSynapse Core shutdown failed: " + e.getMessage());
            } finally {
                shutdownLatch.countDown();
            }
        }, "hs-shutdown"));

        // Synchronous, blocks until the engine reaches RUNNING; HTTP not exposed.
        manager.start();
        System.out.println("HomeSynapse Core is RUNNING (phase=" + manager.currentPhase()
                + "); HTTP surface not exposed (AB-3 C1 boundary). Send SIGTERM to stop.");

        // Park the non-daemon main thread until SIGTERM fires the shutdown hook —
        // the health loop and bus delivery run on virtual threads, which do not
        // keep the JVM alive on their own.
        try {
            shutdownLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Resolves the runtime base directory: {@code $HOMESYNAPSE_HOME} if set, else
     * {@code <user.dir>/.homesynapse}. PlatformPaths-based resolution
     * (LinuxSystemPaths/LocalPaths) replaces this when those impls are wired.
     *
     * @return the base directory; never {@code null}
     */
    private static Path resolveBaseDir() {
        String home = System.getenv("HOMESYNAPSE_HOME");
        if (home != null && !home.isBlank()) {
            return Path.of(home);
        }
        return Path.of(System.getProperty("user.dir"), ".homesynapse");
    }

    /**
     * Reads the durable installation {@link HomeId} from {@code configDir/home_id}
     * if present, else mints one and persists it. This keeps {@code home_id}
     * (AMD-34 — stamped on every persisted event) STABLE across restarts; a fresh
     * random id per boot would re-identify the installation on every restart.
     *
     * @param configDir the configuration directory (already created); never {@code null}
     * @param clock     the injected clock for minting a new id; never {@code null}
     * @return the stable home identity; never {@code null}
     * @throws IOException if the home_id file cannot be read or written
     */
    private static HomeId resolveHomeId(Path configDir, Clock clock) throws IOException {
        Path idFile = configDir.resolve("home_id");
        if (Files.exists(idFile)) {
            return HomeId.of(Ulid.parse(Files.readString(idFile).trim()));
        }
        HomeId homeId = HomeId.of(UlidFactory.generate(clock));
        Files.writeString(idFile, homeId.value().toString());
        return homeId;
    }

    /**
     * Builds the M6.2/M6.3 E2 bridge (Doc 15 §3.8 / CARRY 1): constructs the
     * config-resident {@link ScopeKeyManager} and wraps it in a thin adapter of
     * the persistence-defined {@link PayloadCipher} seam.
     *
     * <p>{@code com.homesynapse.app} is the only module that requires both
     * {@code com.homesynapse.config} and {@code com.homesynapse.persistence}, so
     * this adapter closes the key-management/encryption cycle with zero new
     * module edges (the AMD-45 injection-at-the-composition-root discipline).</p>
     *
     * <p><strong>AB-3.</strong> This adapter is NOT wired into the runtime yet —
     * AB-3 leaves the at-rest cipher inert. AB-4 passes the adapter into the
     * five-argument {@code HomeSynapseCore} constructor. Package-private so the
     * app-level bridge round-trip test exercises the real adapter.</p>
     *
     * @param configDir the resolved configuration directory the key files live
     *                  under; never {@code null}
     * @param clock     time source for scope-key creation stamps; never
     *                  {@code null}
     * @return the adapter, ready to inject into {@code HomeSynapseCore}; never
     *         {@code null}
     */
    static PayloadCipher payloadCipher(Path configDir, Clock clock) {
        ScopeKeyManager keyManager = ScopeKeyManager.create(configDir, clock);
        return new PayloadCipher() {
            @Override
            public EncryptedPayload encrypt(String scopeId, byte[] plaintext) {
                // M6.3: counter-nonce payload path (Doc 15 §3.4), NOT the
                // random-IV encrypt() (that stays the M6.2 secrets path).
                ScopeCipherResult result = keyManager.encryptPayload(scopeId, plaintext);
                return new EncryptedPayload(
                        result.ciphertext(), result.iv(), result.keyVersion());
            }

            @Override
            public byte[] decrypt(String scopeId, int keyVersion,
                                  byte[] ciphertext, byte[] iv) {
                return keyManager.decrypt(scopeId, keyVersion, ciphertext, iv);
            }
        };
    }
}
