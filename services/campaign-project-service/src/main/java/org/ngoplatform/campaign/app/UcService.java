package org.ngoplatform.campaign.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Utilisation certificates for CSR funders (Enterprise: CSR_UTILISATION_CERT). DRAFT: figures computed live from the
 * campaign's accounts. NGO_SIGNED: an administrator signed for the NGO; figures are frozen and the CA gets a 14-day
 * link. CA_CERTIFIED: the CA entered membership no. and UDIN through the link; the final PDF is stored and listed in
 * the vault (category CSR). CANCELLED: corrections are a new UC.
 */
@Service
public class UcService {
    public record Uc(UUID id, UUID ngoId, UUID campaignId, String number, String fiscalYear, String funderName, String funderReference,
                     String csr1Number, BigDecimal grantAmount, LocalDate grantReceivedOn, LocalDate periodFrom, LocalDate periodTo,
                     String status, String figuresJson, String signatoryName, String signatoryRole, UUID ngoSignedBy, Instant ngoSignedAt,
                     String caCode, Instant caLinkExpiresAt, String caName, String caMembershipNo, String caFirm, String caFirmRegNo,
                     String caUdin, String caPlace, Instant caCertifiedAt, String pdfKey, String pdfSha256, String cancelReason,
                     Instant cancelledAt, UUID createdBy, Instant createdAt, long version) {}

    public record Input(UUID campaignId, String funderName, String funderReference, String csr1Number, BigDecimal grantAmount,
                        LocalDate grantReceivedOn, LocalDate periodFrom, LocalDate periodTo, UUID proposalId) {}

    private static final String COLS = "id, ngo_id, campaign_id, uc_number, fiscal_year, funder_name, funder_reference, csr1_number, "
            + "grant_amount, grant_received_on, period_from, period_to, status, figures::text as figures, signatory_name, signatory_role, "
            + "ngo_signed_by, ngo_signed_at, ca_code, ca_link_expires_at, ca_name, ca_membership_no, ca_firm, ca_firm_reg_no, ca_udin, "
            + "ca_place, ca_certified_at, pdf_key, pdf_sha256, cancel_reason, cancelled_at, created_by, created_at, version";
    private static final RowMapper<Uc> ROW = (rs, i) -> new Uc(rs.getObject("id", UUID.class), rs.getObject("ngo_id", UUID.class),
            rs.getObject("campaign_id", UUID.class), rs.getString("uc_number"), rs.getString("fiscal_year"), rs.getString("funder_name"),
            rs.getString("funder_reference"), rs.getString("csr1_number"), rs.getBigDecimal("grant_amount"),
            rs.getObject("grant_received_on", LocalDate.class), rs.getObject("period_from", LocalDate.class),
            rs.getObject("period_to", LocalDate.class), rs.getString("status"), rs.getString("figures"), rs.getString("signatory_name"),
            rs.getString("signatory_role"), rs.getObject("ngo_signed_by", UUID.class), ts(rs.getTimestamp("ngo_signed_at")),
            rs.getString("ca_code"), ts(rs.getTimestamp("ca_link_expires_at")), rs.getString("ca_name"), rs.getString("ca_membership_no"),
            rs.getString("ca_firm"), rs.getString("ca_firm_reg_no"), rs.getString("ca_udin"), rs.getString("ca_place"),
            ts(rs.getTimestamp("ca_certified_at")), rs.getString("pdf_key"), rs.getString("pdf_sha256"), rs.getString("cancel_reason"),
            ts(rs.getTimestamp("cancelled_at")), rs.getObject("created_by", UUID.class), ts(rs.getTimestamp("created_at")), rs.getLong("version"));

    private static Instant ts(Timestamp t) { return t == null ? null : t.toInstant(); }

    private final JdbcTemplate jdbc;
    private final CampaignService campaigns;
    private final NgoFacts ngos;
    private final ObjectStorage storage;
    private final EventPublisher events;
    private final AuditLog audit;
    private final ObjectMapper mapper;
    private final Clock clock;

    public UcService(JdbcTemplate jdbc, CampaignService campaigns, NgoFacts ngos, ObjectStorage storage, EventPublisher events,
                     AuditLog audit, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc; this.campaigns = campaigns; this.ngos = ngos; this.storage = storage; this.events = events;
        this.audit = audit; this.mapper = mapper; this.clock = clock;
    }

