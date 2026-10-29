package org.ngoplatform.commons.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.ngoplatform.commons.web.CorrelationIdFilter;
import org.springframework.stereotype.Component;

/** Records who did what. Callers must not put secrets or unnecessary personal data in details. */
@Component
public class AuditLog {
    private final AuditRepository repo;
    private final ObjectMapper mapper;

    public AuditLog(AuditRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    public void record(UUID tenantId, UUID actorId, String action, String resourceType, Object resourceId,
                       Map<String, ?> details) {
        String json;
        try {
            json = details == null ? null : mapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            json = null;
        }
        repo.save(new AuditEvent(tenantId, actorId, action, resourceType,
                resourceId == null ? null : resourceId.toString(), json, CorrelationIdFilter.current()));
    }
}
