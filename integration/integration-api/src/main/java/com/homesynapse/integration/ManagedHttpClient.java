/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

/**
 * Resource-controlled HTTP client facade with concurrency limits and rate
 * limiting for cloud-connected integration adapters (Doc 05 §3.8, §3.9, §8.1).
 *
 * <p>{@code ManagedHttpClient} wraps {@link java.net.http.HttpClient} with
 * integration-scoped resource controls: connection pool isolation, concurrent
 * request limits, and rate limiting. Each integration receives its own instance
 * with lifecycle tied to the adapter — the connection pool is created when the
 * adapter starts and closed when the adapter stops.</p>
 *
 * <p>Only available if the adapter declares
 * {@link RequiredService#HTTP_CLIENT} in its
 * {@link IntegrationDescriptor#requiredServices()}. If not declared, the
 * corresponding field in {@link IntegrationContext} is {@code null}.</p>
 *
 * <p>Typical consumers: cloud-connected adapters (Philips Hue, MQTT brokers
 * with HTTP configuration APIs, weather service integrations) that need
 * outbound HTTP access with controlled resource usage on constrained
 * hardware.</p>
 *
 * <p><strong>Thread safety:</strong> All methods are safe for concurrent use
 * from any thread within the adapter's thread group.</p>
 *
 * @see RequiredService#HTTP_CLIENT
 * @see IntegrationContext
 * @see IntegrationDescriptor#requiredServices()
 */
public interface ManagedHttpClient {

    /**
     * Sends an HTTP request synchronously with concurrency and rate enforcement.
     *
     * <p>If the concurrency limit is reached, this method blocks until a slot
     * becomes available. If the rate limit is reached, this method blocks until
     * the next permitted request window. Both blocking operations are
     * virtual-thread-friendly (they unmount from the carrier thread).</p>
     *
     * @param <T>     the response body type
     * @param request the HTTP request to send; never {@code null}
     * @param handler the response body handler; never {@code null}
     * @return the HTTP response; never {@code null}
     * @throws IOException          if an I/O error occurs during the request
     * @throws InterruptedException if the current thread is interrupted while
     *                              waiting for a concurrency or rate limit slot
     */
    <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException;

    /**
     * Sends an HTTP request asynchronously with concurrency and rate enforcement.
     *
     * <p>The returned future completes on the integration's virtual thread
     * group. Concurrency and rate limits are enforced before the request is
     * dispatched — the future may not start immediately if limits are
     * reached.</p>
     *
     * @param <T>     the response body type
     * @param request the HTTP request to send; never {@code null}
     * @param handler the response body handler; never {@code null}
     * @return a {@link CompletableFuture} that completes with the HTTP
     *         response; never {@code null}
     */
    <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                      HttpResponse.BodyHandler<T> handler);

    /**
     * Releases the connection pool and cancels any pending requests.
     *
     * <p>Called by the supervisor when the adapter is stopped. After this
     * method returns, no further requests can be sent. Pending async requests
     * complete exceptionally.</p>
     */
    void close();
}
