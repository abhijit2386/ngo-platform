package org.ngoplatform.notification.domain;

import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One outbound message on one channel. Lifecycle: PENDING -> SENT, or PENDING -> FAILED (retry scheduled) -> ... ->
 * DEAD after {@link #MAX_ATTEMPTS} (dead letter, replayable by platform support). SUPPRESSED = opted out.
 */
@Entity
@Table(name = "notification_deliveries")
public class Delivery {
    public enum Status { PENDING, SENT, FAILED, DEAD, SUPPRESSED }

    public static final int MAX_ATTEMPTS = 5;
    /** Wait before attempt n+1 (index = attempts already made - 1). */
    static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
            Duration.ofHours(2));

    @Id private UUID id;
    @Column(name = "user_id") private UUID userId;
    @Column(name = "ngo_id") private UUID ngoId;
    @Column(nullable = false) private String channel;
    @Column(nullable = false) private String recipient;
    @Column(nullable = false) private String category;
    @Column(name = "template_code", nullable = false) private String templateCode;
    @Column(nullable = false) private String language;
    private String subject;
    @Column(nullable = false) private String body;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status = Status.PENDING;
    @Column(nullable = false) private int attempts;
    @Column(name = "next_attempt_at", nullable = false) private Instant nextAttemptAt = Instant.now();
    @Column(name = "last_error") private String lastError;
    @Column(name = "resource_type") private String resourceType;
    @Column(name = "resource_id") private String resourceId;
    @Column(name = "source_event_id") private UUID sourceEventId;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "sent_at") private Instant sentAt;
    @Version private long version;

    protected Delivery() {}

    public Delivery(UUID userId, UUID ngoId, String channel, String recipient, String category, String templateCode,
                    String language, String subject, String body, String resourceType, String resourceId,
                    UUID sourceEventId) {
        this.id = UUID.randomUUID(); this.userId = userId; this.ngoId = ngoId; this.channel = channel;
        this.recipient = recipient.trim().toLowerCase(); this.category = category; this.templateCode = templateCode;
        this.language = language; this.subject = Text.clip(subject, 200); this.body = body;
        this.resourceType = resourceType; this.resourceId = resourceId; this.sourceEventId = sourceEventId;
    }

    public void suppress(String reason) { status = Status.SUPPRESSED; lastError = Text.clip(reason, 500); }

    public void sent(Instant at) { status = Status.SENT; sentAt = at; attempts++; lastError = null; }

    /** Records a failed attempt; returns true when the message is now dead-lettered. */
    public boolean failed(String error, Instant now) {
        attempts++;
        lastError = Text.clip(error == null ? "unknown error" : error, 500);
        if (attempts >= MAX_ATTEMPTS) { status = Status.DEAD; return true; }
        status = Status.FAILED;
        nextAttemptAt = now.plus(BACKOFF.get(Math.min(attempts - 1, BACKOFF.size() - 1)));
        return false;
    }

    /** Dead-letter replay (platform support): one more round of attempts. */
    public void requeue(Instant now) {
        if (status != Status.DEAD && status != Status.FAILED) {
            throw new IllegalStateException("Only failed or dead deliveries can be retried");
        }
        status = Status.PENDING; attempts = 0; nextAttemptAt = now;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getNgoId() { return ngoId; }
    public String getChannel() { return channel; }
    public String getRecipient() { return recipient; }
    public String getCategory() { return category; }
    public String getTemplateCode() { return templateCode; }
    public String getLanguage() { return language; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public Status getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLastError() { return lastError; }
    public String getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }
}
