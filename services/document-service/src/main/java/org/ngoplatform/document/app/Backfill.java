package org.ngoplatform.document.app;

import java.util.List;
import java.util.Map;
import org.ngoplatform.commons.config.InternalClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Files that existed before the vault (registration documents, expense bills) are listed once per source service.
 * Runs shortly after start and retries every few minutes until each source answered; idempotent per file.
 */
@Component
public class Backfill {
    private static final Logger log = LoggerFactory.getLogger(Backfill.class);
    private final JdbcTemplate jdbc;
    private final DocumentService docs;
    private final Map<String, String> sources;
    private final InternalClients clients;

    public Backfill(JdbcTemplate jdbc, DocumentService docs, InternalClients clients,
                    @Value("${ngo.services.ngo-registry}") String registry, @Value("${ngo.services.campaign}") String campaign) {
        this.jdbc = jdbc; this.docs = docs; this.clients = clients;
        this.sources = Map.of("REGISTRATION", registry + "/internal/v1/ngos/document-files",
                "EXPENSE_BILL", campaign + "/internal/v1/document-files");
    }

    @Scheduled(initialDelayString = "${ngo.documents.backfill-delay-ms:60000}", fixedDelayString = "${ngo.documents.backfill-retry-ms:300000}")
    public void run() {
        for (var s : sources.entrySet()) {
            Integer done = jdbc.queryForObject("select count(*) from backfill_state where source = ?", Integer.class, s.getKey());
            if (done != null && done > 0) continue;
            try {
                List<Map<String, Object>> files = clients.forBaseUrl(s.getValue().substring(0, s.getValue().indexOf("/internal")))
                        .get().uri(s.getValue().substring(s.getValue().indexOf("/internal"))).retrieve()
                        .body(new ParameterizedTypeReference<>() {});
                int n = 0;
                for (Map<String, Object> f : files == null ? List.<Map<String, Object>>of() : files) {
                    docs.registerExternal(EventsListener.external(null, f));
                    n++;
                }
                jdbc.update("insert into backfill_state(source, files) values (?, ?) on conflict do nothing", s.getKey(), n);
                log.info("document backfill {}: {} files", s.getKey(), n);
            } catch (RuntimeException e) {
                log.info("document backfill {} not done yet: {}", s.getKey(), e.getMessage());
            }
        }
    }
}
