/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.util.JsonRecyclerPools;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.blackbird.BlackbirdModule;

/**
 * Factory producing a correctly configured {@link ObjectMapper} for the persistence
 * layer's domain event payload serde (LTD-19 / DECIDE-M2-04, amended).
 *
 * <p>Every configuration item here is a locked decision, validated by the pre-M2.4
 * Jackson research session. Changes require PM approval and a new design doc entry.</p>
 *
 * <p><strong>Modules registered:</strong></p>
 * <ul>
 *   <li>{@link JavaTimeModule} — {@code Instant} as ISO 8601 strings (future-proofing
 *       for events that will eventually carry temporal fields).</li>
 *   <li>{@link BlackbirdModule} — LambdaMetafactory-based accessors for a 10–20%
 *       throughput boost on record-heavy workloads (LTD-08). No {@code opens}
 *       directives are required because records expose public accessors.</li>
 *   <li>{@link PersistenceJacksonModule} — the ULID wrapper serde.</li>
 * </ul>
 *
 * <p><strong>Feature configuration:</strong></p>
 * <ul>
 *   <li>{@link PropertyNamingStrategies#SNAKE_CASE} — Java {@code camelCase}
 *       fields map to JSON {@code snake_case} keys (LTD-08). Records work
 *       natively starting with Jackson 2.18.4 (DECIDE-M2-08 amended).</li>
 *   <li>{@link JsonInclude.Include#NON_NULL} — omit null fields from JSON output.
 *       Storage savings on constrained hardware (INV-PR-01).</li>
 *   <li>{@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES} disabled —
 *       forward-compatibility guarantee (INV-ES-07): a payload written by a
 *       newer version with additional fields round-trips cleanly through an
 *       older reader.</li>
 *   <li>{@link SerializationFeature#WRITE_DATES_AS_TIMESTAMPS} disabled — ISO
 *       8601 strings are observable by humans and standard tooling.</li>
 *   <li>{@link SerializationFeature#INDENT_OUTPUT} disabled — compact JSON
 *       minimizes BLOB size.</li>
 * </ul>
 *
 * <p><strong>Recycler pool:</strong> The factory replaces Jackson's default
 * {@code ThreadLocalPool} with {@link JsonRecyclerPools#newConcurrentDequePool()}.
 * The default pool leaks buffer objects onto every virtual thread carrier and
 * then frees them on carrier reuse — a worst-case allocation pattern under the
 * heavy virtual-thread load that the event bus imposes. The concurrent-deque
 * pool is shared across carriers and never attaches to thread-local state.</p>
 *
 * <p><strong>What is deliberately NOT registered:</strong></p>
 * <ul>
 *   <li>{@code ParameterNamesModule} — unnecessary for records since Jackson 2.12
 *       and can cause "conflicting property-based creators" errors on records
 *       that have additional secondary constructors.</li>
 *   <li>{@code Jdk8Module} — no event record uses {@link java.util.Optional}.
 *       Add when first needed.</li>
 *   <li>{@code AttributeValue} serde — deferred to the state-store milestone
 *       (DECIDE-M2-03). No current event record uses the type.</li>
 * </ul>
 *
 * <p><strong>Virtual-thread safety:</strong> The produced {@link ObjectMapper} is
 * thread-safe after configuration and safe for concurrent use across any number
 * of virtual threads. See {@link JacksonWarmup} for the cache pre-population that
 * prevents {@code synchronized} cache-miss paths from pinning virtual thread
 * carriers under steady-state load.</p>
 *
 * <p>Package-private — internal persistence infrastructure, not public API.</p>
 *
 * @see PersistenceJacksonModule
 * @see JacksonWarmup
 * @see EventPayloadCodec
 */
final class PersistenceObjectMapper {

    private PersistenceObjectMapper() {
        // Utility class — non-instantiable
    }

    /**
     * Creates a new, fully-configured {@link ObjectMapper} for persistence payload
     * serde.
     *
     * <p>Each call returns an independent instance. Callers should cache the
     * result; construction is non-trivial (module scanning, feature configuration).</p>
     *
     * @return a configured {@code ObjectMapper}, never {@code null}
     */
    static ObjectMapper create() {
        JsonFactory factory = JsonFactory.builder()
                .recyclerPool(JsonRecyclerPools.newConcurrentDequePool())
                .build();

        return JsonMapper.builder(factory)
                .addModule(new JavaTimeModule())
                .addModule(new BlackbirdModule())
                .addModule(new PersistenceJacksonModule())
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.INDENT_OUTPUT)
                .build();
    }
}
