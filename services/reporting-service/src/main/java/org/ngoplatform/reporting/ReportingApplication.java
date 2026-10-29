package org.ngoplatform.reporting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"org.ngoplatform.commons", "org.ngoplatform.reporting"})
@EntityScan({"org.ngoplatform.commons", "org.ngoplatform.reporting"})
@EnableJpaRepositories(basePackages = {"org.ngoplatform.commons", "org.ngoplatform.reporting"}, considerNestedRepositories = true)
public class ReportingApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReportingApplication.class, args);
    }
}
