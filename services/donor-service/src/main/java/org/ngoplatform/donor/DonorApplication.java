package org.ngoplatform.donor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"org.ngoplatform.commons", "org.ngoplatform.donor"})
@EntityScan({"org.ngoplatform.commons", "org.ngoplatform.donor"})
@EnableJpaRepositories(basePackages = {"org.ngoplatform.commons", "org.ngoplatform.donor"}, considerNestedRepositories = true)
public class DonorApplication {
    public static void main(String[] args) {
        SpringApplication.run(DonorApplication.class, args);
    }
}
