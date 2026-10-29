package org.ngoplatform.notification.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Communication preference per user and category (NOTIF-003..005). */
@Entity
@Table(name = "communication_preferences")
@IdClass(Preference.Key.class)
public class Preference {
    @Id @Column(name = "user_id") private UUID userId;
    @Id private String category;
    @Column(name = "email_enabled", nullable = false) private boolean emailEnabled = true;
    @Column(name = "in_app_enabled", nullable = false) private boolean inAppEnabled = true;
    @Column(name = "push_enabled", nullable = false) private boolean pushEnabled = true;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    protected Preference() {}

    public Preference(UUID userId, String category) { this.userId = userId; this.category = category; }

    public void set(boolean email, boolean inApp, boolean push) {
        this.emailEnabled = email; this.inAppEnabled = inApp; this.pushEnabled = push; this.updatedAt = Instant.now();
    }

    public UUID getUserId() { return userId; }
    public String getCategory() { return category; }
    public boolean isEmailEnabled() { return emailEnabled; }
    public boolean isInAppEnabled() { return inAppEnabled; }
    public boolean isPushEnabled() { return pushEnabled; }

    public static class Key implements Serializable {
        private UUID userId;
        private String category;
        public Key() {}
        public Key(UUID userId, String category) { this.userId = userId; this.category = category; }
        @Override public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(userId, k.userId) && Objects.equals(category, k.category);
        }
        @Override public int hashCode() { return Objects.hash(userId, category); }
    }
}
