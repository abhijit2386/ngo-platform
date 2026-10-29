package org.ngoplatform.commons.events;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs a handler at most once per (consumer, eventId). The inbox row and the handler's writes commit together;
 * a concurrent duplicate fails on the unique constraint and is redelivered, then skipped.
 */
@Component
public class IdempotentConsumer {
    private final InboxRepository inbox;

    public IdempotentConsumer(InboxRepository inbox) { this.inbox = inbox; }

    @Transactional
    public boolean handle(String consumer, EventEnvelope event, Runnable handler) {
        if (inbox.existsByConsumerAndEventId(consumer, event.eventId())) return false;
        handler.run();
        inbox.save(new InboxRecord(consumer, event.eventId()));
        return true;
    }
}
