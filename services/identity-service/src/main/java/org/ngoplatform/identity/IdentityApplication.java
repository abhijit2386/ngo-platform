package org.ngoplatform.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"org.ngoplatform.commons", "org.ngoplatform.identity"})
@EntityScan({"org.ngoplatform.commons", "org.ngoplatform.identity"})
@EnableJpaRepositories(basePackages = {"org.ngoplatform.commons", "org.ngoplatform.identity"}, considerNestedRepositories = true)
public class IdentityApplication {
    public static void main(String[] args) {
        SpringApplication.run(IdentityApplication.class, args);
    }
}
