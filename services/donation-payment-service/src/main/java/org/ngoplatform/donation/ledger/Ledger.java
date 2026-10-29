package org.ngoplatform.donation.ledger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operational donation ledger (spec §14): balanced double-entry, immutable once posted (DB triggers), idempotent per
 * (ngo, entry type, reference). Corrections are reversing entries, never edits.
 */
@Component
public class Ledger {
    public static final String GATEWAY_RECEIVABLE = "GATEWAY_RECEIVABLE";
    public static final String BANK = "BANK";
    public static final String CASH = "CASH";
    public static final String DONATION_INCOME = "DONATION_INCOME";
    public static final String GATEWAY_FEES = "GATEWAY_FEES";
    public static final String GST_ON_FEES = "GST_ON_FEES";
    public static final String REFUNDS_PAYABLE = "REFUNDS_PAYABLE";

    private static final Map<String, String[]> CHART = new LinkedHashMap<>();
    static {
        CHART.put(GATEWAY_RECEIVABLE, new String[]{"Payment gateway receivable", "ASSET"});
        CHART.put(BANK, new String[]{"Bank (settlement account)", "ASSET"});
        CHART.put(CASH, new String[]{"Cash and offline receipts", "ASSET"});
        CHART.put(DONATION_INCOME, new String[]{"Donation income", "INCOME"});
        CHART.put(GATEWAY_FEES, new String[]{"Gateway fees", "EXPENSE"});
        CHART.put(GST_ON_FEES, new String[]{"GST on gateway fees", "EXPENSE"});
        CHART.put(REFUNDS_PAYABLE, new String[]{"Refunds payable", "LIABILITY"});
    }

    public record Line(String account, BigDecimal debit, BigDecimal credit) {
        public static Line dr(String a, BigDecimal v) { return new Line(a, v, BigDecimal.ZERO); }
        public static Line cr(String a, BigDecimal v) { return new Line(a, BigDecimal.ZERO, v); }
    }

    private final JdbcTemplate jdbc;

    public Ledger(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private UUID account(UUID ngoId, String code) {
        String[] def = CHART.get(code);
        jdbc.update("insert into ledger_accounts(id, ngo_id, code, name, kind) values (?,?,?,?,?) on conflict (ngo_id, code) do nothing",
                UUID.randomUUID(), ngoId, code, def[0], def[1]);
        return jdbc.queryForObject("select id from ledger_accounts where ngo_id = ? and code = ?", UUID.class, ngoId, code);
    }

    /** Posts a balanced entry once. Returns false if this (type, reference) was already posted. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean post(UUID ngoId, String type, String reference, String memo, List<Line> lines) {
        BigDecimal dr = BigDecimal.ZERO, cr = BigDecimal.ZERO;
        List<Line> nonZero = new ArrayList<>();
        for (Line l : lines) {
            if (l.debit().signum() < 0 || l.credit().signum() < 0) throw new IllegalArgumentException("Negative ledger amount");
            if (l.debit().signum() == 0 && l.credit().signum() == 0) continue;
            dr = dr.add(l.debit()); cr = cr.add(l.credit()); nonZero.add(l);
        }
        if (dr.compareTo(cr) != 0) throw new IllegalStateException("Unbalanced journal " + type + " " + reference);
        UUID entry = UUID.randomUUID();
        int inserted = jdbc.update("insert into journal_entries(id, ngo_id, entry_type, reference_id, memo) values (?,?,?,?,?) "
                + "on conflict (ngo_id, entry_type, reference_id) do nothing", entry, ngoId, type, reference, memo);
        if (inserted == 0) return false;
        for (Line l : nonZero) {
            jdbc.update("insert into journal_lines(id, entry_id, account_id, debit, credit) values (?,?,?,?,?)",
                    UUID.randomUUID(), entry, account(ngoId, l.account()), l.debit(), l.credit());
        }
        return true;
    }

    public List<Map<String, Object>> balances(UUID ngoId) {
        return jdbc.queryForList("select a.code, a.name, a.kind, coalesce(sum(l.debit),0) as debit, coalesce(sum(l.credit),0) as credit "
                + "from ledger_accounts a left join journal_lines l on l.account_id = a.id where a.ngo_id = ? "
                + "group by a.code, a.name, a.kind order by a.code", ngoId);
    }

    public List<Map<String, Object>> entries(UUID ngoId, int limit) {
        return jdbc.queryForList("select e.id, e.entry_type, e.reference_id, e.memo, e.posted_at, "
                + "json_agg(json_build_object('account', a.code, 'debit', l.debit, 'credit', l.credit)) as lines "
                + "from journal_entries e join journal_lines l on l.entry_id = e.id join ledger_accounts a on a.id = l.account_id "
                + "where e.ngo_id = ? group by e.id order by e.posted_at desc limit ?", ngoId, Math.min(limit, 500));
    }
}
