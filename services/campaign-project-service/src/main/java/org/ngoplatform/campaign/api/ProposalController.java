package org.ngoplatform.campaign.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.ngoplatform.campaign.app.CampaignService;
import org.ngoplatform.campaign.app.ProposalRules;
import org.ngoplatform.campaign.app.ProposalService;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.security.AccessGuard;
import org.ngoplatform.commons.security.Entitlements;
import org.ngoplatform.commons.security.Membership;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CSR funding proposals (Enterprise: CSR_PROPOSALS). Administrators only. Writing needs the flag; reading, notes on
 * open proposals and closing them stay possible after a downgrade, so no proposal is ever stranded.
 */
@RestController
public class ProposalController {
    public record CreateRequest(UUID campaignId, String funderName, BigDecimal askAmount) {}
    public record UpdateRequest(String title, String funderName, String funderContact, String funderEmail, String scheduleVii,
                                BigDecimal askAmount, Integer durationMonths, Integer beneficiaries, String location, String need,
                                String objectives, String activities, String outcomes, String monitoring, String contactName,
                                String contactDetails, UUID packId, String packTitle, String packUrl, Instant packExpiresAt, Long version) {
        ProposalService.Input input() {
            return new ProposalService.Input(title, funderName, funderContact, funderEmail, scheduleVii, askAmount, durationMonths,
                    beneficiaries, location, need, objectives, activities, outcomes, monitoring, contactName, contactDetails, packId,
                    packTitle, packUrl, packExpiresAt);
        }
    }
    public record SendRequest(LocalDate sentOn, LocalDate followUpOn, Long version) {}
    public record NoteRequest(String note, LocalDate followUpOn) {}
    public record CloseRequest(String outcome, BigDecimal amount, LocalDate on, String reference, String reason) {}

    private static final String BASE = "/api/v1/ngos/{ngoId}/csr-proposals";
    private static final String NEEDS = "CSR proposals are part of the Enterprise plan";

    private final ProposalService proposals;
    private final CampaignService campaigns;
    private final AccessGuard guard;

    public ProposalController(ProposalService proposals, CampaignService campaigns, AccessGuard guard) {
        this.proposals = proposals; this.campaigns = campaigns; this.guard = guard;
    }

    @GetMapping(BASE)
    public Map<String, Object> list(@PathVariable UUID ngoId, @RequestParam(required = false) UUID campaignId) {
        guard.requireAdmin(ngoId);
        List<Map<String, Object>> items = new ArrayList<>();
        Map<UUID, String> titles = new LinkedHashMap<>();
        for (ProposalService.Proposal p : proposals.list(ngoId, campaignId)) items.add(summary(p, titles));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", guard.entitlements(ngoId).flag(Entitlements.CSR_PROPOSALS));
        m.put("summary", proposals.summary(ngoId));
        m.put("items", items);
        List<Map<String, String>> areas = new ArrayList<>();
        ProposalRules.SCHEDULE_VII.forEach((k, v) -> areas.add(Map.of("code", k, "label", v)));
        m.put("scheduleVii", areas);
        return m;
    }

    @PostMapping(BASE)
    public Map<String, Object> create(@PathVariable UUID ngoId, @RequestBody CreateRequest req) {
        Membership m = admin(ngoId);
        return detail(proposals.create(ngoId, m.userId(), req.campaignId(), req.funderName(), req.askAmount()));
    }

    @GetMapping(BASE + "/{id}")
    public Map<String, Object> get(@PathVariable UUID ngoId, @PathVariable UUID id) {
        guard.requireAdmin(ngoId);
        return detail(proposals.get(ngoId, id));
    }

    @PutMapping(BASE + "/{id}")
    public Map<String, Object> update(@PathVariable UUID ngoId, @PathVariable UUID id, @RequestBody UpdateRequest req) {
        Membership m = admin(ngoId);
        return detail(proposals.update(ngoId, m.userId(), id, req.input(), req.version() == null ? -1 : req.version()));
    }

    @PostMapping(BASE + "/{id}/send")
    public Map<String, Object> send(@PathVariable UUID ngoId, @PathVariable UUID id, @RequestBody SendRequest req) {
        Membership m = admin(ngoId);
        return detail(proposals.send(ngoId, m.userId(), id, req.sentOn(), req.followUpOn(), req.version() == null ? -1 : req.version()));
    }

