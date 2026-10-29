package org.ngoplatform.donor.app;

import java.math.BigDecimal;
import java.time.Instant;
import org.ngoplatform.commons.config.MessagingConfig;
import org.ngoplatform.commons.events.EventEnvelope;
import org.ngoplatform.commons.events.EventHandler;
import org.ngoplatform.commons.events.EventTypes;
import org.ngoplatform.commons.events.IdempotentConsumer;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public class EventsListener implements EventHandler {
    static final String QUEUE = "donor.donation-events";
    private final IdempotentConsumer inbox;
    private final DonorService svc;

    public EventsListener(IdempotentConsumer inbox, DonorService svc) { this.inbox = inbox; this.svc = svc; }

    @Bean
    static Declarables donorQueues() {
        return MessagingConfig.consumerQueue(QUEUE, EventTypes.DONATION_PAID, EventTypes.DONATION_REFUNDED);
    }

    @Override
    public String consumerName() { return QUEUE; }

    @Override
    public java.util.Set<String> eventTypes() { return java.util.Set.of(EventTypes.DONATION_PAID, EventTypes.DONATION_REFUNDED); }

    @RabbitListener(queues = QUEUE)
    @Override
    public void on(EventEnvelope e) {
        inbox.handle(QUEUE, e, () -> {
            if (EventTypes.DONATION_PAID.equals(e.eventType())) {
                svc.applyPaidDonation(e.uuid("donationId"), e.tenantId(), e.uuid("donorId"), new BigDecimal(e.str("amount")),
                        e.uuid("campaignId"), Instant.parse(e.str("paidAt")));
            } else {
                svc.applyRefund(e.uuid("donationId"), new BigDecimal(e.str("refundedTotal")));
            }
        });
    }
}
