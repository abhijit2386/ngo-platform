package org.ngoplatform.campaign.app;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.ngoplatform.campaign.domain.Campaign;
import org.ngoplatform.commons.audit.AuditLog;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.events.EventPublisher;
import org.ngoplatform.commons.events.EventTypes;
import org.ngoplatform.commons.money.Money;
import org.ngoplatform.commons.storage.FileInspector;
import org.ngoplatform.commons.storage.ObjectStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CSR funding proposals (Enterprise: CSR_PROPOSALS). Written from one campaign: the budget and the progress so far
 * come from the campaign's own records. Sending stores the PDF exactly as sent (vault, category CSR) and freezes the
 * text; the log keeps notes and follow-ups; a funded proposal leads to the utilisation certificate.
 */
@Service
public class ProposalService {
    public record Proposal(UUID id, UUID ngoId, UUID campaignId, String number, String fiscalYear, String title, String funderName,
                           String funderContact, String funderEmail, String scheduleVii, BigDecimal askAmount, int durationMonths,
                           Integer beneficiaries, String location, String need, String objectives, String activities, String outcomes,
                           String monitoring, String contactName, String contactDetails, UUID packId, String packTitle, String packUrl,
                           Instant packExpiresAt, String status, LocalDate sentOn, LocalDate followUpOn, String pdfKey, String pdfSha256,
                           BigDecimal fundedAmount, LocalDate fundedOn, String funderReference, String closeReason, Instant closedAt,
                           UUID createdBy, Instant createdAt, long version) {}

    public record Input(String title, String funderName, String funderContact, String funderEmail, String scheduleVii,
                        BigDecimal askAmount, Integer durationMonths, Integer beneficiaries, String location, String need,
                        String objectives, String activities, String outcomes, String monitoring, String contactName,
                        String contactDetails, UUID packId, String packTitle, String packUrl, Instant packExpiresAt) {}

    public record Event(UUID id, String kind, String note, LocalDate followUpOn, UUID actor, Instant at) {}

    public record BudgetLine(String head, BigDecimal planned, BigDecimal spent) {}

    /** What the campaign has done so far: printed in the proposal as the track record. */
    public record Progress(BigDecimal goal, BigDecimal raised, int donations, BigDecimal spent, int approvedExpenses,
                           List<BudgetLine> budget, BigDecimal budgetTotal, LocalDate startDate, LocalDate endDate, String status) {}

    private static final String COLS = "id, ngo_id, campaign_id, proposal_number, fiscal_year, title, funder_name, funder_contact, funder_email, "
            + "schedule_vii, ask_amount, duration_months, beneficiaries, location, need, objectives, activities, outcomes, monitoring, "
            + "contact_name, contact_details, pack_id, pack_title, pack_url, pack_expires_at, status, sent_on, follow_up_on, pdf_key, "
            + "pdf_sha256, funded_amount, funded_on, funder_reference, close_reason, closed_at, created_by, created_at, version";
    private static final RowMapper<Proposal> ROW = (rs, i) -> new Proposal(rs.getObject("id", UUID.class), rs.getObject("ngo_id", UUID.class),
            rs.getObject("campaign_id", UUID.class), rs.getString("proposal_number"), rs.getString("fiscal_year"), rs.getString("title"),
            rs.getString("funder_name"), rs.getString("funder_contact"), rs.getString("funder_email"), rs.getString("schedule_vii"),
            rs.getBigDecimal("ask_amount"), rs.getInt("duration_months"), (Integer) rs.getObject("beneficiaries"), rs.getString("location"),
            rs.getString("need"), rs.getString("objectives"), rs.getString("activities"), rs.getString("outcomes"), rs.getString("monitoring"),
            rs.getString("contact_name"), rs.getString("contact_details"), rs.getObject("pack_id", UUID.class), rs.getString("pack_title"),
            rs.getString("pack_url"), ts(rs.getTimestamp("pack_expires_at")), rs.getString("status"), rs.getObject("sent_on", LocalDate.class),
            rs.getObject("follow_up_on", LocalDate.class), rs.getString("pdf_key"), rs.getString("pdf_sha256"), rs.getBigDecimal("funded_amount"),
            rs.getObject("funded_on", LocalDate.class), rs.getString("funder_reference"), rs.getString("close_reason"),
            ts(rs.getTimestamp("closed_at")), rs.getObject("created_by", UUID.class), ts(rs.getTimestamp("created_at")), rs.getLong("version"));

    private static Instant ts(Timestamp t) { return t == null ? null : t.toInstant(); }

