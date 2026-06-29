/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;

/**
 * The module's single YAML emission and atomic file-replacement point
 * (Doc 06 §3.5 step 6, §6.8; DP-6 — keep emission in one place).
 *
 * <h2>Atomicity discipline</h2>
 *
 * <p>{@link #writeAtomically} writes the full content to a same-directory
 * {@code <name>.tmp} sibling, fsyncs it, and renames it over the target
 * with {@code ATOMIC_MOVE} (POSIX {@code rename(2)}; Windows
 * {@code MoveFileEx(MOVEFILE_REPLACE_EXISTING)} — both replace an existing
 * target atomically). A failure at any step deletes the temp file
 * best-effort and leaves the prior target byte-identical (§6.8). The
 * containing directory is fsynced best-effort afterwards so the rename's
 * directory entry is durable on POSIX filesystems.</p>
 *
 * <h2>Emission</h2>
 *
 * <p>{@link #emit} dumps block-style YAML via snakeyaml-engine. The
 * emitter needs no scalar schema (the Core-schema discipline, DP-12,
 * governs LOAD settings only); comment and formatting preservation is the
 * documented Locked-doc limitation of programmatic writes (Doc 06 §3.5 —
 * out of scope until the post-MVP comment-preserving emitter).</p>
 */
final class AtomicYamlWriter {

    private static final Logger log = LoggerFactory.getLogger(AtomicYamlWriter.class);

    private static final String TMP_SUFFIX = ".tmp";

    private AtomicYamlWriter() {
        // Utility class — non-instantiable
    }

    /**
     * Emits the document as block-style YAML.
     *
     * @param document the string-keyed document tree; never {@code null}
     * @return the YAML text; never {@code null}
     */
    static String emit(Map<String, Object> document) {
        Objects.requireNonNull(document, "document must not be null");
        DumpSettings settings = DumpSettings.builder()
                .setDefaultFlowStyle(FlowStyle.BLOCK)
                .build();
        return new Dump(settings).dumpToString(document);
    }

    /**
     * Atomically replaces {@code target} with {@code content}:
     * write-temp → fsync → rename → best-effort fsync-directory.
     *
     * <p>The directory fsync is best-effort: a failure (including a platform
     * that cannot open a directory channel — Windows/non-POSIX) is logged at
     * DEBUG and tolerated. Durability-critical callers (the nonce-counter
     * store) MUST use {@link #writeAtomicallyDurable} instead.</p>
     *
     * @param target  the file to replace; never {@code null}
     * @param content the full new content; never {@code null}
     * @throws IOException on any step's failure — the prior {@code target}
     *                     is intact and the temp file is removed
     *                     best-effort (§6.8)
     */
    static void writeAtomically(Path target, String content) throws IOException {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(content, "content must not be null");
        replaceAtomically(target, content);
        fsyncDirectoryBestEffort(target.getParent());
    }

    /**
     * Atomically replaces {@code target} with {@code content} like
     * {@link #writeAtomically}, but FAILS CLOSED on a confirmed directory-entry
     * fsync failure (F13b / AB-4).
     *
     * <p>For a durability-critical store — the OR-M6-NONCE high-water mark — a
     * lost directory fsync means a crash could surface the pre-rename file,
     * resetting the counter and replaying a nonce (catastrophic GCM
     * {@code (key, nonce)} reuse). So a directory channel that opens but whose
     * {@code force()} fails (a genuine POSIX fsync failure) throws an
     * {@link UncheckedIOException} — the encrypt must not proceed. A platform
     * that cannot open a directory channel at all (Windows/non-POSIX) is NOT a
     * failure: there is no directory fsync to perform and durability rides the
     * metadata journal, so the write completes normally (the Windows/non-POSIX
     * dev path is not broken).</p>
     *
     * @param target  the file to replace; never {@code null}
     * @param content the full new content; never {@code null}
     * @throws IOException           on a pre-rename failure (temp create/write,
     *                               or the rename) — the prior {@code target}
     *                               is intact
     * @throws UncheckedIOException  on a confirmed directory-entry fsync failure
     *                               after a successful rename — fail closed
     */
    static void writeAtomicallyDurable(Path target, String content)
            throws IOException {
        writeAtomicallyDurable(target, content,
                AtomicYamlWriter::fsyncDirectoryOrThrow);
    }

