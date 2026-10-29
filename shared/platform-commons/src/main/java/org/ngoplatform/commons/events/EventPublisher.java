package org.ngoplatform.commons.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.ngoplatform.commons.web.CorrelationIdFilter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Transactional outbox writer: the event row commits atomically with the business change. */
@Component
public class EventPublisher {
    private final OutboxRepository repo;
    private final ObjectMapper mapper;

    public EventPublisher(OutboxRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publish(String type, UUID tenantId, Object resourceId, Map<String, Object> payload) {
        return publish(type, 1, tenantId, resourceId, payload);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publish(String type, int version, UUID tenantId, Object resourceId, Map<String, Object> payload) {
        UUID id = UUID.randomUUID();
        try {
            repo.save(new OutboxEvent(id, type, version, tenantId, resourceId == null ? null : resourceId.toString(),
                    CorrelationIdFilter.current(), mapper.writeValueAsString(payload)));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Event payload not serialisable", e);
        }
        return id;
    }
}
