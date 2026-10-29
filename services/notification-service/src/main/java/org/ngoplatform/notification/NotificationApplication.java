package org.ngoplatform.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"org.ngoplatform.commons", "org.ngoplatform.notification"})
@EntityScan({"org.ngoplatform.commons", "org.ngoplatform.notification"})
@EnableJpaRepositories(basePackages = {"org.ngoplatform.commons", "org.ngoplatform.notification"}, considerNestedRepositories = true)
public class NotificationApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }
}
