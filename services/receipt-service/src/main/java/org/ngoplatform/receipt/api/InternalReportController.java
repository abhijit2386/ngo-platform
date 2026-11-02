package org.ngoplatform.receipt.api;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Receipt numbers for reporting-service (80G register, Form 10BD data). Internal token only. */
@RestController
@RequestMapping("/internal/v1/ngos/{ngoId}/reports")
public class InternalReportController {
    private final JdbcTemplate jdbc;

    public InternalReportController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** donationId -> number of its current receipt (the newest that is not voided or replaced); max 5,000 ids. */
    @PostMapping("/receipt-numbers")
    public Map<String, String> numbers(@PathVariable UUID ngoId, @RequestBody List<UUID> donationIds) {
        Map<String, String> out = new HashMap<>();
        if (donationIds == null || donationIds.isEmpty()) return out;
        List<UUID> ids = donationIds.size() > 5000 ? donationIds.subList(0, 5000) : donationIds;
        jdbc.query("select distinct on (donation_id) donation_id::text, receipt_number from donation_receipts "
                        + "where ngo_id = ? and donation_id = any(?) and receipt_number is not null "
                        + "and status not in ('VOIDED','REISSUED') order by donation_id, created_at desc",
                rs -> { out.put(rs.getString(1), rs.getString(2)); },
                ngoId, ids.toArray(new UUID[0]));
        return out;
    }
}
