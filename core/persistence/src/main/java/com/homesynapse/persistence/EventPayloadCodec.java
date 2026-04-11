/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.homesynapse.event.DegradedEvent;
import com.homesynapse.event.DomainEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encodes {@link DomainEvent} payloads to UTF-8 JSON bytes for SQLite BLOB storage
 * and decodes them back with a {@link DegradedEvent} fallback for failed reads
 * (LTD-19 / DECIDE-M2-06, DECIDE-M2-07).
 *
 * <p>This is the sole serialization boundary between in-memory {@link DomainEvent}
 * records and the {@code payload} column of the event store. The
 * {@link com.homesynapse.event.EventEnvelope outer envelope} (metadata, correlation
 * IDs, event ID) is stored in discrete SQLite columns and is NOT serialized by this
 * codec — only the inner {@code payload} {@code DomainEvent} passes through here.</p>
 *
 * <p><strong>Encode path.</strong> Looks up the pre-warmed {@link ObjectWriter}
 * from {@link JacksonWarmup} and writes the payload as UTF-8 JSON bytes. Throws
 * {@link IllegalArgumentException} if the payload class is not registered
 * (including {@link DegradedEvent}, which has no {@link com.homesynapse.event.EventType}
 * annotation by design and must never be re-serialized as a payload).</p>
 *
 * <p><strong>Decode path — two-stage {@code DegradedEvent} fallback.</strong></p>
 * <ol>
 *   <li><strong>Unknown event type:</strong> if {@code eventType} is not in the
 *       registry, the codec returns a {@code DegradedEvent} carrying the raw
 *       payload as UTF-8 text and a {@code "Unknown event type: ..."} failure
 *       reason.</li>
 *   <li><strong>Parse failure:</strong> if the registered type is found but
 *       Jackson throws during deserialization (malformed JSON, missing required
 *       field, compact-constructor validation failure), the codec catches the
 *       exception and returns a {@code DegradedEvent} carrying the raw payload
 *       and the exception class plus message as the failure reason.</li>
 * </ol>
 *
 * <p><strong>Metadata sanitization.</strong> Corrupt SQLite rows may have blank or
 * null {@code eventType} or {@code schemaVersion} values. {@link DegradedEvent}'s
 * compact constructor validates non-blank {@code eventType} and
 * {@code schemaVersion >= 1}. Before constructing any fallback
 * {@code DegradedEvent}, this codec clamps {@code schemaVersion} to at least 1
 * and defaults blank/null {@code eventType} to {@code "unknown"} so the fallback
 * path itself can never throw.</p>
 *
 * <p><strong>Thread-safety.</strong> Fully thread-safe. {@link ObjectWriter} and
 * {@link ObjectReader} are Jackson's immutable, reusable accessors. Multiple
 * virtual threads may call {@link #encode(DomainEvent)} and
 * {@link #decode(String, int, byte[])} concurrently.</p>
 *
 * <p>Package-private — internal persistence infrastructure, not public API.</p>
 *
 * @see JacksonWarmup
 * @see EventTypeRegistry
 * @see DegradedEvent
 */
final class EventPayloadCodec {

    private static final Logger LOG = LoggerFactory.getLogger(EventPayloadCodec.class);

    /** Sentinel {@code eventType} substituted for blank or null values in the fallback path. */
    private static final String UNKNOWN_TYPE = "unknown";

    private final EventTypeRegistry registry;
    private final JacksonWarmup warmup;

    /**
     * Constructs a new codec backed by the given registry and pre-warmed caches.
     *
     * @param registry the event type registry; never {@code null}
     * @param warmup   the Jackson warmup output; never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    EventPayloadCodec(EventTypeRegistry registry, JacksonWarmup warmup) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.warmup = Objects.requireNonNull(warmup, "warmup must not be null");
    }

    /**
     * Serializes a {@link DomainEvent} payload to UTF-8 JSON bytes suitable for
     * SQLite BLOB storage (DECIDE-M2-06).
     *
     * @param payload the event payload; never {@code null}
     * @return a freshly allocated UTF-8 JSON byte array
     * @throws NullPointerException     if {@code payload} is {@code null}
     * @throws IllegalArgumentException if the payload's concrete class is not
     *                                  registered in the {@link EventTypeRegistry}
     *                                  (including {@link DegradedEvent}, which
     *                                  is never re-serialized as a payload)
     * @throws IOException              if Jackson fails to serialize the payload
     */
    byte[] encode(DomainEvent payload) throws IOException {
        Objects.requireNonNull(payload, "payload must not be null");

        Class<? extends DomainEvent> concreteClass = payload.getClass();
        if (registry.typeFor(concreteClass).isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot encode unregistered event class: " + concreteClass.getName()
                            + " (DegradedEvent is never re-serialized as a payload)");
        }

        ObjectWriter writer = warmup.writerFor(concreteClass);
        return writer.writeValueAsBytes(payload);
    }

    /**
     * Deserializes UTF-8 JSON bytes to a {@link DomainEvent}, with
     * {@link DegradedEvent} fallback on unknown type or parse failure
     * (DECIDE-M2-07).
     *
     * @param eventType     the event type discriminator from the SQLite
     *                      {@code event_type} column; may be {@code null} or blank
     *                      on corrupt rows (sanitized before DegradedEvent construction)
     * @param schemaVersion the schema version from the SQLite
     *                      {@code schema_version} column; values below 1 are
     *                      clamped to 1 before DegradedEvent construction
     * @param payload       the raw JSON bytes from the SQLite {@code payload}
     *                      BLOB column; never {@code null}
     * @return the deserialized event, or a {@link DegradedEvent} wrapping the
     *         raw payload on any failure
     * @throws NullPointerException if {@code payload} is {@code null}
     */
    DomainEvent decode(String eventType, int schemaVersion, byte[] payload) {
        Objects.requireNonNull(payload, "payload must not be null");

        // Sanitize metadata up-front so every fallback path is safe against
        // DegradedEvent's compact-constructor validation.
        int safeVersion = Math.max(1, schemaVersion);
        String safeType = (eventType == null || eventType.isBlank())
                ? UNKNOWN_TYPE
                : eventType;

        // Stage 1: registry lookup
        Optional<Class<? extends DomainEvent>> maybeClass = registry.classFor(eventType);
        if (maybeClass.isEmpty()) {
            String reason = "Unknown event type: " + eventType;
            LOG.warn(
                    "Decode fallback (unknown type): eventType='{}', schemaVersion={}",
                    eventType,
                    schemaVersion);
            return new DegradedEvent(safeType, safeVersion, toUtf8String(payload), reason);
        }

        // Stage 2: typed deserialization with catch-all fallback
        Class<? extends DomainEvent> concreteClass = maybeClass.get();
        ObjectReader reader = warmup.readerFor(concreteClass);
        try {
            return reader.readValue(payload);
        } catch (IOException | RuntimeException ex) {
            String reason = ex.getClass().getName() + ": " + ex.getMessage();
            LOG.warn(
                    "Decode fallback (parse failure): eventType='{}', class={}, reason={}",
                    eventType,
                    concreteClass.getName(),
                    reason);
            return new DegradedEvent(safeType, safeVersion, toUtf8String(payload), reason);
        }
    }

    /**
     * Converts raw payload bytes to a UTF-8 string for embedding in a
     * {@link DegradedEvent#rawPayload()}. Bytes are assumed to be a best-effort
     * representation of the stored JSON — on binary garbage, the resulting string
     * is still usable for diagnostic display (it will contain replacement characters
     * but never throw).
     */
    private static String toUtf8String(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
