package org.ngoplatform.commons.events;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Every integration event (spec §4.3). Payload carries minimal data: identifiers and amounts, not personal data
 * beyond what the consumer strictly needs.
 */
public record EventEnvelope(UUID eventId, String eventType, int eventVersion, Instant occurredAt,
                            String correlationId, UUID tenantId, String resourceId, Map<String, Object> payload) {

    public String str(String key) { Object v = payload.get(key); return v == null ? null : String.valueOf(v); }
    public UUID uuid(String key) { String v = str(key); return v == null ? null : UUID.fromString(v); }
}
