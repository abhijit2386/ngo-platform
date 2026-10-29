package org.ngoplatform.subscription.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.ngoplatform.commons.events.EventPublisher;
import org.ngoplatform.commons.events.EventTypes;
import org.ngoplatform.subscription.domain.Repos;
import org.ngoplatform.subscription.domain.Subscription;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Time-based lifecycle and reminder dispatch (15/7/1 days before, on expiry, in grace, on suspension). */
@Component
public class Scheduler {
    private final SubscriptionService service;
    private final Repos.Subscriptions subs;
    private final EventPublisher events;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public Scheduler(SubscriptionService service, Repos.Subscriptions subs, EventPublisher events, JdbcTemplate jdbc,
                     Clock clock) {
        this.service = service; this.subs = subs; this.events = events; this.jdbc = jdbc; this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${ngo.billing.lifecycle-ms:300000}", initialDelay = 30000)
    public void tick() {
        service.advanceLifecycle();
        sendReminders();
    }

    @Transactional
    public void sendReminders() {
        Instant now = clock.instant();
        for (Subscription s : subs.findByStatusInAndPeriodEndBetween(
                List.of(Subscription.Status.TRIAL, Subscription.Status.ACTIVE), now, now.plus(Duration.ofDays(16)))) {
            long days = ChronoUnit.DAYS.between(now, s.getPeriodEnd());
            String kind = days <= 1 ? "D1" : days <= 7 ? "D7" : days <= 15 ? "D15" : null;
            if (kind != null && !s.isCancelAtPeriodEnd()) remind(s, kind, days);
        }
        for (Subscription s : subs.findByStatusInAndPeriodEndBetween(List.of(Subscription.Status.GRACE),
                now.minus(Duration.ofDays(30)), now)) {
            remind(s, "EXPIRED", 0);
            long graceLeft = s.getGraceEndsAt() == null ? 0 : ChronoUnit.DAYS.between(now, s.getGraceEndsAt());
            if (graceLeft <= 3) remind(s, "GRACE_ENDING", graceLeft);
        }
        for (Subscription s : subs.findByStatusInAndPeriodEndBetween(List.of(Subscription.Status.SUSPENDED),
                now.minus(Duration.ofDays(60)), now)) {
            remind(s, "SUSPENDED", 0);
        }
    }

    private void remind(Subscription s, String kind, long daysLeft) {
        int inserted = jdbc.update("insert into subscription_reminders(ngo_id, period_end, kind) values (?,?,?) "
                + "on conflict do nothing", s.getNgoId(), java.sql.Timestamp.from(s.getPeriodEnd()), kind);
        if (inserted == 1) {
            events.publish(EventTypes.SUBSCRIPTION_EXPIRING, s.getNgoId(), s.getId(), Map.of("kind", kind,
                    "daysLeft", daysLeft, "plan", s.getPlanCode(), "status", s.getStatus().name(),
                    "periodEnd", s.getPeriodEnd().toString()));
        }
    }
}