    /**
     * Test seam for {@link #writeAtomicallyDurable(Path, String)} — the
     * directory fsync is injectable so the fail-closed branch is exercisable
     * cross-platform (a directory channel's {@code force()} cannot be made to
     * fail on a healthy directory).
     *
     * @param target         the file to replace; never {@code null}
     * @param content        the full new content; never {@code null}
     * @param directoryForce the directory-fsync strategy; production passes
     *                       {@link #fsyncDirectoryOrThrow}
     */
    static void writeAtomicallyDurable(Path target, String content,
                                       DirectoryForce directoryForce)
            throws IOException {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(directoryForce, "directoryForce must not be null");
        replaceAtomically(target, content);
        try {
            directoryForce.force(target.getParent());
        } catch (IOException e) {
            // The content was fsynced and the rename completed, but the
            // directory entry's durability could not be confirmed. Fail closed
            // rather than let the caller treat a possibly non-durable write as
            // durable (F13b / OR-M6-NONCE / Doc 15 §6).
            throw new UncheckedIOException(
                    "directory fsync could not be confirmed for "
                            + target.getParent() + "; refusing to treat the write"
                            + " as durable — a non-durable nonce-counter high-water"
                            + " mark risks a replayed nonce on crash recovery", e);
        }
    }

    /**
     * The directory-entry fsync strategy a {@link #writeAtomicallyDurable}
     * caller delegates to. Returns normally when the fsync succeeds OR when the
     * platform cannot open a directory as a channel (nothing to fsync); throws
     * only on a genuine fsync failure on a platform that supports directory
     * channels — the fail-closed trigger.
     */
    @FunctionalInterface
    interface DirectoryForce {
        void force(Path directory) throws IOException;
    }

    /** Shared write-temp → fsync → atomic-rename, with best-effort temp cleanup. */
    private static void replaceAtomically(Path target, String content)
            throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + TMP_SUFFIX);
        try {
            try (FileChannel channel = FileChannel.open(tmp,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer =
                        ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    /**
     * Copies {@code source} to {@code backupTarget}, replacing an existing
     * backup of the same name (a re-run of an interrupted window re-copies
     * identical content).
     *
     * @param source       the file to preserve; never {@code null}
     * @param backupTarget the backup destination; never {@code null}
     * @throws IOException when the copy fails — callers treat the backup
     *                     as load-bearing and fail closed
     */
    static void copyBackup(Path source, Path backupTarget) throws IOException {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(backupTarget, "backupTarget must not be null");
        Files.copy(source, backupTarget, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Best-effort directory fsync: POSIX filesystems durably record the
     * rename's directory entry; Windows cannot open a directory channel
     * (the rename's metadata rides the NTFS journal instead), so any failure
     * here — open OR force — is expected and only logged at DEBUG.
     */
    private static void fsyncDirectoryBestEffort(Path directory) {
        if (directory == null) {
            return;
        }
        try (FileChannel channel =
                     FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException e) {
            log.debug("Directory fsync unavailable for {}: {}",
                    directory, e.getMessage());
        }
    }

    /**
     * Fail-closed directory fsync (F13b): tolerates a platform that cannot
     * open a directory channel (Windows/non-POSIX — there is no directory
     * fsync to perform, durability rides the journal) but PROPAGATES a genuine
     * {@code force()} failure on a platform that does support directory
     * channels — the durability failure a critical caller must not ignore.
     */
    private static void fsyncDirectoryOrThrow(Path directory) throws IOException {
        if (directory == null) {
            return;
        }
        FileChannel channel = openDirectoryChannel(directory);
        if (channel == null) {
            // The platform cannot open a directory as a channel
            // (Windows/non-POSIX): nothing to fsync, not a failure.
            return;
        }
        try (channel) {
            channel.force(true);
        }
    }

    /**
     * Opens {@code directory} as a read channel, or returns {@code null} when
     * the platform does not support directory channels (Windows/non-POSIX) —
     * distinguishing "no directory fsync is possible here" from a genuine
     * fsync failure (which {@link #fsyncDirectoryOrThrow} must propagate).
     */
    private static FileChannel openDirectoryChannel(Path directory) {
        try {
            return FileChannel.open(directory, StandardOpenOption.READ);
        } catch (IOException e) {
            log.debug("Directory fsync unavailable (no directory channel) for {}: {}",
                    directory, e.getMessage());
            return null;
        }
    }
}
