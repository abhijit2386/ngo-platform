package org.ngoplatform.receipt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"org.ngoplatform.commons", "org.ngoplatform.receipt"})
@EntityScan({"org.ngoplatform.commons", "org.ngoplatform.receipt"})
@EnableJpaRepositories(basePackages = {"org.ngoplatform.commons", "org.ngoplatform.receipt"}, considerNestedRepositories = true)
public class ReceiptApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReceiptApplication.class, args);
    }
}
