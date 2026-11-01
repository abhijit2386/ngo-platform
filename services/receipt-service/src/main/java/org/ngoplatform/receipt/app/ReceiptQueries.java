package org.ngoplatform.receipt.app;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.ngoplatform.commons.web.PageResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Read side for the NGO receipt register and the donor's receipts. */
@Component
public class ReceiptQueries {
    public record Item(UUID id, UUID donationId, String receiptNumber, String donorName, String ngoName, BigDecimal amount,
                       LocalDate donationDate, String fiscalYear, String channel, String campaignTitle, String status, boolean tax80g,
                       boolean donorAccount, int attempts, String lastError, UUID reissueOf, String voidReason, Instant issuedAt,
                       Instant deliveredAt, Instant createdAt, String verifyCode, UUID templateId, Integer templateVersion) {}

    /** Status groups shown in the app. */
    public static List<String> statuses(String group) {
        if (group == null || group.isBlank()) return List.of();
        return switch (group) {
            case "ISSUED" -> List.of("GENERATED", "DELIVERY_PENDING", "DELIVERED");
            case "PENDING" -> List.of("PENDING", "GENERATING");
            case "FAILED" -> List.of("GENERATION_FAILED");
            case "CANCELLED" -> List.of("VOIDED", "REISSUED");
            default -> List.of(group);
        };
    }

    private final JdbcTemplate jdbc;

    public ReceiptQueries(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    static Item item(ResultSet rs, int i) throws SQLException {
        return new Item(rs.getObject("id", UUID.class), rs.getObject("donation_id", UUID.class), rs.getString("receipt_number"),
                rs.getString("donor_name"), rs.getString("ngo_name"), rs.getBigDecimal("amount"),
                rs.getObject("donation_date", LocalDate.class), rs.getString("fiscal_year"), rs.getString("channel"),
                rs.getString("campaign_title"), rs.getString("status"), rs.getBoolean("tax_80g"), rs.getObject("donor_user_id") != null,
                rs.getInt("attempts"), rs.getString("last_error"), rs.getObject("reissue_of", UUID.class), rs.getString("void_reason"),
                ts(rs, "issued_at"), ts(rs, "delivered_at"), ts(rs, "created_at"), rs.getString("verify_code"),
                rs.getObject("template_id", UUID.class), (Integer) rs.getObject("template_version"));
    }

    private static Instant ts(ResultSet rs, String c) throws SQLException {
        var t = rs.getTimestamp(c);
        return t == null ? null : t.toInstant();
    }

    /** NGO register: search by receipt number or donor name, status group, financial year; newest first. */
    public PageResponse<Item> search(UUID ngoId, String q, String statusGroup, String fy, int page, int size) {
        StringBuilder where = new StringBuilder(" where ngo_id = ?");
        List<Object> args = new ArrayList<>(List.of(ngoId));
        if (q != null && !q.isBlank()) {
            where.append(" and (receipt_number ilike ? or donor_name ilike ?)");
            String like = "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            args.add(like);
            args.add(like);
        }
        List<String> st = statuses(statusGroup);
        if (!st.isEmpty()) {
            where.append(" and status in (").append(String.join(",", st.stream().map(x -> "?").toList())).append(")");
            args.addAll(st);
        }
        if (fy != null && fy.matches("\\d{4}-\\d{2}")) { where.append(" and fiscal_year = ?"); args.add(fy); }
        Long total = jdbc.queryForObject("select count(*) from donation_receipts" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(page * size);
        List<Item> items = jdbc.query("select * from donation_receipts" + where
                + " order by coalesce(issued_at, created_at) desc, receipt_number desc nulls first limit ? offset ?", ReceiptQueries::item, pageArgs.toArray());
        long t = total == null ? 0 : total;
        return new PageResponse<>(items, page, size, t, (int) ((t + size - 1) / size));
    }

    public Map<String, Object> summary(UUID ngoId, String fy) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fiscalYear", fy);
        jdbc.query("""
                select count(*) filter (where status in ('GENERATED','DELIVERY_PENDING','DELIVERED')) as issued,
                       coalesce(sum(amount) filter (where status in ('GENERATED','DELIVERY_PENDING','DELIVERED')), 0) as issued_amount,
                       count(*) filter (where status in ('GENERATED','DELIVERY_PENDING','DELIVERED') and tax_80g) as issued_80g,
                       count(*) filter (where status in ('VOIDED','REISSUED')) as cancelled
                from donation_receipts where ngo_id = ? and fiscal_year = ?""", rs -> {
            m.put("issued", rs.getLong("issued"));
            m.put("issuedAmount", rs.getBigDecimal("issued_amount"));
            m.put("issued80g", rs.getLong("issued_80g"));
            m.put("cancelled", rs.getLong("cancelled"));
        }, ngoId, fy);
        m.put("pending", jdbc.queryForObject("select count(*) from donation_receipts where ngo_id = ? and status in ('PENDING','GENERATING')",
                Long.class, ngoId));
        m.put("failed", jdbc.queryForObject("select count(*) from donation_receipts where ngo_id = ? and status = 'GENERATION_FAILED'",
                Long.class, ngoId));
        m.put("lastNumber", jdbc.query("select receipt_number from donation_receipts where ngo_id = ? and fiscal_year = ? "
                + "and receipt_number is not null order by receipt_number desc limit 1", (rs, i) -> rs.getString(1), ngoId, fy)
                .stream().findFirst().orElse(null));
        m.put("fiscalYears", jdbc.queryForList("select distinct fiscal_year from donation_receipts where ngo_id = ? and fiscal_year is not null "
                + "order by fiscal_year desc", String.class, ngoId));
        return m;
    }

    public List<Map<String, Object>> history(UUID receiptId) {
        return jdbc.queryForList("select event, detail, created_at as \"createdAt\" from receipt_events where receipt_id = ? order by created_at, id",
                receiptId);
    }

    /** The receipt that replaced this one (reissue), if any. */
    public Item replacement(UUID receiptId) {
        return jdbc.query("select * from donation_receipts where reissue_of = ?", ReceiptQueries::item, receiptId).stream().findFirst().orElse(null);
    }

    public Item byId(UUID id) {
        return jdbc.query("select * from donation_receipts where id = ?", ReceiptQueries::item, id).stream().findFirst().orElse(null);
    }

    /** Donor's own receipts (all NGOs), newest first. */
    public PageResponse<Item> mine(UUID userId, int page, int size) {
        Long total = jdbc.queryForObject("select count(*) from donation_receipts where donor_user_id = ? and pdf_key is not null",
                Long.class, userId);
        List<Item> items = jdbc.query("select * from donation_receipts where donor_user_id = ? and pdf_key is not null "
                + "order by coalesce(issued_at, created_at) desc limit ? offset ?", ReceiptQueries::item, userId, size, page * size);
        long t = total == null ? 0 : total;
        return new PageResponse<>(items, page, size, t, (int) ((t + size - 1) / size));
    }
}
