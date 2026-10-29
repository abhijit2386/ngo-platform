package org.ngoplatform.subscription;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"org.ngoplatform.commons", "org.ngoplatform.subscription"})
@EntityScan({"org.ngoplatform.commons", "org.ngoplatform.subscription"})
@EnableJpaRepositories(basePackages = {"org.ngoplatform.commons", "org.ngoplatform.subscription"}, considerNestedRepositories = true)
public class SubscriptionApplication {
    public static void main(String[] args) {
        SpringApplication.run(SubscriptionApplication.class, args);
    }
}
