package org.ngoplatform.notification.app;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.ngoplatform.commons.audit.AuditLog;
import org.ngoplatform.commons.events.EventPublisher;
import org.ngoplatform.commons.events.EventTypes;
import org.ngoplatform.notification.domain.Delivery;
import org.ngoplatform.notification.domain.Repos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends due deliveries. Each message is claimed with FOR UPDATE SKIP LOCKED in its own transaction, so several
 * instances can run side by side. Failures back off (1m, 5m, 30m, 2h) and dead-letter after 5 attempts.
 */
@Component
public class Dispatcher {
    private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);

    private final Repos.Deliveries deliveries;
    private final Map<String, ChannelSender> senders;
    private final TransactionTemplate tx;
    private final EventPublisher events;
    private final AuditLog audit;
    private final Clock clock;
    private final int batch;

    public Dispatcher(Repos.Deliveries deliveries, List<ChannelSender> senders, TransactionTemplate tx,
                      EventPublisher events, AuditLog audit, Clock clock,
                      @Value("${ngo.notifications.batch-size:20}") int batch) {
        this.deliveries = deliveries;
        this.senders = senders.stream().collect(Collectors.toMap(ChannelSender::channel, Function.identity()));
        this.tx = tx; this.events = events; this.audit = audit; this.clock = clock; this.batch = batch;
    }

    @Scheduled(fixedDelayString = "${ngo.notifications.dispatch-ms:5000}", initialDelay = 10000)
    public void tick() {
        try {
            for (var id : deliveries.findDueIds(clock.instant(), batch)) {
                tx.executeWithoutResult(s -> deliveries.lockIfDue(id).ifPresent(this::attempt));
            }
        } catch (RuntimeException e) {
            log.warn("notification dispatch round failed: {}", e.toString());
        }
    }

    void attempt(Delivery d) {
        Instant now = clock.instant();
        ChannelSender sender = senders.get(d.getChannel());
        try {
            if (sender == null) throw new IllegalStateException("No provider configured for channel " + d.getChannel());
            sender.send(d);
            d.sent(now);
            // Only receipt delivery has a downstream consumer today (receipt-service marks the receipt delivered).
            if ("RECEIPT".equals(d.getResourceType())) {
                events.publish(EventTypes.NOTIFICATION_DELIVERED, d.getNgoId(), d.getId(), payload(d));
            }
        } catch (Exception e) {
            boolean dead = d.failed(e.getClass().getSimpleName() + ": " + e.getMessage(), now);
            log.warn("delivery {} attempt {} failed{}: {}", d.getId(), d.getAttempts(), dead ? " (dead-lettered)" : "", e.toString());
            if (dead) {
                events.publish(EventTypes.NOTIFICATION_FAILED, d.getNgoId(), d.getId(), payload(d));
                audit.record(d.getNgoId(), null, "NOTIFICATION_DEAD_LETTERED", "NOTIFICATION_DELIVERY", d.getId(),
                        Map.of("template", d.getTemplateCode(), "attempts", d.getAttempts()));
            }
        }
        deliveries.save(d);
    }

    private static Map<String, Object> payload(Delivery d) {
        Map<String, Object> m = new HashMap<>();
        m.put("deliveryId", d.getId().toString());
        m.put("channel", d.getChannel());
        m.put("templateCode", d.getTemplateCode());
        if (d.getResourceType() != null) m.put("resourceType", d.getResourceType());
        if (d.getResourceId() != null) m.put("resourceId", d.getResourceId());
        return m;
    }
}
