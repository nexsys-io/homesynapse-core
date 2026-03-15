/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.event.bus;

/**
 * Notification mechanism for the pull-based event subscription model (Doc 01 §3.4).
 *
 * <p>The {@code EventBus} does <em>not</em> deliver events directly to subscribers.
 * Instead, it maintains a registry of subscribers and their {@link SubscriptionFilter
 * filters}, evaluates those filters when new events are persisted, and wakes matching
 * subscribers via {@code LockSupport.unpark()} so they can poll the
 * {@link com.homesynapse.event.EventStore EventStore} for actual event data. This
 * separation keeps the bus lightweight: it determines <em>who</em> to wake, not
 * <em>what</em> to deliver.</p>
 *
 * <p>The bus manages three concerns:</p>
 * <ol>
 *   <li><strong>Filter evaluation:</strong> When
 *       {@link #notifyEvent(long) notifyEvent(globalPosition)} is called, the bus
 *       evaluates each registered subscriber's filter to determine who should be
 *       notified.</li>
 *   <li><strong>Subscriber lifecycle:</strong> Subscribers register via
 *       {@link #subscribe(SubscriberInfo)} and deregister via
 *       {@link #unsubscribe(String)}. Checkpoints are managed through
 *       {@link CheckpointStore} and survive across registration cycles.</li>
 *   <li><strong>Backpressure coalescing:</strong> Under subscriber backpressure, the
 *       bus may coalesce notifications for specific {@code DIAGNOSTIC} event types
 *       for non-exempt subscribers (Doc 01 §3.6). Coalescing-exempt subscribers
 *       (identified by {@link SubscriberInfo#coalesceExempt()}) always receive
 *       individual notifications.</li>
 * </ol>
 *
 * <p>The bus does <em>not</em> own event persistence — that is the responsibility of
 * {@link com.homesynapse.event.EventPublisher EventPublisher}. The publisher calls
 * {@link #notifyEvent(long)} after successfully appending an event to the log.</p>
 *
 * <p><strong>Thread safety:</strong> All methods on this interface may be called
 * concurrently. Implementations must ensure that registration, unregistration, and
 * notification are safe under concurrent access.</p>
 *
 * @see SubscriberInfo
 * @see SubscriptionFilter
 * @see CheckpointStore
 * @see com.homesynapse.event.EventStore
 * @see <a href="Doc 01 §3.4">Subscription Model</a>
 * @see <a href="Doc 01 §3.6">Backpressure and Coalescing</a>
 * @see <a href="Doc 01 §3.7">Processing Modes</a>
 */
public interface EventBus {

    /**
     * Registers a subscriber with the bus.
     *
     * <p>The subscriber's {@link SubscriberInfo#filter() filter} determines which events
     * trigger notification. The subscriber's checkpoint is loaded from the
     * {@link CheckpointStore} at registration time; if no checkpoint exists, the
     * subscriber starts from position 0 (beginning of the event log).</p>
     *
     * <p>If a subscriber with the same {@link SubscriberInfo#subscriberId() subscriberId}
     * is already registered, the previous registration is replaced.</p>
     *
     * @param subscriber the subscriber registration metadata
     * @throws NullPointerException if {@code subscriber} is {@code null}
     * @see SubscriberInfo
     * @see CheckpointStore#readCheckpoint(String)
     */
    void subscribe(SubscriberInfo subscriber);

    /**
     * Removes a subscriber from the bus.
     *
     * <p>The subscriber will no longer receive notifications. The subscriber's checkpoint
     * is <em>retained</em> in the {@link CheckpointStore} (not deleted) so that
     * re-registration via {@link #subscribe(SubscriberInfo)} resumes from the last
     * checkpointed position.</p>
     *
     * <p>This method is a no-op if the given {@code subscriberId} is not currently
     * registered.</p>
     *
     * @param subscriberId the stable identifier of the subscriber to remove
     * @throws NullPointerException if {@code subscriberId} is {@code null}
     */
    void unsubscribe(String subscriberId);

    /**
     * Notifies the bus that a new event has been persisted at the given global position.
     *
     * <p>Called by the {@link com.homesynapse.event.EventPublisher EventPublisher} after
     * successfully appending an event to the log. The bus evaluates registered filters
     * and wakes matching subscribers via {@code LockSupport.unpark()} (Doc 01 §3.4).</p>
     *
     * <p>The bus does <em>not</em> deliver the event directly — subscribers poll the
     * {@link com.homesynapse.event.EventStore EventStore} themselves using
     * {@code EventStore.readFrom()}. This separation means the bus is lightweight: it
     * only determines who to wake, not what to deliver.</p>
     *
     * @param globalPosition the global position of the newly persisted event
     */
    void notifyEvent(long globalPosition);

    /**
     * Returns the last checkpointed global position for the given subscriber.
     *
     * <p>Returns 0 if the subscriber has no checkpoint. Used by monitoring and the
     * REPLAY to LIVE transition logic (Doc 01 §3.7) to determine how far behind a
     * subscriber is relative to the head of the event log.</p>
     *
     * @param subscriberId the stable identifier of the subscriber
     * @return the last checkpointed global position, or 0 if none exists
     * @throws NullPointerException if {@code subscriberId} is {@code null}
     */
    long subscriberPosition(String subscriberId);
}
