package org.ngoplatform.notification.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** A message template per (code, channel, language). Placeholders use {{name}}. */
@Entity
@Table(name = "notification_templates")
public class Template {
    @Id private UUID id;
    @Column(nullable = false) private String code;
    @Column(nullable = false) private String channel;
    @Column(nullable = false) private String language;
    @Column(nullable = false) private String category;
    private String subject;
    @Column(nullable = false) private String body;
    @Column(nullable = false) private boolean active = true;
    @Column(name = "updated_by") private UUID updatedBy;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    @Version private long version;

    protected Template() {}

    public Template(String code, String channel, String language, String category, String subject, String body) {
        this.id = UUID.randomUUID(); this.code = code; this.channel = channel; this.language = language;
        this.category = category; this.subject = subject; this.body = body;
    }

    public void update(String subject, String body, boolean active, UUID actor) {
        this.subject = subject; this.body = body; this.active = active; this.updatedBy = actor; this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getChannel() { return channel; }
    public String getLanguage() { return language; }
    public String getCategory() { return category; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public boolean isActive() { return active; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
