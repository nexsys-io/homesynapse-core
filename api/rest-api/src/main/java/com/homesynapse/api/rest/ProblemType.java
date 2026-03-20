/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

/**
 * Machine-readable error type identifiers for RFC 9457 Problem Detail responses.
 *
 * <p>Each value corresponds to a specific error condition that the REST API can return
 * (Doc 09 §3.8). The enum provides three derived properties per value:</p>
 * <ul>
 *   <li>{@link #slug()} — the URI path segment (e.g., {@code "not-found"}).</li>
 *   <li>{@link #defaultStatus()} — the HTTP status code associated with this error.</li>
 *   <li>{@link #title()} — a short, human-readable summary of the problem type.</li>
 * </ul>
 *
 * <p>The {@link #typeUri()} method derives the full problem type URI from the slug,
 * producing URIs of the form {@code https://homesynapse.local/problems/{slug}}. This
 * URI is the machine-readable {@code type} field in the JSON Problem Detail response.</p>
 *
 * <p>Problem types are stable across releases. New types may be added in Tier 2, but
 * existing types and their slugs are never changed or removed.</p>
 *
 * @see ProblemDetail
 * @see ApiException
 * @see <a href="Doc 09 §3.8">Error Response Model</a>
 */
public enum ProblemType {

    /**
     * The requested resource does not exist.
     *
     * <p>Returned when an entity, device, or other addressable resource identified
     * by the request path cannot be found in the system. The {@code detail} field
     * identifies the specific resource type and identifier (Doc 09 §3.8).</p>
     */
    NOT_FOUND("not-found", 404, "Not Found"),

    /**
     * The target entity is disabled and cannot accept commands.
     *
     * <p>Returned when a command is issued to an entity that has been explicitly
     * disabled by the user. This is a user-initiated state — the entity exists
     * but is not accepting commands. Re-enable the entity to resume command
     * acceptance (Doc 09 §3.8).</p>
     */
    ENTITY_DISABLED("entity-disabled", 409, "Entity Disabled"),

    /**
     * The integration responsible for the target device is unhealthy.
     *
     * <p>Returned when a command or query targets a device whose integration
     * adapter is in a degraded or failed state. The integration may be restarting,
     * disconnected, or experiencing persistent errors (Doc 09 §3.8).</p>
     */
    INTEGRATION_UNHEALTHY("integration-unhealthy", 503, "Integration Unhealthy"),

    /**
     * The command is not valid for the target entity's capabilities.
     *
     * <p>Returned when the requested capability or command name does not match
     * any declared capability on the target entity, or when the command parameters
     * fail schema validation against the {@code CommandDefinition} (Doc 09 §3.8).
     * The {@code errors} array in the Problem Detail response contains per-field
     * validation details.</p>
     */
    INVALID_COMMAND("invalid-command", 422, "Invalid Command"),

    /**
     * One or more request parameters are invalid.
     *
     * <p>Returned for malformed query parameters, invalid pagination cursors,
     * out-of-range values, or missing required parameters. The {@code errors}
     * array in the Problem Detail response contains per-field validation
     * details (Doc 09 §3.8).</p>
     */
    INVALID_PARAMETERS("invalid-parameters", 400, "Invalid Parameters"),

    /**
     * Authentication is required but was not provided or is malformed.
     *
     * <p>Returned when the {@code Authorization: Bearer {key}} header is missing
     * or cannot be parsed. Every API request requires authentication
     * (INV-SE-02). No endpoint is accessible without a valid API key
     * (Doc 09 §12.1).</p>
     */
    AUTHENTICATION_REQUIRED("authentication-required", 401, "Authentication Required"),

    /**
     * The authenticated caller does not have permission for this operation.
     *
     * <p>Returned when the API key is well-formed but invalid, expired, or
     * revoked. Also returned when the key is valid but lacks permission for
     * the requested operation (Doc 09 §12.1).</p>
     */
    FORBIDDEN("forbidden", 403, "Forbidden"),