    // ------------------------------------------------------------------ reading

    public List<Uc> list(UUID ngoId, UUID campaignId) {
        return campaignId == null
                ? jdbc.query("select " + COLS + " from utilisation_certificates where ngo_id = ? order by created_at desc limit 200", ROW, ngoId)
                : jdbc.query("select " + COLS + " from utilisation_certificates where ngo_id = ? and campaign_id = ? order by created_at desc",
                        ROW, ngoId, campaignId);
    }

    public Uc get(UUID ngoId, UUID id) {
        List<Uc> l = jdbc.query("select " + COLS + " from utilisation_certificates where id = ? and ngo_id = ?", ROW, id, ngoId);
        if (l.isEmpty()) throw ApiException.notFound("Utilisation certificate");
        return l.getFirst();
    }

    /** Frozen figures once signed; while a draft, computed now from the campaign's accounts. */
    public UcRules.Figures figures(Uc uc) {
        if (uc.figuresJson() != null) {
            try { return mapper.readValue(uc.figuresJson(), UcRules.Figures.class); }
            catch (JsonProcessingException e) { throw new IllegalStateException("UC figures unreadable", e); }
        }
        return compute(uc.campaignId(), uc.grantAmount(), uc.periodFrom(), uc.periodTo());
    }

    /** Approved expenses dated in the period (cancelled ones excluded), grouped by budget head. */
    UcRules.Figures compute(UUID campaignId, BigDecimal grant, LocalDate from, LocalDate to) {
        Map<String, BigDecimal> budget = new LinkedHashMap<>();
        jdbc.query("select category, planned from campaign_budget_lines where campaign_id = ? order by position, created_at",
                rs -> { budget.put(rs.getString(1), rs.getBigDecimal(2)); }, campaignId);
        List<UcRules.Line> lines = jdbc.query("select e.voucher_no, e.expense_date, coalesce(b.category, e.category) as head, e.description, "
                        + "coalesce(e.vendor_name, v.name) as vendor, e.amount, (select count(*) from expense_attachments a where a.expense_id = e.id) as bills "
                        + "from campaign_expenses e left join campaign_budget_lines b on b.id = e.budget_line_id left join vendors v on v.id = e.vendor_id "
                        + "where e.campaign_id = ? and e.status = 'APPROVED' and e.expense_date between ? and ? order by e.expense_date, e.voucher_no",
                (rs, i) -> new UcRules.Line(rs.getString(1), rs.getObject(2, LocalDate.class), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getBigDecimal(6), rs.getInt(7)), campaignId, from, to);
        BigDecimal received = jdbc.queryForObject("select coalesce(sum(amount - refunded), 0) from campaign_donations where campaign_id = ? "
                + "and created_at >= ? and created_at < ?", BigDecimal.class, campaignId,
                Timestamp.from(from.atStartOfDay(CampaignService.INDIA).toInstant()),
                Timestamp.from(to.plusDays(1).atStartOfDay(CampaignService.INDIA).toInstant()));
        return UcRules.figures(grant, budget, lines, received == null ? Money.ZERO : received);
    }

    // ------------------------------------------------------------------ writing (administrators)

