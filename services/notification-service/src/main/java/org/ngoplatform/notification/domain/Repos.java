package org.ngoplatform.notification.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public final class Repos {
    private Repos() {}

    public interface Templates extends JpaRepository<Template, UUID> {
        Optional<Template> findByCodeAndChannelAndLanguage(String code, String channel, String language);
        List<Template> findAllByOrderByCodeAscChannelAscLanguageAsc();
        List<Template> findByCodeOrderByChannelAscLanguageAsc(String code);
    }

    public interface Notifications extends JpaRepository<Notification, UUID> {
        Page<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable page);
        Optional<Notification> findByIdAndUserId(UUID id, UUID userId);
        long countByUserIdAndReadAtIsNull(UUID userId);
        boolean existsBySourceEventIdAndUserIdAndTemplateCode(UUID eventId, UUID userId, String templateCode);

        @Modifying
        @Query(value = "update notifications set read_at = :at where user_id = :userId and read_at is null", nativeQuery = true)
        int markAllRead(UUID userId, Instant at);
    }

    public interface Deliveries extends JpaRepository<Delivery, UUID> {
        Page<Delivery> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable page);
        Page<Delivery> findByStatusOrderByCreatedAtDesc(Delivery.Status status, Pageable page);
        boolean existsBySourceEventIdAndChannelAndRecipientAndTemplateCode(UUID eventId, String channel, String recipient,
                                                                          String templateCode);

        /** Due work, oldest first. */
        @Query(value = "select id from notification_deliveries where status in ('PENDING','FAILED') "
                + "and next_attempt_at <= :now order by next_attempt_at limit :limit", nativeQuery = true)
        List<UUID> findDueIds(Instant now, int limit);

        /** Claims one row; another dispatcher instance holding it is skipped (horizontal scaling). */
        @Query(value = "select * from notification_deliveries where id = :id and status in ('PENDING','FAILED') "
                + "for update skip locked", nativeQuery = true)
        Optional<Delivery> lockIfDue(UUID id);
    }

    public interface Preferences extends JpaRepository<Preference, Preference.Key> {
        List<Preference> findByUserId(UUID userId);
    }
}
