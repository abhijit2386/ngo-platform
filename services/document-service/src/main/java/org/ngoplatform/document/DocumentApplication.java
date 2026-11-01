package org.ngoplatform.document;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Document vault (one register of every NGO file, kept in the shared object store) and meetings / minutes. */
@SpringBootApplication(scanBasePackages = {"org.ngoplatform.commons", "org.ngoplatform.document"})
@EntityScan({"org.ngoplatform.commons", "org.ngoplatform.document"})
@EnableJpaRepositories(basePackages = {"org.ngoplatform.commons", "org.ngoplatform.document"}, considerNestedRepositories = true)
@EnableScheduling
public class DocumentApplication {
    public static void main(String[] args) {
        SpringApplication.run(DocumentApplication.class, args);
    }
}
