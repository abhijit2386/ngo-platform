package org.ngoplatform.identity.app;

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
public class RegistryEventsListener implements EventHandler {
    static final String QUEUE = "identity.ngo-events";
    private final IdempotentConsumer inbox;
    private final StaffService staff;

    public RegistryEventsListener(IdempotentConsumer inbox, StaffService staff) {
        this.inbox = inbox;
        this.staff = staff;
    }

    @Bean
    static Declarables identityQueues() {
        return MessagingConfig.consumerQueue(QUEUE, EventTypes.NGO_REGISTERED);
    }

    @Override
    public String consumerName() { return QUEUE; }

    @Override
    public java.util.Set<String> eventTypes() { return java.util.Set.of(EventTypes.NGO_REGISTERED); }

    @RabbitListener(queues = QUEUE)
    @Override
    public void on(EventEnvelope e) {
        inbox.handle(QUEUE, e, () -> staff.bootstrapTenant(e.tenantId(), e.uuid("primaryAdminUserId"),
                e.str("primaryAdminEmail"), e.str("secondaryAdminEmail")));
    }
}
