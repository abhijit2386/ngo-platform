package org.ngoplatform.commons.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ngoplatform.commons.events.EventTypes;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class MessagingConfig {

    @Bean
    MessageConverter jsonMessageConverter(ObjectMapper mapper) {
        Jackson2JsonMessageConverter c = new Jackson2JsonMessageConverter(mapper);
        c.setAlwaysConvertToInferredType(true); // listener parameter type wins over __TypeId__ header
        return c;
    }

    @Bean
    TopicExchange eventsExchange() { return new TopicExchange(EventTypes.EXCHANGE, true, false); }

    /**
     * Declares a durable consumer queue bound to the given routing keys, with a per-queue dead-letter queue
     * ("<name>.dlq") receiving messages that exhaust retries.
     */
    public static Declarables consumerQueue(String name, String... routingKeys) {
        TopicExchange ex = new TopicExchange(EventTypes.EXCHANGE, true, false);
        Queue dlq = QueueBuilder.durable(name + ".dlq").build();
        Queue q = QueueBuilder.durable(name)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", name + ".dlq")
                .build();
        java.util.List<org.springframework.amqp.core.Declarable> all = new java.util.ArrayList<>();
        all.add(dlq);
        all.add(q);
        for (String rk : routingKeys) {
            Binding b = BindingBuilder.bind(q).to(ex).with(rk);
            all.add(b);
        }
        return new Declarables(all);
    }
}
