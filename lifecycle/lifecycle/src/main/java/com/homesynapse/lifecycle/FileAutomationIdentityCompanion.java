/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import com.homesynapse.automation.AutomationIdentityCompanion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The bytes of the automation identity companion: {@code automations.ids.yaml} beside
 * {@code homesynapse.yaml} in the configuration directory (Doc 07 §4.1; AMD-93 §2.3;
 * AMD-71 §2.1), read and written as YAML by snakeyaml-engine, never by hand.
 *
 * <p>{@code core/automation}'s {@code CompanionAutomationIdentityStore} owns the
 * document's shape and policy and may touch neither the filesystem
 * ({@code NO_DIRECT_FILESYSTEM_IN_CORE}) nor a YAML library; the composition root hands
 * it this class. The document is a string-keyed map tree that this class neither
 * interprets nor reorders.</p>
 *
 * <p><strong>Write.</strong> The header comment, then the library's block-style dump of
 * the document in the document's own iteration order (the store sorts it, so an unchanged
 * document is byte-stable), to a same-directory {@code .tmp} sibling that is fsynced and
 * renamed over the target with {@code ATOMIC_MOVE} — the {@code AtomicYamlWriter} idiom
 * (that class is {@code config}'s and package-private). On any failure the previous file
 * is intact, the temp file this call created is removed, and the failure surfaces as an
 * {@link IllegalStateException} naming the path. The library emits no comment for a plain
 * object dump, so the header line is written before the dumped body; on read the YAML
 * parser skips it as the comment it is.</p>
 *
 * <p><strong>Read.</strong> Absent → empty (the first boot). A file that exists and cannot
 * be read — an I/O failure, invalid YAML, a duplicate key, an empty document, a root that
 * is not a mapping, a key that is not a scalar — is an {@link IllegalStateException}
 * naming the path: the caller fails closed. Scalar keys the YAML typed (an unquoted
 * trigger index) are returned as their strings — the seam's document is string-keyed.</p>
 *
 * <p>Not thread-safe; the store never calls the seam concurrently.</p>
 */
final class FileAutomationIdentityCompanion implements AutomationIdentityCompanion {

    private static final Logger LOG = LoggerFactory.getLogger(FileAutomationIdentityCompanion.class);

    /** The first line of every companion this class writes (AUTO-ID-1 R2, verbatim). */
    static final String HEADER =
            "# automations.ids.yaml — engine-managed (Doc 07 §4.1; AMD-93 §2.3). Do not edit.";

    private static final String TMP_SUFFIX = ".tmp";

    private final Path file;

    /**
     * @param file the companion's path ({@code <configDir>/automations.ids.yaml}); never
     *             {@code null}. Its directory is the composition root's to create.
     */
    FileAutomationIdentityCompanion(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    @Override
    public Optional<Map<String, Object>> read() {
        // notExists, never !exists: a file whose existence cannot be determined is NOT
        // the first boot — the read below fails closed on it.
        if (Files.notExists(file)) {
            return Optional.empty();
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw unreadable(e.toString(), e);
        }
        Object root;
        try {
            root = new Load(LoadSettings.builder()
                    .setLabel(file.toString())
                    .setAllowDuplicateKeys(false)
                    .build()).loadFromString(text);
        } catch (YamlEngineException e) {
            throw unreadable("not valid YAML: " + e.getMessage(), e);
        }
        if (!(root instanceof Map<?, ?> mapping)) {
            throw unreadable("the document root is " + (root == null
                    ? "empty" : "a " + root.getClass().getSimpleName()) + ", not a mapping", null);
        }
        return Optional.of(stringKeyed(mapping));
    }

    @Override
    public void replace(Map<String, Object> document) {
        Objects.requireNonNull(document, "document");
        Path tmp = file.resolveSibling(file.getFileName() + TMP_SUFFIX);
        boolean created = false;
        try {
            String content = HEADER + "\n" + new Dump(DumpSettings.builder()
                    .setDefaultFlowStyle(FlowStyle.BLOCK)
                    .build()).dumpToString(document);
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                created = true;
                ByteBuffer buffer = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | YamlEngineException e) {
            if (created) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException cleanup) {
                    e.addSuppressed(cleanup);
                }
            }
            throw new IllegalStateException("automation identity companion cannot be written: "
                    + "path=" + file + " cause=" + e, e);
        }
        forceDirectoryBestEffort(file.toAbsolutePath().getParent());
    }

    /** The path — the store prints it as {@code path=}. */
    @Override
    public String toString() {
        return file.toString();
    }

    private Map<String, Object> stringKeyed(Map<?, ?> mapping) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : mapping.entrySet()) {
            Object key = entry.getKey();
            if (!(key instanceof String || key instanceof Number || key instanceof Boolean)) {
                throw unreadable("a mapping key is " + (key == null
                        ? "null" : "a " + key.getClass().getSimpleName()) + ", not a scalar", null);
            }
            Object value = entry.getValue() instanceof Map<?, ?> nested
                    ? stringKeyed(nested) : entry.getValue();
            if (result.put(String.valueOf(key), value) != null) {
                throw unreadable("duplicate key '" + key + "'", null);
            }
        }
        return result;
    }

    private IllegalStateException unreadable(String cause, Exception source) {
        return new IllegalStateException("automation identity companion cannot be read: path="
                + file + " cause=" + cause + " — the file is engine-managed (Doc 07 §4.1) and"
                + " identities are never re-minted over it; restore it, or remove it to mint"
                + " new identities (the run history under the old ones is orphaned)", source);
    }

    /**
     * POSIX records the rename's directory entry durably on a directory fsync; Windows
     * cannot open a directory channel (the rename rides the NTFS journal), so a failure
     * here is expected there and only logged at DEBUG.
     */
    private static void forceDirectoryBestEffort(Path directory) {
        if (directory == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException e) {
            LOG.debug("Directory fsync unavailable for {}: {}", directory, e.getMessage());
        }
    }
}
