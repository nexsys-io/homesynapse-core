/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

/**
 * Computes ETag strings for HTTP conditional request handling.
 *
 * <p>ETags enable efficient caching and conditional responses across the three
 * response categories (Doc 09 §3.7):</p>
 * <ul>
 *   <li><strong>State Query plane:</strong> Weak ETags from
 *       {@link #fromViewPosition(long)} enable {@code 304 Not Modified} when
 *       the projection has not advanced since the client's last request.</li>
 *   <li><strong>Event responses:</strong> Strong ETags from
 *       {@link #fromEventId(String)} enable aggressive caching
 *       ({@code Cache-Control: max-age=31536000, immutable}) because events
 *       never change after persistence (INV-ES-01).</li>
 *   <li><strong>Automation plane:</strong> Weak ETags from
 *       {@link #fromDefinitionHash(String)} enable short-lived caching that
 *       invalidates on hot-reload of automation definitions.</li>
 * </ul>
 *
 * <p>Formatting belongs in the provider, not the caller — the returned string
 * includes the correct weak ({@code W/"..."}) or strong ({@code "..."}) prefix.</p>
 *
 * <p>Thread-safe and stateless.</p>
 *
 * @see ApiResponse#eTag()
 * @see ResponseMeta#viewPosition()
 * @see <a href="Doc 09 §3.7">ETag and Conditional Requests</a>
 */
public interface ETagProvider {

    /**
     * Computes a weak ETag from the State Store's current view position.
     *
     * <p>Returns a weak ETag of the form {@code W/"{viewPosition}"} for State
     * Query plane responses. The weak prefix indicates that the representation
     * may change if the projection advances.</p>
     *
     * @param viewPosition the State Store's current view position
     * @return the weak ETag string (e.g., {@code W/"42850"}), never {@code null}
     */
    String fromViewPosition(long viewPosition);

    /**
     * Computes a strong ETag from an immutable event's identifier.
     *
     * <p>Returns a strong ETag of the form {@code "{eventId}"} for single
     * immutable event responses. The strong ETag justifies aggressive caching
     * because events never change after persistence (INV-ES-01).</p>
     *
     * @param eventId the event's identifier as a Crockford Base32 string,
     *                never {@code null}
     * @return the strong ETag string (e.g., {@code "01HV..."}), never {@code null}
     */
    String fromEventId(String eventId);

    /**
     * Computes a weak ETag from an automation definition's content hash.
     *
     * <p>Returns a weak ETag of the form {@code W/"{definitionHash}"} for
     * Automation plane responses. The weak prefix indicates that the
     * representation invalidates on hot-reload of automation definitions.</p>
     *
     * @param definitionHash the content hash of the automation definition,
     *                       never {@code null}
     * @return the weak ETag string (e.g., {@code W/"a3f8c1..."}), never {@code null}
     */
    String fromDefinitionHash(String definitionHash);
}
