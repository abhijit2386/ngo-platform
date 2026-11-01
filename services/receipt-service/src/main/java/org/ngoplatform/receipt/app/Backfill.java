package org.ngoplatform.receipt.app;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.ngoplatform.receipt.domain.Repos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Donations paid before receipt-service ran get their receipts once (idempotent: one live receipt per donation).
 * Retries every 5 minutes until donation-service answers, then never again.
 */
@Component
public class Backfill {
    private static final Logger log = LoggerFactory.getLogger(Backfill.class);
    private final Facts facts;
    private final ReceiptService receipts;
    private final Repos.Receipts repo;
    private final JdbcTemplate jdbc;

    public Backfill(Facts facts, ReceiptService receipts, Repos.Receipts repo, JdbcTemplate jdbc) {
        this.facts = facts; this.receipts = receipts; this.repo = repo; this.jdbc = jdbc;
    }

    @Scheduled(initialDelayString = "${ngo.receipts.backfill-delay-ms:45000}", fixedDelay = 300_000)
    public void run() {
        Integer done = jdbc.queryForObject("select count(*) from backfill_state where name = 'DONATIONS'", Integer.class);
        if (done != null && done > 0) return;
        try {
            int created = 0;
            for (int page = 0; page < 10_000; page++) {
                List<Map<String, Object>> list = facts.paidDonations(page);
                if (list == null || list.isEmpty()) break;
                for (Map<String, Object> d : list) {
                    UUID donationId = UUID.fromString(String.valueOf(d.get("donationId")));
                    if (repo.existsByDonationId(donationId)) continue;
                    receipts.onDonationPaid(UUID.fromString(String.valueOf(d.get("ngoId"))), donationId, uuid(d.get("donorId")),
                            uuid(d.get("donorUserId")), new BigDecimal(String.valueOf(d.get("amount"))));
                    created++;
                }
            }
            jdbc.update("insert into backfill_state(name) values ('DONATIONS') on conflict do nothing");
            log.info("receipt backfill: {} receipts created for earlier donations", created);
        } catch (RuntimeException e) {
            log.warn("receipt backfill postponed: {}", e.toString());
        }
    }

    private static UUID uuid(Object o) { return o == null || o.toString().isBlank() ? null : UUID.fromString(o.toString()); }
}
