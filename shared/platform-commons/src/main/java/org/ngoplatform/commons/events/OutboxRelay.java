package org.ngoplatform.commons.events;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Relays committed outbox rows to RabbitMQ with publisher confirms (production transport). At-least-once: consumers must be idempotent.
 */
@Component
@ConditionalOnProperty(name = "ngo.events.transport", havingValue = "rabbit")
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private final OutboxRepository repo;
    private final RabbitTemplate rabbit;
    private final ObjectMapper mapper;

    public OutboxRelay(OutboxRepository repo, RabbitTemplate rabbit, ObjectMapper mapper, MeterRegistry meters) {
        this.repo = repo;
        this.rabbit = rabbit;
        this.mapper = mapper;
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
                rabbit.invoke(ops -> {
                    ops.convertAndSend(EventTypes.EXCHANGE, e.getEventType(), env, m -> {
                        m.getMessageProperties().setMessageId(e.getId().toString());
                        m.getMessageProperties().setCorrelationId(e.getCorrelationId());
                        m.getMessageProperties().setDeliveryMode(
                                org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
                        return m;
                    });
                    ops.waitForConfirmsOrDie(5000);
                    return null;
                });
                e.markPublished();
            } catch (Exception ex) {
                e.markFailed(ex.getMessage());
                log.warn("Outbox publish failed for {} ({}), attempt {}", e.getId(), e.getEventType(), e.getAttempts());
                break; // keep ordering; retry next tick
            }
        }
    }
}
