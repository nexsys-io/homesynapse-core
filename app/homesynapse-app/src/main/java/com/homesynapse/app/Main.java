/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.app;

import com.homesynapse.config.ScopeCipherResult;
import com.homesynapse.config.ScopeKeyManager;
import com.homesynapse.device.InMemoryDeviceRegistry;
import com.homesynapse.integration.zigbee.ZigbeeIntegrationFactory;
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
import java.util.List;
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
 * <p><strong>AB-1 boundary.</strong> {@code start()} opens the HTTP surface
 * behind bearer-token authentication, loopback-bound by default (core-review C1
 * closed inside {@code HomeSynapseCore}).</p>
 *
 * <p><strong>AB-4 boundary.</strong> The at-rest payload cipher is now
 * <em>live</em>: {@code main()} builds the {@link #payloadCipher(Path, Clock)}
 * adapter and passes it into the six-argument {@code HomeSynapseCore}
 * constructor, so {@code SqlitePersistenceLifecycle} enables at-rest encryption
 * for {@code [identity, presence_personal]} (encrypt-from-genesis — the
 * immutable log is encrypted from the first sensitive write).</p>
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

        // M9.4b §1 — the zigbee integration factory (constructed directly,
        // never discovered — no ServiceLoader). R4 UNIFIED (AB-3 substrate,
        // pm-handoff v18 beat 4): ONE app-constructed DeviceRegistry rides BOTH
        // paths — the factory supplier below AND the 8-arg HomeSynapseCore ctor
        // — so the registry the adapter adopts into IS the registry dispatch
        // resolution reads. It is NOT an IntegrationContext component (the
        // frozen 12); the ctor path carries it.
        Path zigbeeDataDir = baseDir.resolve("data").resolve("zigbee");
        Files.createDirectories(zigbeeDataDir);
        InMemoryDeviceRegistry zigbeeDeviceRegistry = new InMemoryDeviceRegistry();
        // M9.5-DUR (AMD-99): the registry projection is core-constructed (Phase 3
        // — it wraps the core-owned entity registry), so the factory receives it
        // through the same resolved-at-create() supplier shape as the device
        // registry. The one-element holder bridges the construction order (the
        // factory list is a core ctor argument); create() runs during start()
        // Phase 6, after the holder is set and after Phase 3 built the projection.
        HomeSynapseCore[] coreRef = new HomeSynapseCore[1];
        ZigbeeIntegrationFactory zigbeeFactory = new ZigbeeIntegrationFactory(
                () -> zigbeeDeviceRegistry, () -> coreRef[0].registryProjection(),
                zigbeeDataDir, clock);

        // AB-4 boundary: the at-rest payload cipher goes LIVE — the ctor passes
        // the payloadCipher(configDir, clock) adapter, flipping
        // SqlitePersistenceLifecycle's cipher-presence gate so encryption is
        // enabled for [identity, presence_personal] (Doc 15 §3.4, AMD-94).
        // The 8th argument is the R4 registry unification (M9.4b §1).
        HomeSynapseCore core = new HomeSynapseCore(
                dbPath, configDir, HomeSynapseConfig.HOME_DEFAULT, clock, homeId,
                payloadCipher(configDir, clock), List.of(zigbeeFactory),
                zigbeeDeviceRegistry);
        coreRef[0] = core;
        SystemLifecycleManager manager = core;

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

        // Synchronous, blocks until the engine reaches RUNNING; AB-1 opens the
        // HTTP surface behind auth (loopback-bound) during start() Phase 5.
        manager.start();

        // W10: the integrations.zigbee schema fragment registers AFTER Phase 6
        // (Doc 12 — only CORE schemas compose before config.load()); the adapter's
        // config subtree is served by the existing per-type ConfigurationAccess
        // scoping inside the supervisor assembly.
        core.registerIntegrationSchema(ZigbeeIntegrationFactory.INTEGRATION_TYPE,
                ZigbeeIntegrationFactory.configSchemaJson());
        System.out.println("HomeSynapse Core is RUNNING (phase=" + manager.currentPhase()
                + "); HTTP surface exposed behind bearer-token auth, loopback-bound (AB-1)."
                + " Send SIGTERM to stop.");

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
     * <p><strong>AB-4.</strong> This adapter is now wired into the runtime —
     * {@code main()} passes it into the six-argument {@code HomeSynapseCore}
     * constructor, activating at-rest encryption. The {@code aad} threaded
     * through both directions is the F1 envelope version byte, framed by the
     * persistence envelope codec and bound here into the GCM tag (downgrade
     * resistance). Package-private so the app-level bridge round-trip test
     * exercises the real adapter.</p>
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
            public EncryptedPayload encrypt(String scopeId, byte[] plaintext,
                                            byte[] aad) {
                // M6.3: counter-nonce payload path (Doc 15 §3.4), NOT the
                // random-IV encrypt() (that stays the M6.2 secrets path). The
                // aad (the F1 envelope version byte) is bound into the GCM tag.
                ScopeCipherResult result =
                        keyManager.encryptPayload(scopeId, plaintext, aad);
                return new EncryptedPayload(
                        result.ciphertext(), result.iv(), result.keyVersion());
            }

            @Override
            public byte[] decrypt(String scopeId, int keyVersion,
                                  byte[] ciphertext, byte[] iv, byte[] aad) {
                return keyManager.decrypt(scopeId, keyVersion, ciphertext, iv, aad);
            }
        };
    }
}
