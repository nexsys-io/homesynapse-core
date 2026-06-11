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
     * write-temp → fsync → rename → fsync-directory.
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
        fsyncDirectory(target.getParent());
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
     * (the rename's metadata rides the NTFS journal instead), so failure
     * here is expected and only logged at DEBUG.
     */
    private static void fsyncDirectory(Path directory) {
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
}
