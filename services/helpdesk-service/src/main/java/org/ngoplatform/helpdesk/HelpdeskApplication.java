package org.ngoplatform.helpdesk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"org.ngoplatform.commons", "org.ngoplatform.helpdesk"})
@EntityScan({"org.ngoplatform.commons", "org.ngoplatform.helpdesk"})
@EnableJpaRepositories(basePackages = {"org.ngoplatform.commons", "org.ngoplatform.helpdesk"}, considerNestedRepositories = true)
public class HelpdeskApplication {
    public static void main(String[] args) {
        SpringApplication.run(HelpdeskApplication.class, args);
    }
}
