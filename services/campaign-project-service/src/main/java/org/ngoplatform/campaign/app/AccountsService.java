package org.ngoplatform.campaign.app;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.ngoplatform.campaign.domain.Campaign;
import org.ngoplatform.campaign.domain.Expense;
import org.ngoplatform.campaign.domain.Repos;
import org.ngoplatform.campaign.domain.Vendor;
import org.ngoplatform.commons.audit.AuditLog;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.events.EventPublisher;
import org.ngoplatform.commons.events.EventTypes;
import org.ngoplatform.commons.money.Money;
import org.ngoplatform.commons.security.Membership;
import org.ngoplatform.commons.storage.FileInspector;
import org.ngoplatform.commons.storage.ObjectStorage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Campaign accounts: budget, expenses with bills and administrator approval, vendor list and the staff cash ledger
 * (advances, staff-paid expenses, returns, reimbursements). The account sheet is built by {@link AccountSheet}.
 */
@Service
public class AccountsService {
    public record ExpenseInput(UUID budgetLineId, String category, String description, BigDecimal amount, LocalDate date,
                               UUID vendorId, String vendorName, String billNumber, String noBillReason, String paymentMode,
                               String paidBy, UUID paidByUser) {}
    public record BudgetLineInput(UUID id, String category, BigDecimal planned) {}
    public record BudgetLine(UUID id, String category, BigDecimal planned, int position) {}
    public record Attachment(UUID id, UUID expenseId, String key, String contentType, long size, String fileName,
                             UUID uploadedBy, Instant createdAt) {}
    public record Upload(byte[] bytes, String fileName) {}
    public record ImportResult(int created, int skipped, List<Map<String, Object>> errors) {}

    private final Repos.Campaigns campaigns;
    private final Repos.Expenses expenses;
    private final Repos.Vendors vendors;
    private final CampaignService campaignService;
    private final TeamDirectory team;
    private final ObjectStorage storage;
    private final JdbcTemplate jdbc;
    private final EventPublisher events;
    private final AuditLog audit;

    public AccountsService(Repos.Campaigns campaigns, Repos.Expenses expenses, Repos.Vendors vendors, CampaignService campaignService,
                           TeamDirectory team, ObjectStorage storage, JdbcTemplate jdbc, EventPublisher events, AuditLog audit) {
        this.campaigns = campaigns; this.expenses = expenses; this.vendors = vendors; this.campaignService = campaignService;
        this.team = team; this.storage = storage; this.jdbc = jdbc; this.events = events; this.audit = audit;
    }

    // ---------------- budget

