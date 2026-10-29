package org.ngoplatform.receipt.app;

import java.math.BigDecimal;
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
    static final String QUEUE = "receipt.events";
    private final IdempotentConsumer inbox;
    private final ReceiptService svc;

    public EventsListener(IdempotentConsumer inbox, ReceiptService svc) { this.inbox = inbox; this.svc = svc; }

    @Bean
    static Declarables receiptQueues() {
        return MessagingConfig.consumerQueue(QUEUE, EventTypes.DONATION_PAID, EventTypes.DONATION_VOIDED,
                EventTypes.DONATION_REFUNDED, EventTypes.NOTIFICATION_DELIVERED);
    }

    @Override
    public String consumerName() { return QUEUE; }

    @Override
    public java.util.Set<String> eventTypes() { return java.util.Set.of(EventTypes.DONATION_PAID, EventTypes.DONATION_VOIDED,
                EventTypes.DONATION_REFUNDED, EventTypes.NOTIFICATION_DELIVERED); }

    @RabbitListener(queues = QUEUE)
    @Override
    public void on(EventEnvelope e) {
        inbox.handle(QUEUE, e, () -> {
            switch (e.eventType()) {
                case EventTypes.DONATION_PAID -> svc.onDonationPaid(e.tenantId(), e.uuid("donationId"), e.uuid("donorId"),
                        e.uuid("donorUserId"), new BigDecimal(e.str("amount")));
                case EventTypes.DONATION_VOIDED -> svc.voidForDonation(e.uuid("donationId"), "Donation corrected: " + e.str("reason"));
                case EventTypes.DONATION_REFUNDED -> {
                    if ("REFUNDED".equals(e.str("status"))) svc.voidForDonation(e.uuid("donationId"), "Donation fully refunded");
                }
                case EventTypes.NOTIFICATION_DELIVERED -> {
                    if ("RECEIPT".equals(e.str("resourceType"))) svc.markDelivered(e.uuid("resourceId"));
                }
                default -> { }
            }
        });
    }
}
