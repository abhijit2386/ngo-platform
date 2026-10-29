package org.ngoplatform.campaign;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"org.ngoplatform.commons", "org.ngoplatform.campaign"})
@EntityScan({"org.ngoplatform.commons", "org.ngoplatform.campaign"})
@EnableJpaRepositories(basePackages = {"org.ngoplatform.commons", "org.ngoplatform.campaign"}, considerNestedRepositories = true)
public class CampaignApplication {
    public static void main(String[] args) {
        SpringApplication.run(CampaignApplication.class, args);
    }
}
