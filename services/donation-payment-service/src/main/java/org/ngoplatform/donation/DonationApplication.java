package org.ngoplatform.donation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"org.ngoplatform.commons", "org.ngoplatform.donation"})
@EntityScan({"org.ngoplatform.commons", "org.ngoplatform.donation"})
@EnableJpaRepositories(basePackages = {"org.ngoplatform.commons", "org.ngoplatform.donation"}, considerNestedRepositories = true)
public class DonationApplication {
    public static void main(String[] args) {
        SpringApplication.run(DonationApplication.class, args);
    }
}
