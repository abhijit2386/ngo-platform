package org.ngoplatform.commons.events;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP event inbox used when {@code ngo.events.transport=http}. Delivery is at-least-once (the producer's outbox
 * retries until every subscriber answers 2xx), so each handler stays idempotent through the inbox table.
 * Protected by the internal-token filter like every /internal endpoint.
 */
@RestController
@ConditionalOnProperty(name = "ngo.events.transport", havingValue = "http")
public class InternalEventsController {
    private final List<EventHandler> handlers;

    public InternalEventsController(List<EventHandler> handlers) { this.handlers = handlers; }

    @PostMapping("/internal/v1/events")
    public ResponseEntity<Void> deliver(@RequestBody EventEnvelope event) {
        for (EventHandler h : handlers) {
            if (h.eventTypes().contains(event.eventType())) h.on(event);
        }
        return ResponseEntity.noContent().build();
    }
}
