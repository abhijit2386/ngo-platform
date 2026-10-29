package org.ngoplatform.notification.app;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.ngoplatform.notification.domain.Delivery;
import org.ngoplatform.notification.domain.Notification;
import org.ngoplatform.notification.domain.Repos;
import org.ngoplatform.notification.domain.Template;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns "tell these people X" into in-app notifications and queued e-mail deliveries, honouring language,
 * preferences and opt-outs. Idempotent per (source event, recipient, template): replays create nothing new.
 */
@Service
public class NotificationPlanner {
    private static final Logger log = LoggerFactory.getLogger(NotificationPlanner.class);
    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    public record Recipient(UUID userId, String email, String name, String language) {
        static Recipient of(Directory.Person p) { return new Recipient(p.userId(), p.email(), p.name(), p.language()); }
    }

    /**
     * @param vars template variables; keys ending in "Code" (roleCode, planCode) are localized into the key without
     *             the suffix, keys ending in "At" holding ISO instants are formatted as dates into "...On".
     */
    public record Request(String templateCode, UUID ngoId, List<Recipient> recipients, Map<String, Object> vars,
                          String link, String resourceType, String resourceId, UUID sourceEventId,
                          boolean inApp, boolean email) {}

    public record Result(int inApp, int emailsQueued, int suppressed) {}

    private final Repos.Templates templates;
    private final Repos.Notifications notifications;
    private final Repos.Deliveries deliveries;
    private final PreferenceService preferences;

    public NotificationPlanner(Repos.Templates templates, Repos.Notifications notifications, Repos.Deliveries deliveries,
                               PreferenceService preferences) {
        this.templates = templates; this.notifications = notifications; this.deliveries = deliveries;
        this.preferences = preferences;
    }

    @Transactional
    public Result plan(Request r) {
        int inApp = 0, queued = 0, suppressed = 0;
        Map<String, Recipient> unique = new LinkedHashMap<>();
        for (Recipient p : r.recipients()) {
            String key = p.userId() != null ? p.userId().toString() : (p.email() == null ? null : p.email().toLowerCase());
            if (key != null) unique.putIfAbsent(key, p);
        }
        for (Recipient p : unique.values()) {
            String lang = Labels.language(p.language());
            Map<String, Object> vars = localize(r.vars(), lang);
            if (r.inApp() && p.userId() != null) {
                Optional<Template> t = template(r.templateCode(), "IN_APP", lang);
                if (t.isPresent() && preferences.allows(p.userId(), t.get().getCategory(), PreferenceService.Channel.IN_APP)
                        && (r.sourceEventId() == null || !notifications.existsBySourceEventIdAndUserIdAndTemplateCode(
                                r.sourceEventId(), p.userId(), r.templateCode()))) {
                    notifications.save(new Notification(p.userId(), r.ngoId(), t.get().getCategory(), r.templateCode(),
                            TemplateRenderer.render(t.get().getSubject(), vars), TemplateRenderer.render(t.get().getBody(), vars),
                            r.link(), r.sourceEventId()));
                    inApp++;
                }
            }
            if (r.email() && p.email() != null && !p.email().isBlank()) {
                Optional<Template> t = template(r.templateCode(), "EMAIL", lang);
                String to = p.email().trim().toLowerCase();
                if (t.isEmpty() || (r.sourceEventId() != null && deliveries.existsBySourceEventIdAndChannelAndRecipientAndTemplateCode(
                        r.sourceEventId(), "EMAIL", to, r.templateCode()))) continue;
                String category = t.get().getCategory();
                Delivery d = new Delivery(p.userId(), r.ngoId(), "EMAIL", to, category, r.templateCode(),
                        t.get().getLanguage(), TemplateRenderer.render(t.get().getSubject(), vars),
                        TemplateRenderer.render(t.get().getBody(), vars), r.resourceType(), r.resourceId(), r.sourceEventId());
                boolean mandatory = Categories.MANDATORY.contains(category);
                if (!mandatory && (!preferences.allows(p.userId(), category, PreferenceService.Channel.EMAIL)
                        || preferences.emailOptedOut(to))) {
                    d.suppress("OPTED_OUT");
                    suppressed++;
                } else {
                    queued++;
                }
                deliveries.save(d);
            }
        }
        log.info("notification planned template={} inApp={} emails={} suppressed={}", r.templateCode(), inApp, queued, suppressed);
        return new Result(inApp, queued, suppressed);
    }

    /** Active template in the language, falling back to English. */
    Optional<Template> template(String code, String channel, String lang) {
        return templates.findByCodeAndChannelAndLanguage(code, channel, lang).filter(Template::isActive)
                .or(() -> templates.findByCodeAndChannelAndLanguage(code, channel, "en").filter(Template::isActive));
    }

    static Map<String, Object> localize(Map<String, Object> vars, String lang) {
        Map<String, Object> out = new HashMap<>(vars);
        vars.forEach((k, v) -> {
            if (v == null) return;
            if (k.equals("roleCode")) out.put("role", Labels.role(v.toString(), lang));
            else if (k.equals("planCode")) out.put("plan", Labels.plan(v.toString()));
            else if (k.endsWith("At") && k.length() > 2) {
                try {
                    out.put(k.substring(0, k.length() - 2) + "On", DATE.format(Instant.parse(v.toString()).atZone(INDIA)));
                } catch (RuntimeException ignored) { /* not an instant */ }
            }
        });
        return out;
    }
}