    private final JdbcTemplate jdbc;
    private final CampaignService campaigns;
    private final NgoFacts ngos;
    private final ObjectStorage storage;
    private final EventPublisher events;
    private final AuditLog audit;
    private final Clock clock;
    private final String publicApi;

    public ProposalService(JdbcTemplate jdbc, CampaignService campaigns, NgoFacts ngos, ObjectStorage storage, EventPublisher events,
                           AuditLog audit, Clock clock, @Value("${ngo.public-web-url:http://localhost:8080}") String publicApi) {
        this.jdbc = jdbc; this.campaigns = campaigns; this.ngos = ngos; this.storage = storage; this.events = events;
        this.audit = audit; this.clock = clock; this.publicApi = publicApi;
    }

    // ------------------------------------------------------------------ reading

    public List<Proposal> list(UUID ngoId, UUID campaignId) {
        return campaignId == null
                ? jdbc.query("select " + COLS + " from csr_proposals where ngo_id = ? order by created_at desc limit 300", ROW, ngoId)
                : jdbc.query("select " + COLS + " from csr_proposals where ngo_id = ? and campaign_id = ? order by created_at desc", ROW, ngoId, campaignId);
    }

    public ProposalRules.Summary summary(UUID ngoId) {
        List<ProposalRules.Row> rows = jdbc.query("select status, ask_amount, funded_amount, follow_up_on from csr_proposals where ngo_id = ?",
                (rs, i) -> new ProposalRules.Row(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getObject(4, LocalDate.class)), ngoId);
        return ProposalRules.summary(rows, campaigns.today());
    }

    public Proposal get(UUID ngoId, UUID id) {
        List<Proposal> l = jdbc.query("select " + COLS + " from csr_proposals where id = ? and ngo_id = ?", ROW, id, ngoId);
        if (l.isEmpty()) throw ApiException.notFound("Proposal");
        return l.getFirst();
    }