    @PostMapping(BASE + "/{id}/notes")
    public Map<String, Object> note(@PathVariable UUID ngoId, @PathVariable UUID id, @RequestBody NoteRequest req) {
        Membership m = guard.requireAdmin(ngoId);
        return detail(proposals.note(ngoId, m.userId(), id, req.note(), req.followUpOn()));
    }

    /** Recording the outcome is always possible (also after a downgrade). */
    @PostMapping(BASE + "/{id}/close")
    public Map<String, Object> close(@PathVariable UUID ngoId, @PathVariable UUID id, @RequestBody CloseRequest req) {
        Membership m = guard.requireAdmin(ngoId);
        return detail(proposals.close(ngoId, m.userId(), id, req.outcome(), req.amount(), req.on(), req.reference(), req.reason()));
    }

    @PostMapping(BASE + "/{id}/copy")
    public Map<String, Object> copy(@PathVariable UUID ngoId, @PathVariable UUID id) {
        Membership m = admin(ngoId);
        return detail(proposals.copy(ngoId, m.userId(), id));
    }

    @GetMapping(BASE + "/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID ngoId, @PathVariable UUID id) {
        guard.requireAdmin(ngoId);
        ProposalService.Proposal p = proposals.get(ngoId, id);
        String name = p.number().replace('/', '-') + (p.pdfKey() == null ? "-draft" : "") + ".pdf";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + name + "\"")
                .header("X-Content-Type-Options", "nosniff").body(proposals.pdf(p));
    }

    private Membership admin(UUID ngoId) {
        Membership m = guard.requireAdmin(ngoId);
        guard.requireFlag(ngoId, Entitlements.CSR_PROPOSALS, NEEDS);
        return m;
    }

    private Map<String, Object> summary(ProposalService.Proposal p, Map<UUID, String> titles) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("number", p.number());
        m.put("title", p.title());
        m.put("campaignId", p.campaignId());
        m.put("campaignTitle", titles.computeIfAbsent(p.campaignId(), c -> title(p)));
        m.put("funderName", p.funderName());
        m.put("askAmount", p.askAmount());
        m.put("status", p.status());
        m.put("sentOn", p.sentOn());
        m.put("followUpOn", p.followUpOn());
        m.put("followUpDue", "SENT".equals(p.status()) && p.followUpOn() != null && !p.followUpOn().isAfter(campaigns.today()));
        m.put("fundedAmount", p.fundedAmount());
        m.put("createdAt", p.createdAt());
        return m;
    }

    private String title(ProposalService.Proposal p) {
        try { return campaigns.load(p.ngoId(), p.campaignId()).getTitle(); } catch (ApiException e) { return ""; }
    }

    private Map<String, Object> detail(ProposalService.Proposal p) {
        Map<String, Object> m = summary(p, new LinkedHashMap<>());
        m.put("fiscalYear", p.fiscalYear());
        m.put("funderContact", p.funderContact());
        m.put("funderEmail", p.funderEmail());
        m.put("scheduleVii", p.scheduleVii());
        m.put("scheduleViiLabel", ProposalRules.SCHEDULE_VII.get(p.scheduleVii()));
        m.put("durationMonths", p.durationMonths());
        m.put("beneficiaries", p.beneficiaries());
        m.put("location", p.location());
        m.put("need", p.need());
        m.put("objectives", p.objectives());
        m.put("activities", p.activities());
        m.put("outcomes", p.outcomes());
        m.put("monitoring", p.monitoring());
        m.put("contactName", p.contactName());
        m.put("contactDetails", p.contactDetails());
        m.put("packId", p.packId());
        m.put("packTitle", p.packTitle());
        m.put("packUrl", p.packUrl());
        m.put("packExpiresAt", p.packExpiresAt());
        m.put("missing", "DRAFT".equals(p.status()) ? ProposalRules.missingForSending(p.need(), p.objectives(), p.activities(), p.outcomes()) : List.of());
        m.put("pdfSha256", p.pdfSha256());
        m.put("fundedOn", p.fundedOn());
        m.put("funderReference", p.funderReference());
        m.put("closeReason", p.closeReason());
        m.put("closedAt", p.closedAt());
        m.put("version", p.version());
        m.put("progress", proposals.progress(p.ngoId(), p.campaignId()));
        m.put("events", proposals.events(p.id()));
        m.put("certificates", proposals.certificates(p.id()));
        return m;
    }
}
