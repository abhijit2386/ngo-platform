package org.ngoplatform.notification.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.ngoplatform.notification.domain.Delivery;
import org.ngoplatform.notification.domain.Notification;
import org.ngoplatform.notification.domain.Template;

public final class Dtos {
    private Dtos() {}

    public record NotificationView(UUID id, UUID ngoId, String category, String templateCode, String title, String body,
                                   String link, boolean read, Instant createdAt) {
        static NotificationView of(Notification n) {
            return new NotificationView(n.getId(), n.getNgoId(), n.getCategory(), n.getTemplateCode(), n.getTitle(),
                    n.getBody(), n.getLink(), n.getReadAt() != null, n.getCreatedAt());
        }
    }

    public record UnreadCount(long unread) {}

    /** Communication history (NOTIF-006) and delivery failures (NOTIF-007) as the user sees them. */
    public record DeliveryView(UUID id, String channel, String recipient, String category, String templateCode,
                               String subject, String status, int attempts, String lastError, Instant createdAt,
                               Instant sentAt, Instant nextAttemptAt) {
        static DeliveryView of(Delivery d) {
            boolean retrying = d.getStatus() == Delivery.Status.FAILED;
            return new DeliveryView(d.getId(), d.getChannel(), d.getRecipient(), d.getCategory(), d.getTemplateCode(),
                    d.getSubject(), d.getStatus().name(), d.getAttempts(), d.getLastError(), d.getCreatedAt(),
                    d.getSentAt(), retrying ? d.getNextAttemptAt() : null);
        }
    }

    public record PreferenceView(String category, boolean email, boolean inApp, boolean push, boolean mandatory) {}

    public record PreferenceChange(@NotBlank String category, boolean email, boolean inApp, boolean push) {}

    public record PreferencesUpdate(@NotEmpty @Size(max = 20) List<@Valid PreferenceChange> preferences) {}

    public record TemplateView(UUID id, String code, String channel, String language, String category, String subject,
                               String body, boolean active, Set<String> placeholders, Instant updatedAt, long version) {
        static TemplateView of(Template t, Set<String> placeholders) {
            return new TemplateView(t.getId(), t.getCode(), t.getChannel(), t.getLanguage(), t.getCategory(),
                    t.getSubject(), t.getBody(), t.isActive(), placeholders, t.getUpdatedAt(), t.getVersion());
        }
    }

    public record TemplateUpdate(@Size(max = 200) String subject, @NotBlank @Size(max = 10000) String body,
                                 boolean active, @NotNull Long version) {}

    public record PreviewRequest(@Size(max = 200) String subject, @Size(max = 10000) String body,
                                 Map<String, String> sample) {}

    public record Preview(String subject, String body, Set<String> placeholders) {}

    /** Service-to-service send (e.g. receipt delivery). */
    public record SendRequest(@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{2,79}") String templateCode, UUID ngoId,
                              UUID userId, @Email @Size(max = 254) String email, String language,
                              Map<String, Object> vars, @Size(max = 300) String link,
                              @Size(max = 40) String resourceType, @Size(max = 100) String resourceId,
                              UUID requestId, boolean inApp, boolean sendEmail) {}

    public record SendResult(int inApp, int emailsQueued, int suppressed) {}
}
