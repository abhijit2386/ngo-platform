package org.ngoplatform.subscription.app;

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
    static final String QUEUE = "subscription.ngo-events";
    private final IdempotentConsumer inbox;
    private final SubscriptionService service;

    public EventsListener(IdempotentConsumer inbox, SubscriptionService service) {
        this.inbox = inbox;
        this.service = service;
    }

    @Bean
    static Declarables subscriptionQueues() {
        return MessagingConfig.consumerQueue(QUEUE, EventTypes.NGO_VERIFIED);
    }

    @Override
    public String consumerName() { return QUEUE; }

    @Override
    public java.util.Set<String> eventTypes() { return java.util.Set.of(EventTypes.NGO_VERIFIED); }

    @RabbitListener(queues = QUEUE)
    @Override
    public void on(EventEnvelope e) {
        inbox.handle(QUEUE, e, () -> service.startTrial(e.tenantId()));
    }
}