    @Transactional
    public Uc create(UUID ngoId, UUID actor, Input in) {
        if (in.campaignId() == null) throw ApiException.badRequest("CAMPAIGN_REQUIRED", "Choose the campaign the grant was for");
        Campaign c = campaigns.load(ngoId, in.campaignId());
        BigDecimal grant = UcRules.grant(in.grantAmount());
        UcRules.period(in.periodFrom(), in.periodTo(), in.grantReceivedOn(), c.getStartDate(), campaigns.today());
        if (in.proposalId() != null) {
            Integer ok = jdbc.queryForObject("select count(*) from csr_proposals where id = ? and ngo_id = ? and campaign_id = ? and status = 'FUNDED'",
                    Integer.class, in.proposalId(), ngoId, c.getId());
            if (ok == null || ok == 0) throw ApiException.rule("PROPOSAL_NOT_FUNDED", "That proposal is not a funded proposal of this campaign");
        }
        String fy = UcRules.fiscalYear(in.periodTo());
        jdbc.update("insert into uc_counters(ngo_id, fiscal_year) values (?, ?) on conflict do nothing", ngoId, fy);
        Integer n = jdbc.queryForObject("update uc_counters set next_value = next_value + 1 where ngo_id = ? and fiscal_year = ? "
                + "returning next_value - 1", Integer.class, ngoId, fy);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into utilisation_certificates(id, ngo_id, campaign_id, uc_number, fiscal_year, funder_name, funder_reference, "
                        + "csr1_number, grant_amount, grant_received_on, period_from, period_to, created_by, proposal_id) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, ngoId, c.getId(), UcRules.number(fy, n), fy, UcRules.funder(in.funderName()),
                UcRules.optional(in.funderReference(), 120, "REFERENCE"), UcRules.optional(in.csr1Number(), 40, "CSR1"), grant,
                in.grantReceivedOn(), in.periodFrom(), in.periodTo(), actor, in.proposalId());
        audit.record(ngoId, actor, "UC_CREATED", "UTILISATION_CERTIFICATE", id, Map.of("campaign", c.getId().toString()));
        return get(ngoId, id);
    }

    @Transactional
    public Uc update(UUID ngoId, UUID actor, UUID id, Input in, long version) {
        Uc uc = draft(ngoId, id);
        Campaign c = campaigns.load(ngoId, uc.campaignId());
        BigDecimal grant = UcRules.grant(in.grantAmount());
        UcRules.period(in.periodFrom(), in.periodTo(), in.grantReceivedOn(), c.getStartDate(), campaigns.today());
        int done = jdbc.update("update utilisation_certificates set funder_name = ?, funder_reference = ?, csr1_number = ?, grant_amount = ?, "
                        + "grant_received_on = ?, period_from = ?, period_to = ?, updated_at = now(), version = version + 1 where id = ? and version = ?",
                UcRules.funder(in.funderName()), UcRules.optional(in.funderReference(), 120, "REFERENCE"),
                UcRules.optional(in.csr1Number(), 40, "CSR1"), grant, in.grantReceivedOn(), in.periodFrom(), in.periodTo(), id, version);
        if (done == 0) throw ApiException.conflict("STALE", "Someone else changed this certificate. Reload it and try again.");
        return get(ngoId, id);
    }

