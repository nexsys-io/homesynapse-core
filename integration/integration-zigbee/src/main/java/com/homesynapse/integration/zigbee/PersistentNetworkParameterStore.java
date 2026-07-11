/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.homesynapse.config.ScopeKeyManager;
import com.homesynapse.config.SecretStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The durable {@link NetworkParameterStore} (M9.4b §5.5) — replaces the M9.4a
 * in-memory store. Two custody surfaces, deliberately split:
 *
 * <ul>
 *   <li><strong>Parameters (non-secret):</strong> channel / PAN id / extended PAN
 *       id / the OPAQUE key reference, as JSON at
 *       {@code <dataDirectory>/zigbee-network.json} (written atomically:
 *       temp-then-move).</li>
 *   <li><strong>Network-key material (INV-SE-03):</strong> an INDEPENDENT
 *       {@link SecretStore} rooted at the zigbee data directory (its own
 *       {@code .root-key}/{@code scope_keys.json}/{@code secrets.enc} — zero
 *       sharing, zero cross-instance concurrency with the config-dir store), under
 *       the secret name {@code zigbee.network_key.<keyRef>}. Key bytes ride
 *       hex-encoded INSIDE the store (AES-256-GCM at rest) and NEVER appear in the
 *       parameters JSON, logs, exception messages, or {@code toString()}.</li>
 *   <li><strong>TCLK-seed material (M9.6-SEED, SD-5):</strong> the generated
 *       Trust Center link-key seed rides the SAME custody, hex-encoded under the
 *       fixed secret name {@link #TCLK_SEED_REF}. Its PRESENCE is the posture
 *       marker (DP-2): present ⇒ this custody's network was formed with the
 *       generated seed; absent ⇒ formed on the well-known root (the pre-SEED
 *       bench network). {@code zigbee-network.json} is unchanged — no schema or
 *       version field this WU (the FRAME-CTR row owns the future schema bump).</li>
 * </ul>
 *
 * <p><strong>Custody-corruption honesty:</strong> parameters present but key
 * missing is a CORRUPT custody state — {@code load()} returns the parameters and
 * the resume path's existing {@code loadNetworkKey(...).orElseThrow} semantics
 * surface it as PERMANENT (never a silent re-form: re-forming orphans the paired
 * fleet while looking alive — the never-false-ALIVE class). A parameters file that
 * EXISTS but cannot be parsed throws {@link IllegalStateException} naming the file
 * (never the key) — refusing to re-form over an existing network identity;
 * classifies TRANSIENT, surfacing via supervisor backoff until the operator acts
 * (a recorded limitation — see MODULE_CONTEXT). Only a genuinely absent
 * parameters file reads as first-run (form fresh).</p>
 *
 * <p>Thread-safe for the adapter's single-threaded lifecycle use; the underlying
 * {@link SecretStore} is itself thread-safe.</p>
 */
final class PersistentNetworkParameterStore implements NetworkParameterStore {

    /** The non-secret parameters file (the key NEVER rides in it — INV-SE-03). */
    static final String PARAMETERS_FILE_NAME = "zigbee-network.json";

    /** The secret-name prefix for network-key custody (M9.4b §5.5). */
    static final String KEY_NAME_PREFIX = "zigbee.network_key.";

    /**
     * The fixed secret name for TCLK-seed custody (M9.6-SEED DP-2, the
     * {@code NETWORK_KEY_REF} idiom). Presence = generated-seed posture.
     */
    static final String TCLK_SEED_REF = "zigbee.tclk_seed";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path dataDirectory;
    private final Path parametersFile;
    private final SecretStore secrets;

    /**
     * Creates the store. Construction touches no files (INV-RF-03/INV-CE-02) —
     * the parameters file and the key hierarchy come into existence on first
     * save; the data directory is created on the first mutation (the
     * {@code SecretStore} chain requires it to pre-exist — G12).
     *
     * @param dataDirectory the adapter data directory, never {@code null}
     * @param clock the injected time source (key-creation stamps), never {@code null}
     */
    PersistentNetworkParameterStore(Path dataDirectory, Clock clock) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        Objects.requireNonNull(clock, "clock");
        this.parametersFile = dataDirectory.resolve(PARAMETERS_FILE_NAME);
        this.secrets = SecretStore.create(dataDirectory,
                ScopeKeyManager.create(dataDirectory, clock), clock);
    }

    @Override
    public Optional<NetworkParameters> load() {
        if (!Files.exists(parametersFile)) {
            return Optional.empty();    // first run — §5.2 forms fresh
        }
        try {
            JsonNode root = MAPPER.readTree(
                    Files.readString(parametersFile, StandardCharsets.UTF_8));
            return Optional.of(new NetworkParameters(
                    root.path("channel").asInt(),
                    root.path("panId").asInt(),
                    root.path("extendedPanId").asLong(),
                    root.path("networkKeyRef").asText()));
        } catch (IOException | RuntimeException failure) {
            // Corrupt custody is NEVER silently re-formed over (re-forming orphans
            // the paired fleet while looking alive). Names the file, never any key.
            throw new IllegalStateException(
                    "zigbee network parameter file is corrupt or unreadable: "
                            + parametersFile
                            + " — refusing to re-form over an existing network identity",
                    failure);
        }
    }

    @Override
    public void save(NetworkParameters parameters) {
        Objects.requireNonNull(parameters, "parameters");
        ObjectNode root = MAPPER.createObjectNode();
        root.put("channel", parameters.channel());
        root.put("panId", parameters.panId());
        root.put("extendedPanId", parameters.extendedPanId());
        root.put("networkKeyRef", parameters.networkKeyRef());
        try {
            Files.createDirectories(dataDirectory);
            Path temp = dataDirectory.resolve(PARAMETERS_FILE_NAME + ".tmp");
            Files.writeString(temp, root.toPrettyString(), StandardCharsets.UTF_8);
            Files.move(temp, parametersFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "failed to persist zigbee network parameters to " + parametersFile,
                    failure);
        }
    }

    @Override
    public void saveNetworkKey(String keyRef, byte[] keyMaterial) {
        Objects.requireNonNull(keyRef, "keyRef");
        Objects.requireNonNull(keyMaterial, "keyMaterial");
        ensureDataDirectory();
        secrets.set(KEY_NAME_PREFIX + keyRef, HexFormat.of().formatHex(keyMaterial));
    }

    @Override
    public Optional<byte[]> loadNetworkKey(String keyRef) {
        Objects.requireNonNull(keyRef, "keyRef");
        String name = KEY_NAME_PREFIX + keyRef;
        if (!secrets.list().contains(name)) {
            return Optional.empty();
        }
        return Optional.of(HexFormat.of().parseHex(secrets.resolve(name)));
    }

    @Override
    public void saveTclkSeed(byte[] seedMaterial) {
        Objects.requireNonNull(seedMaterial, "seedMaterial");
        ensureDataDirectory();
        secrets.set(TCLK_SEED_REF, HexFormat.of().formatHex(seedMaterial));
    }

    @Override
    public Optional<byte[]> loadTclkSeed() {
        if (!secrets.list().contains(TCLK_SEED_REF)) {
            return Optional.empty();
        }
        return Optional.of(HexFormat.of().parseHex(secrets.resolve(TCLK_SEED_REF)));
    }

    @Override
    public void saveNetworkKeyAndTclkSeed(String keyRef, byte[] keyMaterial,
            byte[] seedMaterial) {
        Objects.requireNonNull(keyRef, "keyRef");
        Objects.requireNonNull(keyMaterial, "keyMaterial");
        Objects.requireNonNull(seedMaterial, "seedMaterial");
        ensureDataDirectory();
        // The AMD-68 never-torn write (DP-4): both secrets durable or neither.
        secrets.setAll(Map.of(
                KEY_NAME_PREFIX + keyRef, HexFormat.of().formatHex(keyMaterial),
                TCLK_SEED_REF, HexFormat.of().formatHex(seedMaterial)));
    }

    private void ensureDataDirectory() {
        try {
            // The SecretStore chain never mkdirs (G12) — the first mutation
            // creates the temp/store files INSIDE the directory.
            Files.createDirectories(dataDirectory);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "failed to create the zigbee data directory " + dataDirectory,
                    failure);
        }
    }
}
