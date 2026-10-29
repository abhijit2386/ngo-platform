package org.ngoplatform.registry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"org.ngoplatform.commons", "org.ngoplatform.registry"})
@EntityScan({"org.ngoplatform.commons", "org.ngoplatform.registry"})
@EnableJpaRepositories(basePackages = {"org.ngoplatform.commons", "org.ngoplatform.registry"}, considerNestedRepositories = true)
public class RegistryApplication {
    public static void main(String[] args) {
        SpringApplication.run(RegistryApplication.class, args);
    }
}