    public List<Event> events(UUID proposalId) {
        return jdbc.query("select id, kind, note, follow_up_on, actor, at from proposal_events where proposal_id = ? order by at, id",
                (rs, i) -> new Event(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, LocalDate.class),
                        rs.getObject(5, UUID.class), rs.getTimestamp(6).toInstant()), proposalId);
    }

    /** Utilisation certificates made for this proposal's grant. */
    public List<Map<String, Object>> certificates(UUID proposalId) {
        return jdbc.query("select id, uc_number, status, period_to from utilisation_certificates where proposal_id = ? order by created_at",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject(1, UUID.class));
                    m.put("number", rs.getString(2));
                    m.put("status", rs.getString(3));
                    m.put("periodTo", rs.getObject(4, LocalDate.class));
                    return m;
                }, proposalId);
    }

    public Progress progress(UUID ngoId, UUID campaignId) {
        Campaign c = campaigns.load(ngoId, campaignId);
        List<BudgetLine> budget = jdbc.query("select b.category, b.planned, coalesce((select sum(e.amount) from campaign_expenses e "
                        + "where e.budget_line_id = b.id and e.status = 'APPROVED'), 0) from campaign_budget_lines b where b.campaign_id = ? "
                        + "order by b.position, b.created_at",
                (rs, i) -> new BudgetLine(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3)), campaignId);
        BigDecimal total = budget.stream().map(BudgetLine::planned).reduce(Money.ZERO, BigDecimal::add);
        Map<String, Object> spent = jdbc.queryForMap("select coalesce(sum(amount), 0) as spent, count(*) as n from campaign_expenses "
                + "where campaign_id = ? and status = 'APPROVED'", campaignId);
        return new Progress(c.getGoalAmount(), c.getRaisedAmount(), c.getDonationCount(), (BigDecimal) spent.get("spent"),
                ((Number) spent.get("n")).intValue(), budget, Money.round(total), c.getStartDate(), c.getEndDate(), c.getStatus().name());
    }

    // ------------------------------------------------------------------ writing (administrators)

    /** A new draft from a campaign: the text starts from the campaign's own story, the contact from the NGO profile. */
    @Transactional
    public Proposal create(UUID ngoId, UUID actor, UUID campaignId, String funderName, BigDecimal askAmount) {
        if (campaignId == null) throw ApiException.badRequest("CAMPAIGN_REQUIRED", "Choose the campaign this proposal is for");
        Campaign c = campaigns.load(ngoId, campaignId);
        String funder = ProposalRules.funder(funderName);
        Progress p = progress(ngoId, campaignId);
        BigDecimal ask = askAmount != null ? ProposalRules.ask(askAmount)
                : ProposalRules.ask(p.goal().subtract(p.raised()).signum() > 0 ? p.goal().subtract(p.raised()) : p.goal());
        Map<String, Object> ngo = safeDetails(ngoId);
        String story = c.getStory() == null || c.getStory().isBlank() ? c.getSummary() : c.getSummary() + "\n\n" + c.getStory();
        UUID id = UUID.randomUUID();
        String number = nextNumber(ngoId);
        jdbc.update("insert into csr_proposals(id, ngo_id, campaign_id, proposal_number, fiscal_year, title, funder_name, schedule_vii, "
                        + "ask_amount, duration_months, location, need, contact_name, contact_details, created_by) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, ngoId, c.getId(), number, UcRules.fiscalYear(campaigns.today()), clip(c.getTitle(), 160), funder,
                ProposalRules.scheduleFor(c.getCause()), ask, 12, clip(c.getNgoCity(), 160), story == null || story.isBlank() ? "" : clip(story, 4000),
                clip(contactName(ngo), 120), clip(str(ngo.get("officialEmail")), 200), actor);
        log(id, "CREATED", null, null, actor);
        audit.record(ngoId, actor, "PROPOSAL_CREATED", "CSR_PROPOSAL", id, Map.of("campaign", c.getId().toString()));
        return get(ngoId, id);
    }

    @Transactional
    public Proposal update(UUID ngoId, UUID actor, UUID id, Input in, long version) {
        Proposal p = draft(ngoId, id);
        if (p.version() != version) throw stale();
        jdbc.update("update csr_proposals set title = ?, funder_name = ?, funder_contact = ?, funder_email = ?, schedule_vii = ?, ask_amount = ?, "
                        + "duration_months = ?, beneficiaries = ?, location = ?, need = ?, objectives = ?, activities = ?, outcomes = ?, monitoring = ?, "
                        + "contact_name = ?, contact_details = ?, pack_id = ?, pack_title = ?, pack_url = ?, pack_expires_at = ?, updated_at = now(), "
                        + "version = version + 1 where id = ? and version = ?",
                ProposalRules.title(in.title()), ProposalRules.funder(in.funderName()), ProposalRules.optional(in.funderContact(), 120, "CONTACT"),
                ProposalRules.email(in.funderEmail()), ProposalRules.schedule(in.scheduleVii()), ProposalRules.ask(in.askAmount()),
                ProposalRules.months(in.durationMonths()), ProposalRules.beneficiaries(in.beneficiaries()),
                ProposalRules.optional(in.location(), 160, "LOCATION"), ProposalRules.section(in.need(), "NEED"),
                ProposalRules.section(in.objectives(), "OBJECTIVES"), ProposalRules.section(in.activities(), "ACTIVITIES"),
                ProposalRules.section(in.outcomes(), "OUTCOMES"), ProposalRules.section(in.monitoring(), "MONITORING"),
                ProposalRules.optional(in.contactName(), 120, "CONTACT_NAME"), ProposalRules.optional(in.contactDetails(), 200, "CONTACT_DETAILS"),
                in.packUrl() == null || in.packUrl().isBlank() ? null : in.packId(),
                in.packUrl() == null || in.packUrl().isBlank() ? null : ProposalRules.optional(in.packTitle(), 160, "PACK"),
                ProposalRules.packUrl(in.packUrl(), publicApi),
                in.packUrl() == null || in.packUrl().isBlank() || in.packExpiresAt() == null ? null : Timestamp.from(in.packExpiresAt()),
                id, version);
        return get(ngoId, id);
    }

    /** Sent: the PDF as sent is stored (and filed in the vault), the text is frozen, the follow-up date set. */
    @Transactional
    public Proposal send(UUID ngoId, UUID actor, UUID id, LocalDate sentOn, LocalDate followUpOn, long version) {
        Proposal p = draft(ngoId, id);
        if (p.version() != version) throw stale();
        List<String> missing = ProposalRules.missingForSending(p.need(), p.objectives(), p.activities(), p.outcomes());
        if (!missing.isEmpty()) throw ApiException.rule("PROPOSAL_INCOMPLETE", "Fill in before sending: " + String.join("; ", missing));
        ProposalRules.sentOn(sentOn, followUpOn, campaigns.today());
        if (p.packExpiresAt() != null && clock.instant().isAfter(p.packExpiresAt())) {
            throw ApiException.rule("PACK_EXPIRED", "The compliance pack link in this proposal has expired. Attach a current one.");
        }
        Campaign c = campaigns.load(ngoId, p.campaignId());
        Proposal sent = withSent(p, sentOn);
        byte[] pdf = ProposalPdf.render(sent, c.getTitle(), safeDetails(ngoId), progress(ngoId, p.campaignId()), false);
        String key = "ngos/" + ngoId + "/csr/proposals/" + id + ".pdf";
        storage.put(key, pdf, "application/pdf");
        jdbc.update("update csr_proposals set status = 'SENT', sent_on = ?, sent_by = ?, follow_up_on = ?, pdf_key = ?, pdf_sha256 = ?, "
                + "updated_at = now(), version = version + 1 where id = ?", sentOn, actor, followUpOn, key, FileInspector.sha256(pdf), id);
        log(id, "SENT", null, followUpOn, actor);
        Proposal after = get(ngoId, id);
        events.publish(EventTypes.DOCUMENT_FILE_REGISTERED, ngoId, id, vaultFile(after, c.getTitle(), pdf.length));
        audit.record(ngoId, actor, "PROPOSAL_SENT", "CSR_PROPOSAL", id, Map.of("funder", p.funderName(), "ask", p.askAmount().toPlainString()));
        return after;
    }

    /** A call, a meeting, a reminder: kept in the log; on an open proposal it can also move the follow-up date. */
    @Transactional
    public Proposal note(UUID ngoId, UUID actor, UUID id, String note, LocalDate followUpOn) {
        Proposal p = get(ngoId, id);
        if (ProposalRules.CLOSED.contains(p.status())) throw ApiException.rule("PROPOSAL_CLOSED", "This proposal is closed");
        String text = note == null ? "" : note.strip();
        if (text.length() < 2 || text.length() > 1000) throw ApiException.badRequest("INVALID_NOTE", "Write the note (2-1000 characters)");
        if (followUpOn != null) {
            if (!"SENT".equals(p.status())) throw ApiException.rule("NOT_SENT", "Follow-up dates are for proposals that have been sent");
            ProposalRules.followUp(followUpOn, campaigns.today());
            jdbc.update("update csr_proposals set follow_up_on = ?, updated_at = now(), version = version + 1 where id = ?", followUpOn, id);
        }
        log(id, "NOTE", text, followUpOn, actor);
        return get(ngoId, id);
    }

    /** FUNDED (amount, date, the funder's reference), DECLINED or WITHDRAWN (with a reason). */
    @Transactional
    public Proposal close(UUID ngoId, UUID actor, UUID id, String outcome, BigDecimal amount, LocalDate on, String reference, String reason) {
        Proposal p = get(ngoId, id);
        ProposalRules.canClose(p.status(), outcome);
        String why = reason == null || reason.isBlank() ? null : reason.strip();
        if (why != null && why.length() > 300) throw ApiException.badRequest("INVALID_REASON", "Keep the reason under 300 characters");
        if ("FUNDED".equals(outcome)) {
            ProposalRules.funded(amount, on, p.sentOn(), campaigns.today());
            jdbc.update("update csr_proposals set status = 'FUNDED', funded_amount = ?, funded_on = ?, funder_reference = ?, close_reason = ?, "
                            + "closed_by = ?, closed_at = now(), follow_up_on = null, updated_at = now(), version = version + 1 where id = ?",
                    ProposalRules.ask(amount), on, ProposalRules.optional(reference, 120, "REFERENCE"), why, actor, id);
            log(id, "FUNDED", why, null, actor);
        } else {
            if (why == null || why.length() < 3) throw ApiException.badRequest("REASON_REQUIRED", "Give a short reason");
            jdbc.update("update csr_proposals set status = ?, close_reason = ?, closed_by = ?, closed_at = now(), follow_up_on = null, "
                    + "updated_at = now(), version = version + 1 where id = ?", outcome, why, actor, id);
            log(id, outcome, why, null, actor);
        }
        audit.record(ngoId, actor, "PROPOSAL_" + outcome, "CSR_PROPOSAL", id, Map.of());
        return get(ngoId, id);
    }

    /** A new draft with the same text, e.g. to send the same project to another company or to revise a declined one. */
    @Transactional
    public Proposal copy(UUID ngoId, UUID actor, UUID id) {
        Proposal p = get(ngoId, id);
        UUID copy = UUID.randomUUID();
        jdbc.update("insert into csr_proposals(id, ngo_id, campaign_id, proposal_number, fiscal_year, title, funder_name, funder_contact, "
                        + "funder_email, schedule_vii, ask_amount, duration_months, beneficiaries, location, need, objectives, activities, outcomes, "
                        + "monitoring, contact_name, contact_details, created_by) "
                        + "select ?, ngo_id, campaign_id, ?, ?, title, funder_name, funder_contact, funder_email, schedule_vii, ask_amount, "
                        + "duration_months, beneficiaries, location, need, objectives, activities, outcomes, monitoring, contact_name, contact_details, ? "
                        + "from csr_proposals where id = ?",
                copy, nextNumber(ngoId), UcRules.fiscalYear(campaigns.today()), actor, id);
        log(copy, "COPIED", "Copied from " + p.number(), null, actor);
        audit.record(ngoId, actor, "PROPOSAL_COPIED", "CSR_PROPOSAL", copy, Map.of("from", p.number()));
        return get(ngoId, copy);
    }

    /** The PDF: the stored one once sent, otherwise a draft rendered now. */
    public byte[] pdf(Proposal p) {
        if (p.pdfKey() != null) {
            try (var in = storage.get(p.pdfKey())) { return in.readAllBytes(); }
            catch (java.io.IOException e) { throw new IllegalStateException("Proposal PDF unreadable", e); }
        }
        Campaign c = campaigns.load(p.ngoId(), p.campaignId());
        return ProposalPdf.render(p, c.getTitle(), safeDetails(p.ngoId()), progress(p.ngoId(), p.campaignId()), true);
    }

    // ------------------------------------------------------------------ helpers

    private Proposal draft(UUID ngoId, UUID id) {
        Proposal p = get(ngoId, id);
        if (!"DRAFT".equals(p.status())) throw ApiException.rule("PROPOSAL_SENT", "This proposal has been sent and cannot be changed. Make a copy to revise it.");
        return p;
    }

    private String nextNumber(UUID ngoId) {
        String fy = UcRules.fiscalYear(campaigns.today());
        jdbc.update("insert into proposal_counters(ngo_id, fiscal_year) values (?, ?) on conflict do nothing", ngoId, fy);
        Integer n = jdbc.queryForObject("update proposal_counters set next_value = next_value + 1 where ngo_id = ? and fiscal_year = ? "
                + "returning next_value - 1", Integer.class, ngoId, fy);
        return ProposalRules.number(fy, n);
    }

    private void log(UUID proposalId, String kind, String note, LocalDate followUp, UUID actor) {
        jdbc.update("insert into proposal_events(id, proposal_id, kind, note, follow_up_on, actor) values (?,?,?,?,?,?)",
                UUID.randomUUID(), proposalId, kind, note, followUp, actor);
    }

    private static ApiException stale() {
        return ApiException.conflict("STALE", "Someone else changed this proposal. Reload it and try again.");
    }

    private Map<String, Object> safeDetails(UUID ngoId) {
        try { return ngos.details(ngoId); } catch (ApiException e) { return Map.of(); }
    }

    private static String contactName(Map<String, Object> ngo) {
        String n = str(ngo.get("signatoryName"));
        String d = str(ngo.get("signatoryDesignation"));
        return n == null ? null : d == null ? n : n + ", " + d;
    }

    private static Proposal withSent(Proposal p, LocalDate sentOn) {
        return new Proposal(p.id(), p.ngoId(), p.campaignId(), p.number(), p.fiscalYear(), p.title(), p.funderName(), p.funderContact(),
                p.funderEmail(), p.scheduleVii(), p.askAmount(), p.durationMonths(), p.beneficiaries(), p.location(), p.need(), p.objectives(),
                p.activities(), p.outcomes(), p.monitoring(), p.contactName(), p.contactDetails(), p.packId(), p.packTitle(), p.packUrl(),
                p.packExpiresAt(), "SENT", sentOn, p.followUpOn(), p.pdfKey(), p.pdfSha256(), p.fundedAmount(), p.fundedOn(),
                p.funderReference(), p.closeReason(), p.closedAt(), p.createdBy(), p.createdAt(), p.version());
    }

    Map<String, Object> vaultFile(Proposal p, String campaignTitle, long size) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ngoId", p.ngoId().toString());
        m.put("source", "CSR_PROPOSAL");
        m.put("sourceRef", p.id().toString());
        m.put("storageKey", p.pdfKey());
        m.put("title", "CSR proposal " + p.number() + " - " + p.funderName());
        m.put("category", "CSR");
        m.put("contentType", "application/pdf");
        m.put("sizeBytes", size);
        m.put("visibility", "TEAM");
        m.put("requiredPermission", "CAMPAIGN_VIEW");
        m.put("linkType", "CAMPAIGN");
        m.put("linkId", p.campaignId().toString());
        m.put("linkLabel", campaignTitle);
        m.put("documentDate", p.sentOn().toString());
        m.put("createdAt", clock.instant().toString());
        return m;
    }

    private static String clip(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static String str(Object o) { return o == null || o.toString().isBlank() ? null : o.toString(); }

    static List<String> paragraphs(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String p : s.split("\\n")) out.add(p.strip());
        return out;
    }
}