    /**
     * The caller has exceeded the rate limit.
     *
     * <p>Returned when the per-key token bucket is exhausted. The response
     * includes a {@code Retry-After} header with the number of seconds until
     * the limit resets. Default limits: 300 requests/minute sustained, 50
     * burst (Doc 09 §12.5).</p>
     */
    RATE_LIMITED("rate-limited", 429, "Rate Limited"),

    /**
     * The specified command could not be found.
     *
     * <p>Returned when querying a command lifecycle status via
     * {@code GET /api/v1/commands/{command_id}} and no matching
     * {@code command_issued} event exists in the event store
     * (Doc 09 §4.5).</p>
     */
    COMMAND_NOT_FOUND("command-not-found", 404, "Command Not Found"),

    /**
     * The State Store is replaying events and is temporarily unavailable.
     *
     * <p>Returned when a State Query plane request arrives while the State
     * Store is rebuilding its materialized state from the event log. The
     * client should retry after the replay completes. Event History plane
     * queries are unaffected — they read directly from the immutable event
     * store (Doc 09 §3.8).</p>
     */
    STATE_STORE_REPLAYING("state-store-replaying", 503, "State Store Replaying"),

    /**
     * An unexpected internal error occurred.
     *
     * <p>Returned for unrecognized exceptions that do not map to a specific
     * problem type. The {@code detail} field contains a generic message —
     * stack traces are never included in API responses (Doc 09 §12.4). The
     * {@code correlationId} links to server-side log entries for diagnosis.</p>
     */
    INTERNAL_ERROR("internal-error", 500, "Internal Error"),

    /**
     * The idempotency key conflicts with an existing entry for a different request body.
     *
     * <p>Returned when a command request includes an {@code Idempotency-Key} header
     * that matches an existing cache entry, but the request body differs from the
     * original request. The client must use a different idempotency key for a
     * different command (AMD-08, Doc 09 §3.4).</p>
     */
    IDEMPOTENCY_KEY_CONFLICT("idempotency-key-conflict", 409, "Idempotency Key Conflict"),

    /**
     * The target device is orphaned — its integration connection has been lost.
     *
     * <p>Returned when a command is issued to an entity whose parent device has
     * lost its integration adapter connection. The integration is unavailable to
     * deliver the command. This is distinct from {@link #ENTITY_DISABLED}, which
     * is a user-initiated state (AMD-17, Doc 09 §3.8).</p>
     */
    DEVICE_ORPHANED("device-orphaned", 503, "Device Orphaned");

    private static final String TYPE_URI_PREFIX = "https://homesynapse.local/problems/";

    private final String slug;
    private final int defaultStatus;
    private final String title;

    ProblemType(String slug, int defaultStatus, String title) {
        this.slug = slug;
        this.defaultStatus = defaultStatus;
        this.title = title;
    }

    /**
     * Returns the URI path segment for this problem type.
     *
     * <p>The slug is a lowercase, hyphenated string (e.g., {@code "not-found"})
     * that forms the final segment of the problem type URI.</p>
     *
     * @return the slug, never {@code null}
     */
    public String slug() {
        return slug;
    }

    /**
     * Returns the default HTTP status code for this problem type.
     *
     * <p>This is the status code that the REST API returns when this problem
     * type is used in a response. The actual status in a {@link ProblemDetail}
     * may differ if context warrants it, but defaults to this value.</p>
     *
     * @return the HTTP status code (e.g., 404, 409, 503)
     */
    public int defaultStatus() {
        return defaultStatus;
    }

    /**
     * Returns the short, human-readable title for this problem type.
     *
     * <p>The title is a stable, non-localized string (e.g., {@code "Not Found"})
     * suitable for display in API responses and error logs.</p>
     *
     * @return the title, never {@code null}
     */
    public String title() {
        return title;
    }

    /**
     * Returns the full problem type URI for JSON serialization.
     *
     * <p>Produces a URI of the form
     * {@code https://homesynapse.local/problems/{slug}} as specified by
     * RFC 9457 and Doc 09 §3.8. This URI is the {@code type} field in
     * the JSON Problem Detail response body.</p>
     *
     * @return the full problem type URI, never {@code null}
     */
    public String typeUri() {
        return TYPE_URI_PREFIX + slug;
    }
}