    public List<BudgetLine> budget(UUID campaignId) {
        return jdbc.query("select id, category, planned, position from campaign_budget_lines where campaign_id = ? order by position, created_at",
                (rs, i) -> new BudgetLine(rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3), rs.getInt(4)), campaignId);
    }

    /** Replaces the budget with the given lines (existing ids are kept; removed lines must not have expenses). */
    @Transactional
    public List<BudgetLine> setBudget(UUID ngoId, Membership m, UUID campaignId, List<BudgetLineInput> lines) {
        Campaign c = campaignService.load(ngoId, campaignId);
        AccountRules.checkBudgetEdit(c.getStatus(), m.isAdmin());
        if (lines.size() > AccountRules.MAX_BUDGET_LINES) throw ApiException.badRequest("TOO_MANY_LINES", "At most " + AccountRules.MAX_BUDGET_LINES + " budget lines");
        Map<UUID, BudgetLine> existing = new LinkedHashMap<>();
        budget(campaignId).forEach(b -> existing.put(b.id(), b));
        Set<String> seen = new HashSet<>();
        List<UUID> keep = new ArrayList<>();
        for (BudgetLineInput in : lines) {
            String cat = AccountRules.category(in.category());
            if (!seen.add(cat.toLowerCase(Locale.ROOT))) throw ApiException.badRequest("DUPLICATE_CATEGORY", "Category \"" + cat + "\" appears twice");
            if (in.planned() == null || in.planned().signum() < 0 || in.planned().stripTrailingZeros().scale() > 2
                    || in.planned().compareTo(AccountRules.MAX_AMOUNT.multiply(BigDecimal.TEN)) > 0) {
                throw ApiException.badRequest("INVALID_AMOUNT", "Planned amounts must be ₹0 or more with at most 2 decimals");
            }
            if (in.id() != null) {
                if (!existing.containsKey(in.id())) throw ApiException.notFound("Budget line");
                keep.add(in.id());
            }
        }
        for (UUID old : existing.keySet()) {
            if (!keep.contains(old)) {
                if (expenses.existsByBudgetLineId(old)) {
                    throw ApiException.rule("BUDGET_LINE_IN_USE", "\"" + existing.get(old).category() + "\" has expenses and cannot be removed");
                }
                jdbc.update("delete from campaign_budget_lines where id = ?", old);
            }
        }
        // Two passes so a rename can swap names without tripping the unique index.
        for (UUID id : keep) jdbc.update("update campaign_budget_lines set category = ? where id = ?", "~" + id, id);
        int pos = 0;
        for (BudgetLineInput in : lines) {
            String cat = AccountRules.category(in.category());
            BigDecimal planned = Money.round(in.planned());
            if (in.id() != null) {
                jdbc.update("update campaign_budget_lines set category = ?, planned = ?, position = ? where id = ?", cat, planned, pos, in.id());
            } else {
                jdbc.update("insert into campaign_budget_lines(id, campaign_id, ngo_id, category, planned, position) values (?,?,?,?,?,?)",
                        UUID.randomUUID(), campaignId, ngoId, cat, planned, pos);
            }
            pos++;
        }
        audit.record(ngoId, m.userId(), "CAMPAIGN_BUDGET_SET", "CAMPAIGN", campaignId, Map.of("lines", lines.size()));
        return budget(campaignId);
    }

    // ---------------- expenses

    public Expense load(UUID ngoId, UUID campaignId, UUID id) {
        Expense e = expenses.findByIdAndNgoId(id, ngoId).orElseThrow(() -> ApiException.notFound("Expense"));
        if (!e.getCampaignId().equals(campaignId)) throw ApiException.notFound("Expense");
        return e;
    }

    public List<Attachment> attachments(UUID expenseId) {
        return jdbc.query("select id, expense_id, object_key, content_type, size_bytes, file_name, uploaded_by, created_at "
                        + "from expense_attachments where expense_id = ? order by created_at",
                (rs, i) -> new Attachment(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getLong(5), rs.getString(6), rs.getObject(7, UUID.class), rs.getTimestamp(8).toInstant()), expenseId);
    }

    public Map<UUID, Integer> attachmentCounts(UUID campaignId) {
        Map<UUID, Integer> out = new LinkedHashMap<>();
        jdbc.query("select a.expense_id, count(*) from expense_attachments a join campaign_expenses e on e.id = a.expense_id "
                + "where e.campaign_id = ? group by a.expense_id", rs -> { out.put(rs.getObject(1, UUID.class), rs.getInt(2)); }, campaignId);
        return out;
    }

    @Transactional
    public Expense create(UUID ngoId, Membership m, UUID campaignId, ExpenseInput in, List<Upload> files) {
        Campaign c = campaignService.load(ngoId, campaignId);
        AccountRules.checkCampaignOpenForExpenses(c.getStatus());
        if (files.size() > AccountRules.MAX_ATTACHMENTS) throw tooManyFiles();
        Expense e = new Expense(ngoId, campaignId, m.userId());
        apply(e, c, m, in);
        AccountRules.checkEvidence(files.size(), e.getNoBillReason());
        expenses.saveAndFlush(e);
        for (Upload f : files) store(e, m.userId(), f);
        events.publish(EventTypes.CAMPAIGN_EXPENSE_SUBMITTED, ngoId, e.getId(), payload(c, e, Map.of("submittedBy", m.userId().toString())));
        audit.record(ngoId, m.userId(), "EXPENSE_SUBMITTED", "EXPENSE", e.getId(), Map.of("amount", e.getAmount().toPlainString()));
        return e;
    }

    @Transactional
    public Expense update(UUID ngoId, Membership m, UUID campaignId, UUID id, ExpenseInput in, long expected) {
        Campaign c = campaignService.load(ngoId, campaignId);
        AccountRules.checkCampaignOpenForExpenses(c.getStatus());
        Expense e = load(ngoId, campaignId, id);
        if (e.getVersion() != expected) throw ApiException.conflict("STALE_VERSION", "This expense was changed by someone else. Reload.");
        AccountRules.checkEdit(e.getStatus(), e.getSubmittedBy().equals(m.userId()), m.isAdmin());
        boolean wasRejected = e.getStatus() == AccountRules.Status.REJECTED;
        apply(e, c, m, in);
        AccountRules.checkEvidence(attachments(id).size(), e.getNoBillReason());
        e.resubmit();
        if (wasRejected) {
            events.publish(EventTypes.CAMPAIGN_EXPENSE_SUBMITTED, ngoId, e.getId(), payload(c, e, Map.of("submittedBy", e.getSubmittedBy().toString())));
        }
        audit.record(ngoId, m.userId(), "EXPENSE_UPDATED", "EXPENSE", id, Map.of("amount", e.getAmount().toPlainString()));
        return e;
    }

    /** The submitter takes back a pending or rejected expense (deleted with its bills). */
    @Transactional
    public void withdraw(UUID ngoId, Membership m, UUID campaignId, UUID id) {
        Expense e = load(ngoId, campaignId, id);
        AccountRules.checkWithdraw(e.getStatus(), e.getSubmittedBy().equals(m.userId()));
        List<Attachment> atts = attachments(id);
        jdbc.update("delete from expense_attachments where expense_id = ?", id);
        expenses.delete(e);
        expenses.flush();
        atts.forEach(a -> deleteQuietly(a.key()));
        audit.record(ngoId, m.userId(), "EXPENSE_WITHDRAWN", "EXPENSE", id, Map.of());
    }

    @Transactional
    public Expense approve(UUID ngoId, Membership m, UUID campaignId, UUID id, long expected) {
        Campaign c = campaignService.load(ngoId, campaignId);
        if (c.getStatus() == Campaign.Status.ARCHIVED) throw ApiException.rule("CAMPAIGN_ARCHIVED", "This campaign's accounts are closed (archived)");
        Expense e = load(ngoId, campaignId, id);
        boolean submitter = e.getSubmittedBy().equals(m.userId());
        boolean self = AccountRules.checkApprove(e.getStatus(), m.isAdmin(), submitter, submitter ? team.activeAdmins(ngoId) : 2);
        if (e.getVersion() != expected) throw ApiException.conflict("STALE_VERSION", "The expense was edited after you opened it. Review the latest version.");
        AccountRules.checkEvidence(attachments(id).size(), e.getNoBillReason());
        String fy = AccountRules.financialYear(e.getExpenseDate());
        Integer n = jdbc.queryForObject("insert into expense_voucher_counters(ngo_id, fy, last_no) values (?,?,1) "
                + "on conflict (ngo_id, fy) do update set last_no = expense_voucher_counters.last_no + 1 returning last_no", Integer.class, ngoId, fy);
        e.approve(m.userId(), AccountRules.voucherNo(fy, n), self);
        expenses.flush();
        if ("STAFF".equals(e.getPaidBy())) {
            ledger(ngoId, e.getPaidByUser(), campaignId, "EXPENSE", e.getAmount(), e.getId(), e.getPaymentMode(), e.getExpenseDate(),
                    e.getVoucherNo() + " " + e.getDescription(), m.userId());
        }
        events.publish(EventTypes.CAMPAIGN_EXPENSE_APPROVED, ngoId, e.getId(), payload(c, e, Map.of(
                "submittedBy", e.getSubmittedBy().toString(), "decidedBy", m.userId().toString(), "voucherNo", e.getVoucherNo())));
        audit.record(ngoId, m.userId(), "EXPENSE_APPROVED", "EXPENSE", id, Map.of("voucherNo", e.getVoucherNo(), "self", self));
        return e;
    }

    @Transactional
    public Expense reject(UUID ngoId, Membership m, UUID campaignId, UUID id, String reason) {
        Campaign c = campaignService.load(ngoId, campaignId);
        Expense e = load(ngoId, campaignId, id);
        AccountRules.checkReject(e.getStatus(), m.isAdmin(), reason);
        e.reject(m.userId(), reason.strip());
        events.publish(EventTypes.CAMPAIGN_EXPENSE_REJECTED, ngoId, e.getId(), payload(c, e, Map.of(
                "submittedBy", e.getSubmittedBy().toString(), "decidedBy", m.userId().toString(), "reason", e.getRejectionReason())));
        audit.record(ngoId, m.userId(), "EXPENSE_REJECTED", "EXPENSE", id, Map.of());
        return e;
    }

    /** Cancels an approved expense (entered twice, wrong amount...). The voucher number stays used; staff ledger is reversed. */
    @Transactional
    public Expense voidExpense(UUID ngoId, Membership m, UUID campaignId, UUID id, String reason) {
        Campaign c = campaignService.load(ngoId, campaignId);
        if (c.getStatus() == Campaign.Status.ARCHIVED) throw ApiException.rule("CAMPAIGN_ARCHIVED", "This campaign's accounts are closed (archived)");
        Expense e = load(ngoId, campaignId, id);
        AccountRules.checkVoid(e.getStatus(), m.isAdmin(), reason);
        e.voidIt(m.userId(), reason.strip());
        expenses.flush();
        if ("STAFF".equals(e.getPaidBy())) {
            ledger(ngoId, e.getPaidByUser(), campaignId, "EXPENSE_VOID", e.getAmount().negate(), e.getId(), null, campaignService.today(),
                    "Void " + e.getVoucherNo(), m.userId());
        }
        audit.record(ngoId, m.userId(), "EXPENSE_VOIDED", "EXPENSE", id, Map.of("voucherNo", e.getVoucherNo()));
        return e;
    }

    @Transactional
    public Attachment addAttachment(UUID ngoId, Membership m, UUID campaignId, UUID id, Upload f) {
        Expense e = load(ngoId, campaignId, id);
        AccountRules.checkEdit(e.getStatus(), e.getSubmittedBy().equals(m.userId()), m.isAdmin());
        if (attachments(id).size() >= AccountRules.MAX_ATTACHMENTS) throw tooManyFiles();
        Attachment a = store(e, m.userId(), f);
        audit.record(ngoId, m.userId(), "EXPENSE_BILL_ADDED", "EXPENSE", id, Map.of("attachmentId", a.id().toString()));
        return a;
    }

    @Transactional
    public void removeAttachment(UUID ngoId, Membership m, UUID campaignId, UUID id, UUID attachmentId) {
        Expense e = load(ngoId, campaignId, id);
        AccountRules.checkEdit(e.getStatus(), e.getSubmittedBy().equals(m.userId()), m.isAdmin());
        List<Attachment> atts = attachments(id);
        Attachment a = atts.stream().filter(x -> x.id().equals(attachmentId)).findFirst().orElseThrow(() -> ApiException.notFound("Bill"));
        AccountRules.checkEvidence(atts.size() - 1, e.getNoBillReason());
        jdbc.update("delete from expense_attachments where id = ?", attachmentId);
        deleteQuietly(a.key());
        audit.record(ngoId, m.userId(), "EXPENSE_BILL_REMOVED", "EXPENSE", id, Map.of("attachmentId", attachmentId.toString()));
    }

    public Attachment attachment(UUID expenseId, UUID attachmentId) {
        return attachments(expenseId).stream().filter(x -> x.id().equals(attachmentId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Bill"));
    }

    public byte[] read(String key) {
        try (InputStream in = storage.get(key)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + key, e);
        }
    }

    private void apply(Expense e, Campaign c, Membership m, ExpenseInput in) {
        AccountRules.validateExpense(in.amount(), in.date(), campaignService.today(), c.getStartDate(), in.paymentMode(), in.paidBy());
        String description = AccountRules.trim(in.description());
        if (description == null || description.length() < 3 || description.length() > 300) {
            throw ApiException.badRequest("INVALID_DESCRIPTION", "Describe the expense in 3-300 characters");
        }
        UUID lineId = null;
        String category;
        if (in.budgetLineId() != null) {
            BudgetLine line = budget(c.getId()).stream().filter(b -> b.id().equals(in.budgetLineId())).findFirst()
                    .orElseThrow(() -> ApiException.notFound("Budget line"));
            lineId = line.id();
            category = line.category();
        } else {
            category = in.category() == null || in.category().isBlank() ? AccountRules.OTHER : AccountRules.category(in.category());
        }
        UUID vendorId = null;
        String vendorName = AccountRules.trim(in.vendorName());
        if (in.vendorId() != null) {
            Vendor v = vendors.findByIdAndNgoId(in.vendorId(), c.getNgoId()).orElseThrow(() -> ApiException.notFound("Vendor"));
            if (!v.isActive() && !in.vendorId().equals(e.getVendorId())) throw ApiException.rule("VENDOR_INACTIVE", "This vendor is no longer active");
            vendorId = v.getId();
            vendorName = v.getName();
        }
        if (vendorName != null && vendorName.length() > 120) throw ApiException.badRequest("TEXT_TOO_LONG", "Paid-to name is too long");
        String bill = AccountRules.trim(in.billNumber());
        if (bill != null && bill.length() > 40) throw ApiException.badRequest("TEXT_TOO_LONG", "Bill number is too long");
        String noBill = AccountRules.trim(in.noBillReason());
        if (noBill != null && (noBill.length() < 5 || noBill.length() > 200)) {
            throw ApiException.badRequest("INVALID_NO_BILL_REASON", "Say in 5-200 characters why there is no bill");
        }
        UUID payer = null;
        if ("STAFF".equals(in.paidBy())) {
            payer = in.paidByUser() == null ? m.userId() : in.paidByUser();
            if (!m.isAdmin() && !payer.equals(m.userId())) {
                throw ApiException.forbidden("PAYER_NOT_SELF", "You can record only money you paid yourself");
            }
            if (!payer.equals(m.userId())) team.activeMember(c.getNgoId(), payer);
        }
        e.edit(lineId, category, description, Money.round(in.amount()), in.date(), vendorId, vendorName, bill, noBill,
                in.paymentMode(), in.paidBy(), payer);
    }

    private Attachment store(Expense e, UUID actor, Upload f) {
        var info = FileInspector.inspect(f.bytes(), 10L * 1024 * 1024, Set.of("image/jpeg", "image/png", "application/pdf"));
        byte[] data;
        String type;
        if ("application/pdf".equals(info.contentType())) {
            data = f.bytes();
            type = "application/pdf";
        } else {
            data = CoverImage.bill(f.bytes()); // re-encoded: no EXIF / GPS
            type = "image/jpeg";
        }
        UUID id = UUID.randomUUID();
        String key = "ngos/" + e.getNgoId() + "/campaigns/" + e.getCampaignId() + "/expenses/" + e.getId() + "/" + id
                + ("application/pdf".equals(type) ? ".pdf" : ".jpg");
        storage.put(key, data, type);
        String name = f.fileName() == null ? null : FileInspector.safeName(f.fileName());
        jdbc.update("insert into expense_attachments(id, expense_id, object_key, content_type, size_bytes, file_name, uploaded_by) values (?,?,?,?,?,?,?)",
                id, e.getId(), key, type, (long) data.length, name, actor);
        return new Attachment(id, e.getId(), key, type, data.length, name, actor, Instant.now());
    }

    private void deleteQuietly(String key) {
        try { storage.delete(key); } catch (RuntimeException ignored) { /* orphan files are harmless */ }
    }

    private static ApiException tooManyFiles() {
        return ApiException.rule("BILL_LIMIT", "At most " + AccountRules.MAX_ATTACHMENTS + " bills per expense");
    }

    private static Map<String, Object> payload(Campaign c, Expense e, Map<String, Object> extra) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("campaignId", c.getId().toString());
        p.put("campaignTitle", c.getTitle());
        p.put("amount", e.getAmount().toPlainString());
        p.put("description", e.getDescription());
        p.putAll(extra);
        return p;
    }

    public List<Expense> campaignExpenses(UUID campaignId) {
        return expenses.findByCampaignIdOrderByExpenseDateDescCreatedAtDesc(campaignId);
    }

    /** Pending expenses of the whole NGO (approval inbox), oldest first. */
    public List<Expense> pending(UUID ngoId) {
        return expenses.findByNgoIdAndStatusOrderBySubmittedAtAsc(ngoId, AccountRules.Status.SUBMITTED);
    }

    public long pendingCount(UUID campaignId) {
        return expenses.countByCampaignIdAndStatus(campaignId, AccountRules.Status.SUBMITTED);
    }

    // ---------------- staff money ledger

    public record MoneyEntry(UUID id, UUID userId, UUID campaignId, String kind, BigDecimal amount, UUID expenseId, String mode,
                             LocalDate date, String note, UUID recordedBy, Instant createdAt) {}

    /** Per staff member: advanced, spent (approved staff-paid expenses), returned, reimbursed and the balance. */
    public record StaffBalance(UUID userId, BigDecimal advanced, BigDecimal spent, BigDecimal returned, BigDecimal reimbursed,
                               BigDecimal balance) {}

    public BigDecimal balance(UUID ngoId, UUID userId) {
        BigDecimal b = jdbc.queryForObject("select coalesce(sum(amount), 0) from staff_money_entries where ngo_id = ? and user_id = ?",
                BigDecimal.class, ngoId, userId);
        return b == null ? BigDecimal.ZERO : b;
    }

    public List<StaffBalance> balances(UUID ngoId, UUID campaignId) {
        String where = campaignId == null ? "ngo_id = ?" : "ngo_id = ? and campaign_id = ?";
        Object[] args = campaignId == null ? new Object[]{ngoId} : new Object[]{ngoId, campaignId};
        return jdbc.query("select user_id, "
                + "coalesce(sum(case when kind = 'ADVANCE' then -amount end), 0), "
                + "coalesce(sum(case when kind in ('EXPENSE','EXPENSE_VOID') then amount end), 0), "
                + "coalesce(sum(case when kind = 'RETURN' then amount end), 0), "
                + "coalesce(sum(case when kind = 'REIMBURSEMENT' then -amount end), 0), "
                + "coalesce(sum(amount), 0) from staff_money_entries where " + where + " group by user_id order by user_id",
                (rs, i) -> new StaffBalance(rs.getObject(1, UUID.class), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6)), args);
    }

    public List<MoneyEntry> entries(UUID ngoId, UUID userId) {
        return jdbc.query("select id, user_id, campaign_id, kind, amount, expense_id, mode, entry_date, note, recorded_by, created_at "
                + "from staff_money_entries where ngo_id = ? and user_id = ? order by entry_date desc, created_at desc limit 200",
                (rs, i) -> new MoneyEntry(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getString(4), rs.getBigDecimal(5), rs.getObject(6, UUID.class), rs.getString(7),
                        rs.getObject(8, LocalDate.class), rs.getString(9), rs.getObject(10, UUID.class),
                        rs.getTimestamp(11).toInstant()), ngoId, userId);
    }

    /** Administrator records an advance given, cash returned or a reimbursement paid. */
    @Transactional
    public MoneyEntry recordMoney(UUID ngoId, Membership m, UUID userId, String kind, BigDecimal amount, String mode, LocalDate date,
                                  UUID campaignId, String note) {
        if (!m.isAdmin()) throw ApiException.forbidden("ADMIN_REQUIRED", "Only administrators record advances and repayments");
        team.activeMember(ngoId, userId);
        // Serialise entries per staff member so two admins cannot both repay the same balance.
        jdbc.query("select pg_advisory_xact_lock(hashtext(?))", rs -> { }, ngoId + ":" + userId);
        AccountRules.checkSettlement(kind, amount, balance(ngoId, userId));
        if (mode == null || !AccountRules.MODES.contains(mode)) throw ApiException.badRequest("INVALID_MODE", "Choose how the money was paid");
        LocalDate d = date == null ? campaignService.today() : date;
        if (d.isAfter(campaignService.today())) throw ApiException.badRequest("DATE_IN_FUTURE", "The date cannot be in the future");
        if (campaignId != null) {
            Campaign c = campaignService.load(ngoId, campaignId);
            if ("ADVANCE".equals(kind)) AccountRules.checkCampaignOpenForExpenses(c.getStatus());
        }
        String n = AccountRules.trim(note);
        if (n != null && n.length() > 300) throw ApiException.badRequest("TEXT_TOO_LONG", "Note is too long");
        BigDecimal signed = "RETURN".equals(kind) ? Money.round(amount) : Money.round(amount).negate();
        UUID id = ledger(ngoId, userId, campaignId, kind, signed, null, mode, d, n, m.userId());
        audit.record(ngoId, m.userId(), "STAFF_MONEY_" + kind, "MEMBERSHIP", userId, Map.of("amount", amount.toPlainString()));
        return entries(ngoId, userId).stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
    }

    private UUID ledger(UUID ngoId, UUID userId, UUID campaignId, String kind, BigDecimal signed, UUID expenseId, String mode,
                        LocalDate date, String note, UUID actor) {
        UUID id = UUID.randomUUID();
        String n = note != null && note.length() > 300 ? note.substring(0, 300) : note;
        jdbc.update("insert into staff_money_entries(id, ngo_id, user_id, campaign_id, kind, amount, expense_id, mode, entry_date, note, recorded_by) "
                + "values (?,?,?,?,?,?,?,?,?,?,?)", id, ngoId, userId, campaignId, kind, signed, expenseId, mode, date, n, actor);
        return id;
    }

    // ---------------- vendors

    public List<Vendor> vendors(UUID ngoId) { return vendors.findByNgoIdOrderByNameAsc(ngoId); }

    public Vendor vendor(UUID ngoId, UUID id) {
        return vendors.findByIdAndNgoId(id, ngoId).orElseThrow(() -> ApiException.notFound("Vendor"));
    }

    @Transactional
    public Vendor createVendor(UUID ngoId, UUID actor, AccountRules.VendorInput in) {
        AccountRules.VendorInput v = AccountRules.vendor(in);
        if (vendors.findByNgoIdAndNameIgnoreCase(ngoId, v.name()).isPresent()) {
            throw ApiException.conflict("VENDOR_EXISTS", "A vendor named \"" + v.name() + "\" already exists");
        }
        Vendor out = new Vendor(ngoId, actor);
        out.edit(v);
        vendors.saveAndFlush(out);
        audit.record(ngoId, actor, "VENDOR_CREATED", "VENDOR", out.getId(), Map.of());
        return out;
    }

    @Transactional
    public Vendor updateVendor(UUID ngoId, UUID actor, UUID id, AccountRules.VendorInput in, Boolean active, long expected) {
        Vendor out = vendor(ngoId, id);
        if (out.getVersion() != expected) throw ApiException.conflict("STALE_VERSION", "This vendor was changed by someone else. Reload.");
        AccountRules.VendorInput v = AccountRules.vendor(in);
        vendors.findByNgoIdAndNameIgnoreCase(ngoId, v.name()).filter(x -> !x.getId().equals(id)).ifPresent(x -> {
            throw ApiException.conflict("VENDOR_EXISTS", "A vendor named \"" + v.name() + "\" already exists");
        });
        out.edit(v);
        if (active != null) out.setActive(active);
        audit.record(ngoId, actor, "VENDOR_UPDATED", "VENDOR", id, Map.of());
        return out;
    }

    public static final String VENDOR_CSV_HEADER = "name,phone,email,gstin,pan,address,category,notes";

    /**
     * Imports vendors from CSV (header row required, columns as {@link #VENDOR_CSV_HEADER}, any order; max 500 rows).
     * Existing names are skipped; invalid rows are reported with their line number and nothing else is lost.
     */
    @Transactional
    public ImportResult importVendors(UUID ngoId, UUID actor, byte[] csv) {
        if (csv.length > 512 * 1024) throw ApiException.badRequest("FILE_TOO_LARGE", "The file can be at most 512 KB");
        List<List<String>> rows = Csv.parse(new String(stripBom(csv), StandardCharsets.UTF_8));
        if (rows.isEmpty()) throw ApiException.badRequest("CSV_EMPTY", "The file is empty");
        List<String> header = rows.getFirst().stream().map(h -> h.strip().toLowerCase(Locale.ROOT)).toList();
        int nameCol = header.indexOf("name");
        if (nameCol < 0) throw ApiException.badRequest("CSV_HEADER", "The first row must be the header: " + VENDOR_CSV_HEADER);
        if (rows.size() - 1 > 500) throw ApiException.badRequest("CSV_TOO_MANY_ROWS", "At most 500 vendors per file");
        int created = 0, skipped = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row.stream().allMatch(String::isBlank)) continue;
            try {
                AccountRules.VendorInput v = AccountRules.vendor(new AccountRules.VendorInput(col(row, header, "name"), col(row, header, "phone"),
                        col(row, header, "email"), col(row, header, "gstin"), col(row, header, "pan"), col(row, header, "address"),
                        col(row, header, "category"), col(row, header, "notes")));
                if (!seen.add(v.name().toLowerCase(Locale.ROOT)) || vendors.findByNgoIdAndNameIgnoreCase(ngoId, v.name()).isPresent()) {
                    skipped++;
                    continue;
                }
                Vendor out = new Vendor(ngoId, actor);
                out.edit(v);
                vendors.save(out);
                created++;
            } catch (ApiException e) {
                errors.add(Map.of("line", r + 1, "message", e.getMessage()));
            }
        }
        vendors.flush();
        audit.record(ngoId, actor, "VENDORS_IMPORTED", "NGO", ngoId, Map.of("created", created, "skipped", skipped, "errors", errors.size()));
        return new ImportResult(created, skipped, errors);
    }

    private static String col(List<String> row, List<String> header, String name) {
        int i = header.indexOf(name);
        return i < 0 || i >= row.size() ? null : row.get(i);
    }

    private static byte[] stripBom(byte[] b) {
        return b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF
                ? new ByteArrayInputStream(b, 3, b.length - 3).readAllBytes() : b;
    }
}
