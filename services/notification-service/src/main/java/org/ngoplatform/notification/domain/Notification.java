package org.ngoplatform.notification.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** In-app inbox item (NOTIF-001/002). */
@Entity
@Table(name = "notifications")
public class Notification {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "ngo_id") private UUID ngoId;
    @Column(nullable = false) private String category;
    @Column(name = "template_code", nullable = false) private String templateCode;
    @Column(nullable = false) private String title;
    @Column(nullable = false) private String body;
    private String link;
    @Column(name = "source_event_id") private UUID sourceEventId;
    @Column(name = "read_at") private Instant readAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    protected Notification() {}

    public Notification(UUID userId, UUID ngoId, String category, String templateCode, String title, String body,
                        String link, UUID sourceEventId) {
        this.id = UUID.randomUUID(); this.userId = userId; this.ngoId = ngoId; this.category = category;
        this.templateCode = templateCode; this.title = Text.clip(title, 200); this.body = Text.clip(body, 2000);
        this.link = link; this.sourceEventId = sourceEventId;
    }

    public void markRead(Instant at) { if (readAt == null) readAt = at; }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getNgoId() { return ngoId; }
    public String getCategory() { return category; }
    public String getTemplateCode() { return templateCode; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public String getLink() { return link; }
    public Instant getReadAt() { return readAt; }
    public Instant getCreatedAt() { return createdAt; }
}
