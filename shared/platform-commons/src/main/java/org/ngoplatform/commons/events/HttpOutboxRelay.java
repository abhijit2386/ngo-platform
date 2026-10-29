package org.ngoplatform.commons.events;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import org.ngoplatform.commons.config.EventRoutes;
import org.ngoplatform.commons.config.InternalClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Broker-less outbox relay for local development: POSTs each committed event to every subscribing service's
 * {@code /internal/v1/events}. An event is marked published only when all subscribers accepted it; otherwise it is
 * retried on the next tick (consumers are idempotent). Ordering per producer is preserved by stopping at the first
 * failure, exactly like the RabbitMQ relay.
 */
@Component
@ConditionalOnProperty(name = "ngo.events.transport", havingValue = "http")
public class HttpOutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(HttpOutboxRelay.class);
    private final OutboxRepository repo;
    private final ObjectMapper mapper;
    private final InternalClients clients;
    private final EventRoutes routes;

    public HttpOutboxRelay(OutboxRepository repo, ObjectMapper mapper, InternalClients clients, EventRoutes routes,
                           MeterRegistry meters) {
        this.repo = repo;
        this.mapper = mapper;
        this.clients = clients;
        this.routes = routes;
        Gauge.builder("ngo.outbox.pending", repo, OutboxRepository::countByPublishedAtIsNull).register(meters);
    }

    @Scheduled(fixedDelayString = "${ngo.outbox.poll-ms:1000}")
    @Transactional
    public void relay() {
        for (OutboxEvent e : repo.lockBatch(100)) {
            try {
                Map<String, Object> payload = mapper.readValue(e.getPayload(), new TypeReference<>() {});
                EventEnvelope env = new EventEnvelope(e.getId(), e.getEventType(), e.getEventVersion(),
                        e.getCreatedAt(), e.getCorrelationId(), e.getTenantId(), e.getResourceId(), payload);
                List<String> targets = routes.subscribersOf(e.getEventType());
                for (String baseUrl : targets) {
                    clients.forBaseUrl(baseUrl).post().uri("/internal/v1/events").body(env)
                            .retrieve().toBodilessEntity();
                }
                e.markPublished();
            } catch (Exception ex) {
                e.markFailed(ex.getMessage());
                log.warn("Event delivery failed for {} ({}), attempt {}: {}", e.getId(), e.getEventType(),
                        e.getAttempts(), ex.getMessage());
                break;
            }
        }
    }
}
