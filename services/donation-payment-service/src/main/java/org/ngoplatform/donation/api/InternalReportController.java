package org.ngoplatform.donation.api;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Donation figures for reporting-service (internal token only). Counts paid donations (PAID, PARTIALLY_REFUNDED),
 * net of refunds, never voided ones; the period is [from, to) as instants chosen by the caller (India time days).
 * A donor is identified by donor id, else e-mail, else name (offline donors may have neither id nor e-mail).
 */
@RestController
@RequestMapping("/internal/v1/ngos/{ngoId}/reports")
public class InternalReportController {
    private static final String PAID = "status in ('PAID','PARTIALLY_REFUNDED') and not voided";
    private static final String NET = "(amount - refunded_amount)";
    private static final String DONOR_KEY = "coalesce(donor_id::text, lower(donor_email), lower(trim(donor_name)))";

    private final JdbcTemplate jdbc;

    public InternalReportController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Row(String key, String label, BigDecimal amount, long count) {}
    public record Summary(BigDecimal total, long count, long donors, Long newDonors, List<Row> byMonth,
                          List<Row> byChannel, List<Row> byCampaign, List<Row> topDonors) {}
    public record TaxRow(String donationId, Instant paidAt, String donorName, String pan, String address,
                         String channel, BigDecimal amount, String campaignTitle) {}

    /** Summary for the NGO, or only the donations one member recorded ({@code recordedBy}: offline collections). */
    @GetMapping("/donations")
    public Summary donations(@PathVariable UUID ngoId,
                             @RequestParam("from") String fromParam,
                             @RequestParam("to") String toParam,
                             @RequestParam(required = false) UUID recordedBy) {
        Instant from = Instant.parse(fromParam), to = Instant.parse(toParam);
        StringBuilder where = new StringBuilder(" from donations where ngo_id = ? and " + PAID + " and paid_at >= ? and paid_at < ?");
        List<Object> args = new ArrayList<>(List.of(ngoId, Timestamp.from(from), Timestamp.from(to)));
        if (recordedBy != null) { where.append(" and recorded_by = ?"); args.add(recordedBy); }
        Object[] a = args.toArray();

        Map<String, Object> t = jdbc.queryForMap("select coalesce(sum(" + NET + "), 0) as total, count(*) as cnt, "
                + "count(distinct " + DONOR_KEY + ") as donors" + where, a);
        List<Row> byMonth = rows("select to_char(paid_at at time zone 'Asia/Kolkata', 'YYYY-MM') as k, null as l, sum(" + NET
                + ") as amt, count(*) as cnt" + where + " group by 1 order by 1", a);
        List<Row> byChannel = rows("select channel as k, null as l, sum(" + NET + ") as amt, count(*) as cnt" + where
                + " group by 1 order by 3 desc", a);
        List<Row> byCampaign = rows("select campaign_id::text as k, max(campaign_title) as l, sum(" + NET + ") as amt, count(*) as cnt"
                + where + " group by 1 order by 3 desc", a);
        List<Row> top = rows("select " + DONOR_KEY + " as k, max(donor_name) as l, sum(" + NET + ") as amt, count(*) as cnt" + where
                + " group by 1 order by 3 desc, 2 limit 10", a);
        Long newDonors = recordedBy != null ? null : jdbc.queryForObject("select count(*) from (select " + DONOR_KEY
                + " as k, min(paid_at) as first from donations where ngo_id = ? and " + PAID + " group by 1) f "
                + "where first >= ? and first < ?", Long.class, ngoId, Timestamp.from(from), Timestamp.from(to));
        return new Summary((BigDecimal) t.get("total"), ((Number) t.get("cnt")).longValue(), ((Number) t.get("donors")).longValue(),
                newDonors, byMonth, byChannel, byCampaign, top);
    }

    /** Paid donations for the 80G register / Form 10BD data, oldest first; receipt-service says which got an 80G receipt. */
    @GetMapping("/80g")
    public List<TaxRow> taxRows(@PathVariable UUID ngoId,
                                @RequestParam("from") String fromParam,
                                @RequestParam("to") String toParam) {
        Instant from = Instant.parse(fromParam), to = Instant.parse(toParam);
        return jdbc.query("select id::text, paid_at, donor_name, donor_pan, donor_address, channel, " + NET + " as amt, campaign_title "
                        + "from donations where ngo_id = ? and " + PAID + " and paid_at >= ? and paid_at < ? "
                        + "order by paid_at, id",
                (rs, i) -> new TaxRow(rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getBigDecimal(7), rs.getString(8)),
                ngoId, Timestamp.from(from), Timestamp.from(to));
    }

    private List<Row> rows(String sql, Object[] args) {
        return jdbc.query(sql, (rs, i) -> new Row(rs.getString("k"), rs.getString("l"), rs.getBigDecimal("amt"), rs.getLong("cnt")), args);
    }
}
