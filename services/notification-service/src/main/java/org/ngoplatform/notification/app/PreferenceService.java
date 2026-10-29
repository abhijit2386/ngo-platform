package org.ngoplatform.notification.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.ngoplatform.commons.audit.AuditLog;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.notification.domain.Preference;
import org.ngoplatform.notification.domain.Repos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Communication preferences and opt-outs (NOTIF-003..005). Mandatory categories always deliver. */
@Service
public class PreferenceService {
    public enum Channel { EMAIL, IN_APP, PUSH }

    public record Setting(String category, boolean email, boolean inApp, boolean push, boolean mandatory) {}

    private final Repos.Preferences prefs;
    private final JdbcTemplate jdbc;
    private final AuditLog audit;

    public PreferenceService(Repos.Preferences prefs, JdbcTemplate jdbc, AuditLog audit) {
        this.prefs = prefs; this.jdbc = jdbc; this.audit = audit;
    }

    public boolean allows(UUID userId, String category, Channel channel) {
        if (Categories.MANDATORY.contains(category) || userId == null) return true;
        return prefs.findById(new Preference.Key(userId, category)).map(p -> switch (channel) {
            case EMAIL -> p.isEmailEnabled();
            case IN_APP -> p.isInAppEnabled();
            case PUSH -> p.isPushEnabled();
        }).orElse(true);
    }

    public boolean emailOptedOut(String email) {
        Integer n = jdbc.queryForObject("select count(*) from email_opt_outs where email = ?", Integer.class,
                email.trim().toLowerCase());
        return n != null && n > 0;
    }

    @Transactional(readOnly = true)
    public List<Setting> settings(UUID userId) {
        Map<String, Preference> saved = prefs.findByUserId(userId).stream()
                .collect(Collectors.toMap(Preference::getCategory, Function.identity()));
        List<Setting> out = new ArrayList<>();
        for (String c : Categories.ALL) {
            boolean mandatory = Categories.MANDATORY.contains(c);
            Preference p = saved.get(c);
            out.add(p == null || mandatory ? new Setting(c, true, true, p == null || p.isPushEnabled(), mandatory)
                    : new Setting(c, p.isEmailEnabled(), p.isInAppEnabled(), p.isPushEnabled(), false));
        }
        return out;
    }

    @Transactional
    public List<Setting> update(UUID userId, List<Setting> changes) {
        for (Setting s : changes) {
            if (!Categories.isKnown(s.category())) throw ApiException.badRequest("UNKNOWN_CATEGORY", "Unknown category " + s.category());
            if (Categories.MANDATORY.contains(s.category()) && (!s.email() || !s.inApp())) {
                throw ApiException.rule("MANDATORY_CATEGORY", "Account and security messages cannot be switched off");
            }
            Preference p = prefs.findById(new Preference.Key(userId, s.category()))
                    .orElseGet(() -> new Preference(userId, s.category()));
            p.set(s.email(), s.inApp(), s.push());
            prefs.save(p);
        }
        audit.record(null, userId, "NOTIFICATION_PREFERENCES_UPDATED", "USER", userId,
                Map.of("categories", changes.stream().map(Setting::category).toList()));
        return settings(userId);
    }
}
