package org.ngoplatform.campaign.app;

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
    static final String QUEUE = "campaign.events";
    private final IdempotentConsumer inbox;
    private final CampaignService svc;

    public EventsListener(IdempotentConsumer inbox, CampaignService svc) { this.inbox = inbox; this.svc = svc; }

    @Bean
    static Declarables campaignQueues() {
        return MessagingConfig.consumerQueue(QUEUE, EventTypes.DONATION_PAID, EventTypes.DONATION_REFUNDED, EventTypes.EXPENSE_APPROVED);
    }

    @Override
    public String consumerName() { return QUEUE; }

    @Override
    public java.util.Set<String> eventTypes() { return java.util.Set.of(EventTypes.DONATION_PAID, EventTypes.DONATION_REFUNDED, EventTypes.EXPENSE_APPROVED); }

    @RabbitListener(queues = QUEUE)
    @Override
    public void on(EventEnvelope e) {
        inbox.handle(QUEUE, e, () -> {
            switch (e.eventType()) {
                case EventTypes.DONATION_PAID -> svc.onDonationPaid(e.tenantId(), e.uuid("donationId"), e.uuid("campaignId"),
                        e.uuid("projectId"), new BigDecimal(e.str("amount")));
                case EventTypes.DONATION_REFUNDED -> svc.onDonationRefunded(e.uuid("donationId"), new BigDecimal(e.str("refundedTotal")));
                case EventTypes.EXPENSE_APPROVED -> {
                    if (e.str("projectId") != null) svc.onExpenseApproved(e.tenantId(), e.uuid("expenseId"), e.uuid("projectId"), new BigDecimal(e.str("amount")));
                }
                default -> { }
            }
        });
    }
}
