/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

/**
 * Injection seam for projection checkpoint data serialization and projection
 * version recovery (AMD-41 §3.2.3–3.2.4).
 *
 * <p>{@code StateCheckpointSource} decouples {@link StateProjection} from the
 * persistence module's {@code SqliteStateStore}: the projection holds an
 * interface reference, and the composition root supplies an implementation
 * backed by the SQLite-resident state map and Jackson serializer. The seam
 * follows the same pattern as {@link DerivedPublishGate} (M3.5a) — a small
 * interface in state-store that the composition root satisfies with a
 * persistence-module implementation, keeping state-store free of any
 * persistence-module dependency.</p>
 *
 * <h2>Why a separate interface (not {@link StateStore})</h2>
 *
 * <p>The {@link StateStore} port models the four core entity-state operations
 * ({@code get}, {@code put}, {@code getAll}, {@code clear}). Checkpoint
 * serialization is a distinct concern: only the persistent
 * {@code SqliteStateStore} can produce a checkpoint byte representation; the
 * M3.5a {@code InMemoryStateStore} test fixture has nothing to serialize.
 * Splitting the interfaces keeps {@code StateStore} focused on the read/write
 * port shape and avoids forcing in-memory fixtures to implement serialization
 * methods they have no use for.</p>
 *
 * <h2>Composition-root wiring</h2>
 *
 * <p>The persistence module's {@code SqliteStateStore} already exposes
 * package-private {@code serialize(int)} and {@code loadedProjectionVersion()}
 * methods carrying the Jackson-serialized checkpoint data and the version
 * recovered from that payload. A future work unit will have
 * {@code SqliteStateStore} declare {@code implements StateCheckpointSource}
 * and promote those methods to public visibility. The interface method
 * {@link #serializeCheckpoint(int)} is deliberately named to avoid a JPMS
 * visibility-clash with {@code SqliteStateStore.serialize(int)} during that
 * promotion; {@link #loadedProjectionVersion()} matches the existing
 * {@code SqliteStateStore} method name and therefore only requires a
 * visibility promotion when the implementation lands.</p>
 *
 * <h2>Reconciliation source of truth (AMD-41 §3.2.4)</h2>
 *
 * <p>{@link CheckpointRecord#projectionVersion()} is a sentinel hardcoded to
 * {@code 1} by both {@code InMemoryViewCheckpointStore} and
 * {@code SqliteViewCheckpointStore}. The authoritative version lives inside
 * the opaque {@code data} byte payload that this interface produces and
 * recovers. {@link StateProjection#initialize()} consults
 * {@link #loadedProjectionVersion()} when deciding whether to run the
 * reconciliation pass.</p>
 *
 * @see StateProjection
 * @see DerivedPublishGate
 * @see ViewCheckpointStore
 */
public interface StateCheckpointSource {

    /**
     * Serializes the current materialized state for checkpoint persistence.
     *
     * <p>Called by {@link StateProjection} at checkpoint cadence (per the
     * active {@link CheckpointPolicy}). The returned bytes are written to
     * {@link ViewCheckpointStore#writeCheckpoint(String, long, byte[])
     * ViewCheckpointStore.writeCheckpoint} as the opaque data payload.</p>
     *
     * <p>Implementations that do not support serialization (e.g., the
     * {@link #stub()} fixture and any in-memory-only deployment) return an
     * empty array.</p>
     *
     * @param projectionVersion the projection's compile-time version constant.
     *                          Production implementations embed this in the
     *                          serialized payload so {@link #loadedProjectionVersion()}
     *                          can recover it on a subsequent boot.
     * @return serialized checkpoint data; never {@code null}
     */
    byte[] serializeCheckpoint(int projectionVersion);

    /**
     * Returns the projection version recovered from the most recently loaded
     * checkpoint, or {@code 0} if no checkpoint was loaded.
     *
     * <p>This is the authoritative version for reconciliation (AMD-41
     * §3.2.4). The sentinel exposed by {@link CheckpointRecord#projectionVersion()}
     * MUST NOT be used for reconciliation — both
     * {@code InMemoryViewCheckpointStore} and {@code SqliteViewCheckpointStore}
     * hardcode it to {@code 1}.</p>
     *
     * @return the version embedded in the loaded checkpoint payload, or
     *         {@code 0} when no checkpoint has been loaded (no prior
     *         persistence, or stub implementation)
     */
    int loadedProjectionVersion();

    /**
     * Returns a no-op {@code StateCheckpointSource} that serializes to an
     * empty byte array and reports {@code loadedProjectionVersion() == 0}.
     *
     * <p>Suitable for tests and in-memory-only deployments where checkpoint
     * persistence is not needed. Preserves the M3.5a {@code byte[0]} behavior
     * so existing tests that did not depend on real checkpoint data continue
     * to pass.</p>
     *
     * <p><b>Reconciliation note:</b> when paired with a projection that loads
     * a pre-existing checkpoint, the stub's {@code loadedProjectionVersion() == 0}
     * triggers reconciliation against any projection whose own version is
     * greater than {@code 0}. Tests that seed a checkpoint and expect "no
     * reconciliation" must supply a source whose {@code loadedProjectionVersion()}
     * matches the projection's own version.</p>
     *
     * @return a stub source that returns {@code new byte[0]} and version {@code 0}
     */
    static StateCheckpointSource stub() {
        return new StateCheckpointSource() {
            @Override
            public byte[] serializeCheckpoint(int projectionVersion) {
                return new byte[0];
            }

            @Override
            public int loadedProjectionVersion() {
                return 0;
            }
        };
    }
}