    /** The NGO signs: figures frozen as they are now, and the CA gets a 14-day link. */
    @Transactional
    public Uc sign(UUID ngoId, UUID actor, UUID id, String name, String role, long version) {
        Uc uc = draft(ngoId, id);
        if (uc.version() != version) throw ApiException.conflict("STALE", "Someone else changed this certificate. Reload it and try again.");
        UcRules.Figures f = compute(uc.campaignId(), uc.grantAmount(), uc.periodFrom(), uc.periodTo());
        if (f.lines().isEmpty()) throw ApiException.rule("NO_EXPENSES", "No approved expenses in this period: there is nothing to certify yet");
        String json;
        try { json = mapper.writeValueAsString(f); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
        Instant now = clock.instant();
        jdbc.update("update utilisation_certificates set status = 'NGO_SIGNED', figures = ?::jsonb, signatory_name = ?, signatory_role = ?, "
                        + "ngo_signed_by = ?, ngo_signed_at = ?, ca_code = ?, ca_link_expires_at = ?, updated_at = now(), version = version + 1 "
                        + "where id = ?", json, text(name, 3, 120, "SIGNATORY", "Enter the name of the person signing for the organisation"),
                text(role, 2, 80, "ROLE", "Enter their designation (e.g. Secretary, Treasurer)"), actor, Timestamp.from(now),
                UcRules.newCode(), Timestamp.from(now.plus(Duration.ofDays(UcRules.CA_LINK_DAYS))), id);
        audit.record(ngoId, actor, "UC_SIGNED", "UTILISATION_CERTIFICATE", id, Map.of("utilised", f.utilised().toPlainString()));
        return get(ngoId, id);
    }

    /** A new CA link (the old one stops working), e.g. after it expired or went to the wrong person. */
    @Transactional
    public Uc renewCaLink(UUID ngoId, UUID actor, UUID id) {
        Uc uc = get(ngoId, id);
        if (!"NGO_SIGNED".equals(uc.status())) throw ApiException.rule("NOT_AWAITING_CA", "This certificate is not waiting for the CA");
        Instant now = clock.instant();
        jdbc.update("update utilisation_certificates set ca_code = ?, ca_link_expires_at = ?, updated_at = now(), version = version + 1 where id = ?",
                UcRules.newCode(), Timestamp.from(now.plus(Duration.ofDays(UcRules.CA_LINK_DAYS))), id);
        audit.record(ngoId, actor, "UC_CA_LINK_RENEWED", "UTILISATION_CERTIFICATE", id, Map.of());
        return get(ngoId, id);
    }

    @Transactional
    public Uc cancel(UUID ngoId, UUID actor, UUID id, String reason) {
        Uc uc = get(ngoId, id);
        if ("CANCELLED".equals(uc.status())) throw ApiException.rule("ALREADY_CANCELLED", "This certificate is already cancelled");
        String why = text(reason, 5, 300, "REASON", "Give a reason (5-300 characters)");
        jdbc.update("update utilisation_certificates set status = 'CANCELLED', cancel_reason = ?, cancelled_by = ?, cancelled_at = now(), "
                + "ca_link_expires_at = case when ca_link_expires_at is null then null else least(ca_link_expires_at, now()) end, "
                + "updated_at = now(), version = version + 1 where id = ?", why, actor, id);
        if (uc.pdfKey() != null) {
            Uc after = get(ngoId, id);
            events.publish(EventTypes.DOCUMENT_FILE_REGISTERED, ngoId, id, vaultFile(after, campaigns.load(ngoId, uc.campaignId()).getTitle(), pdf(after).length));
        }
        audit.record(ngoId, actor, "UC_CANCELLED", "UTILISATION_CERTIFICATE", id, Map.of("reason", why));
        return get(ngoId, id);
    }

    private Uc draft(UUID ngoId, UUID id) {
        Uc uc = get(ngoId, id);
        if (!"DRAFT".equals(uc.status())) throw ApiException.rule("UC_SIGNED", "This certificate has been signed and cannot be changed. Cancel it and make a new one.");
        return uc;
    }

    // ------------------------------------------------------------------ the chartered accountant (public link)

    /** The UC behind a CA link, if it is still open (or already certified, to show the result). */
    public Uc byCaCode(String code) {
        if (!UcRules.validCode(code)) throw ApiException.notFound("Link");
        List<Uc> l = jdbc.query("select " + COLS + " from utilisation_certificates where ca_code = ?", ROW, code);
        if (l.isEmpty()) throw ApiException.notFound("Link");
        Uc uc = l.getFirst();
        if ("CANCELLED".equals(uc.status())) throw new ApiException(HttpStatus.GONE, "UC_CANCELLED", "The organisation has cancelled this certificate.");
        if ("NGO_SIGNED".equals(uc.status()) && clock.instant().isAfter(uc.caLinkExpiresAt())) {
            throw new ApiException(HttpStatus.GONE, "LINK_EXPIRED", "This link has expired. Please ask the organisation for a new one.");
        }
        return uc;
    }

    /** The CA certifies: identifiers checked, final PDF rendered, stored and listed in the vault. */
    @Transactional
    public Uc certify(String code, UcRules.Ca ca, boolean declared) {
        Uc uc = byCaCode(code);
        if ("CA_CERTIFIED".equals(uc.status())) throw ApiException.conflict("ALREADY_CERTIFIED", "This certificate has already been certified");
        if (!declared) throw ApiException.badRequest("DECLARATION_REQUIRED", "Please confirm the declaration");
        Instant now = clock.instant();
        Uc certified = new Uc(uc.id(), uc.ngoId(), uc.campaignId(), uc.number(), uc.fiscalYear(), uc.funderName(), uc.funderReference(),
                uc.csr1Number(), uc.grantAmount(), uc.grantReceivedOn(), uc.periodFrom(), uc.periodTo(), "CA_CERTIFIED", uc.figuresJson(),
                uc.signatoryName(), uc.signatoryRole(), uc.ngoSignedBy(), uc.ngoSignedAt(), uc.caCode(), uc.caLinkExpiresAt(), ca.name(),
                ca.membershipNo(), ca.firm(), ca.firmRegNo(), ca.udin(), ca.place(), now, null, null, null, null, uc.createdBy(),
                uc.createdAt(), uc.version());
        Campaign c = campaigns.load(uc.ngoId(), uc.campaignId());
        byte[] pdf = UcPdf.render(certified, c.getTitle(), ngos.details(uc.ngoId()), figures(uc), false);
        String key = "ngos/" + uc.ngoId() + "/csr/uc/" + uc.id() + ".pdf";
        storage.put(key, pdf, "application/pdf");
        int done = jdbc.update("update utilisation_certificates set status = 'CA_CERTIFIED', ca_name = ?, ca_membership_no = ?, ca_firm = ?, "
                        + "ca_firm_reg_no = ?, ca_udin = ?, ca_place = ?, ca_certified_at = ?, pdf_key = ?, pdf_sha256 = ?, updated_at = now(), "
                        + "version = version + 1 where id = ? and status = 'NGO_SIGNED'", ca.name(), ca.membershipNo(), ca.firm(), ca.firmRegNo(),
                ca.udin(), ca.place(), Timestamp.from(now), key, FileInspector.sha256(pdf), uc.id());
        if (done == 0) throw ApiException.conflict("ALREADY_CERTIFIED", "This certificate has already been certified");
        Uc after = get(uc.ngoId(), uc.id());
        events.publish(EventTypes.DOCUMENT_FILE_REGISTERED, uc.ngoId(), uc.id(), vaultFile(after, c.getTitle(), pdf.length));
        audit.record(uc.ngoId(), null, "UC_CERTIFIED", "UTILISATION_CERTIFICATE", uc.id(), Map.of("udin", ca.udin(), "membership", ca.membershipNo()));
        return after;
    }

    /** The PDF: the stored final one once certified, otherwise a draft rendered now (watermarked). */
    public byte[] pdf(Uc uc) {
        if (uc.pdfKey() != null) {
            try (var in = storage.get(uc.pdfKey())) { return in.readAllBytes(); }
            catch (java.io.IOException e) { throw new IllegalStateException("UC PDF unreadable", e); }
        }
        Campaign c = campaigns.load(uc.ngoId(), uc.campaignId());
        return UcPdf.render(uc, c.getTitle(), ngos.details(uc.ngoId()), figures(uc), true);
    }

    /** Vault entry (category CSR, linked to the campaign); a cancelled UC stays listed as "Cancelled: ...". */
    Map<String, Object> vaultFile(Uc uc, String campaignTitle, long size) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("ngoId", uc.ngoId().toString());
        p.put("source", "UTILISATION_CERT");
        p.put("sourceRef", uc.id().toString());
        p.put("storageKey", uc.pdfKey());
        p.put("title", ("CANCELLED".equals(uc.status()) ? "Cancelled: " : "") + "Utilisation certificate " + uc.number() + " - " + uc.funderName());
        p.put("category", "CSR");
        p.put("contentType", "application/pdf");
        p.put("sizeBytes", size);
        p.put("visibility", "TEAM");
        p.put("requiredPermission", "CAMPAIGN_VIEW");
        p.put("linkType", "CAMPAIGN");
        p.put("linkId", uc.campaignId().toString());
        p.put("linkLabel", campaignTitle);
        p.put("documentDate", uc.periodTo().toString());
        p.put("createdAt", (uc.caCertifiedAt() == null ? clock.instant() : uc.caCertifiedAt()).toString());
        return p;
    }

    /** The funded proposal this certificate reports on, if any. */
    public UUID proposalOf(UUID ucId) {
        return jdbc.queryForObject("select proposal_id from utilisation_certificates where id = ?", UUID.class, ucId);
    }

    /** Legal name of the NGO (display name if the legal name is missing). */
    public String ngoName(UUID ngoId) {
        Map<String, Object> d = ngos.details(ngoId);
        Object n = d.get("legalName") != null ? d.get("legalName") : d.get("displayName");
        return n == null ? "the organisation" : n.toString();
    }

    private static String text(String s, int min, int max, String code, String msg) {
        String t = s == null ? "" : s.strip();
        if (t.length() < min || t.length() > max) throw ApiException.badRequest("INVALID_" + code, msg);
        return t;
    }
}
