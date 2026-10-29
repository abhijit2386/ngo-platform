package org.ngoplatform.commons.events;

import java.util.Set;

/**
 * A consumer of platform events. Implementations wrap their work in {@link IdempotentConsumer#handle} and are
 * invoked by the active transport: RabbitMQ listeners in production, or {@link InternalEventsController} when
 * events are delivered over HTTP (local development without a broker).
 */
public interface EventHandler {
    /** Stable consumer name, also used as the inbox key and the RabbitMQ queue name. */
    String consumerName();

    /** Event types (routing keys) this handler consumes. */
    Set<String> eventTypes();

    void on(EventEnvelope event);
}
