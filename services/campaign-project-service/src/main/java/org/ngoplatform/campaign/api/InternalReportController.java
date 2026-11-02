package org.ngoplatform.campaign.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.ngoplatform.campaign.app.AccountsService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Campaign and accounts figures for reporting-service (internal token only). Campaign figures cover each campaign's
 * whole life (budget vs actual is not a period figure); "spent" counts APPROVED expenses only.
 */
@RestController
@RequestMapping("/internal/v1/ngos/{ngoId}/reports")
public class InternalReportController {
    private final JdbcTemplate jdbc;
    private final AccountsService accounts;

    public InternalReportController(JdbcTemplate jdbc, AccountsService accounts) { this.jdbc = jdbc; this.accounts = accounts; }

    public record CampaignRow(String id, String title, String status, BigDecimal goal, BigDecimal raised, long donations,
                              BigDecimal planned, BigDecimal spent) {}
    public record Pending(long expenses, BigDecimal expenseAmount, long campaigns) {}
    public record Claim(String id, String voucherNo, String campaignTitle, String category, BigDecimal amount, Instant submittedAt) {}
    public record Operations(long activeCampaigns, long activeProjects, List<CampaignRow> campaigns,
                             List<AccountsService.StaffBalance> staffCash, Pending pending) {}
    public record Mine(List<CampaignRow> campaigns, BigDecimal cashBalance, List<Claim> pendingClaims) {}

    private static final String CAMPAIGN_SQL = "select c.id::text, c.title, c.status, c.goal_amount, c.raised_amount, c.donation_count, "
            + "coalesce((select sum(planned) from campaign_budget_lines b where b.campaign_id = c.id), 0), "
            + "coalesce((select sum(amount) from campaign_expenses e where e.campaign_id = c.id and e.status = 'APPROVED'), 0) "
            + "from campaigns c where c.ngo_id = ? ";

    /** NGO-wide: live and closed campaigns, staff cash, items waiting for approval. */
    @GetMapping("/operations")
    public Operations operations(@PathVariable UUID ngoId) {
        long active = count("select count(*) from campaigns where ngo_id = ? and status = 'PUBLISHED'", ngoId);
        long projects = count("select count(*) from projects where ngo_id = ? and status = 'ACTIVE'", ngoId);
        List<CampaignRow> campaigns = campaigns(CAMPAIGN_SQL + "and c.status in ('PUBLISHED','CLOSED') order by c.status desc, c.raised_amount desc", ngoId);
        var e = jdbc.queryForMap("select count(*) as n, coalesce(sum(amount), 0) as amt from campaign_expenses where ngo_id = ? and status = 'SUBMITTED'", ngoId);
        long pendingCampaigns = count("select count(*) from campaigns where ngo_id = ? and status = 'PENDING_APPROVAL'", ngoId);
        return new Operations(active, projects, campaigns, accounts.balances(ngoId, null),
                new Pending(((Number) e.get("n")).longValue(), (BigDecimal) e.get("amt"), pendingCampaigns));
    }

    /**
     * One member: campaigns they created (not archived), their cash balance (positive: the NGO owes them; negative:
     * they hold NGO cash) and their expense claims waiting for approval.
     */
    @GetMapping("/members/{userId}")
    public Mine member(@PathVariable UUID ngoId, @PathVariable UUID userId) {
        List<CampaignRow> mine = campaigns(CAMPAIGN_SQL + "and c.created_by = ? and c.status <> 'ARCHIVED' order by c.created_at desc", ngoId, userId);
        List<Claim> claims = jdbc.query("select e.id::text, e.voucher_no, c.title, e.category, e.amount, e.submitted_at from campaign_expenses e "
                        + "left join campaigns c on c.id = e.campaign_id where e.ngo_id = ? and e.submitted_by = ? and e.status = 'SUBMITTED' "
                        + "order by e.submitted_at",
                (rs, i) -> new Claim(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5),
                        rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant()),
                ngoId, userId);
        return new Mine(mine, accounts.balance(ngoId, userId), claims);
    }

    private List<CampaignRow> campaigns(String sql, Object... args) {
        return jdbc.query(sql, (rs, i) -> new CampaignRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4),
                rs.getBigDecimal(5), rs.getLong(6), rs.getBigDecimal(7), rs.getBigDecimal(8)), args);
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

}
