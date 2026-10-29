package org.ngoplatform.commons.events;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InboxRepository extends JpaRepository<InboxRecord, UUID> {
    boolean existsByConsumerAndEventId(String consumer, UUID eventId);
}
