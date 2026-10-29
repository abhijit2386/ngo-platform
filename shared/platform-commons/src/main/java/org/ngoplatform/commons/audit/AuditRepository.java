package org.ngoplatform.commons.audit;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditRepository extends JpaRepository<AuditEvent, UUID> {
    Page<AuditEvent> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);
    Page<AuditEvent> findByTenantIdAndResourceTypeAndResourceIdOrderByCreatedAtDesc(
            UUID tenantId, String resourceType, String resourceId, Pageable pageable);
}
