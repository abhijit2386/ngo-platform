package org.ngoplatform.commons.config;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CommonBeans {
    /** Injected everywhere time matters so tests can control it. */
    @Bean
    @ConditionalOnMissingBean
    Clock clock() { return Clock.systemUTC(); }
}
