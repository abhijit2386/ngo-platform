package org.ngoplatform.donation.api;

import jakarta.persistence.criteria.Predicate;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.ngoplatform.commons.audit.AuditRepository;
import org.ngoplatform.commons.config.InternalClients;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.security.AccessGuard;
import org.ngoplatform.commons.security.Membership;
import org.ngoplatform.commons.security.Permissions;
import org.ngoplatform.commons.web.PageResponse;
import org.ngoplatform.donation.app.DonationService;
import org.ngoplatform.donation.app.Reconciliation;
import org.ngoplatform.donation.domain.Donation;
import org.ngoplatform.donation.domain.Records;
import org.ngoplatform.donation.domain.Repos;
import org.ngoplatform.donation.ledger.Ledger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

/** PAY-001..020 for NGO members. */
@RestController
@RequestMapping("/api/v1/ngos/{ngoId}")
public class NgoDonationController {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final DonationService svc;
    private final Reconciliation recon;
    private final AccessGuard guard;
    private final Repos.Donations donations;
    private final Repos.Payments payments;
    private final Repos.Events events;
    private final Repos.Settings settings;
    private final Repos.Refunds refunds;
    private final Repos.Batches batches;
    private final Repos.Lines lines;
    private final Repos.Exceptions exceptions;
    private final Ledger ledger;
    private final AuditRepository auditRepo;
    private final RestClient registry;

    public NgoDonationController(DonationService svc, Reconciliation recon, AccessGuard guard, Repos.Donations donations,
                                 Repos.Payments payments, Repos.Events events, Repos.Settings settings, Repos.Refunds refunds,
                                 Repos.Batches batches, Repos.Lines lines, Repos.Exceptions exceptions, Ledger ledger,
                                 AuditRepository auditRepo, InternalClients clients,
                                 @Value("${ngo.services.ngo-registry}") String registryUrl) {
        this.svc = svc; this.recon = recon; this.guard = guard; this.donations = donations; this.payments = payments;
        this.events = events; this.settings = settings; this.refunds = refunds; this.batches = batches; this.lines = lines;
        this.exceptions = exceptions; this.ledger = ledger; this.auditRepo = auditRepo; this.registry = clients.forBaseUrl(registryUrl);
    }

    // ---------------- list / detail

    private Specification<Donation> spec(UUID ngoId, Donation.Status status, Donation.Channel channel, UUID campaignId,
                                         UUID donorId, LocalDate from, LocalDate to, String q) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("ngoId"), ngoId));
            if (status != null) p.add(cb.equal(root.get("status"), status));
            if (channel != null) p.add(cb.equal(root.get("channel"), channel));
            if (campaignId != null) p.add(cb.equal(root.get("campaignId"), campaignId));
            if (donorId != null) p.add(cb.equal(root.get("donorId"), donorId));
            if (from != null) p.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from.atStartOfDay(IST).toInstant()));
            if (to != null) p.add(cb.lessThan(root.get("createdAt"), to.plusDays(1).atStartOfDay(IST).toInstant()));
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase().replace("%", "\\%") + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("donorName")), like, '\\'), cb.like(cb.lower(root.get("donorEmail")), like, '\\')));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
    }

    @GetMapping("/donations")
    public PageResponse<Dtos.DonationView> list(@PathVariable UUID ngoId, @RequestParam(required = false) Donation.Status status,
                                                @RequestParam(required = false) Donation.Channel channel,
                                                @RequestParam(required = false) UUID campaignId,
                                                @RequestParam(required = false) UUID donorId,
                                                @RequestParam(required = false) LocalDate from,
                                                @RequestParam(required = false) LocalDate to,
                                                @RequestParam(required = false) String q,
                                                @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        guard.require(ngoId, Permissions.DONATION_VIEW);
        Page<Donation> p = donations.findAll(spec(ngoId, status, channel, campaignId, donorId, from, to, q),
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "createdAt")));
        return PageResponse.of(p, Dtos.DonationView::of);
    }

    @GetMapping("/donations/summary")
    public Map<String, Object> summary(@PathVariable UUID ngoId) {
        guard.require(ngoId, Permissions.DONATION_VIEW);
        return Map.of("netReceived", donations.netReceived(ngoId),
                "pendingRefunds", refunds.findByNgoIdOrderByCreatedAtDesc(ngoId).stream()
                        .filter(r -> r.getStatus() == Records.Refund.Status.REQUESTED).count(),
                "openExceptions", exceptions.findByNgoIdAndStatusOrderByCreatedAtDesc(ngoId, "OPEN").size());
    }

    @GetMapping("/donations/{id}")
    public Map<String, Object> detail(@PathVariable UUID ngoId, @PathVariable UUID id) {
        guard.require(ngoId, Permissions.DONATION_VIEW);
        Donation d = donations.findByIdAndNgoId(id, ngoId).orElseThrow(() -> ApiException.notFound("Donation"));
        return Map.of("donation", Dtos.DonationView.of(d),
                "payments", payments.findByDonationIdOrderByCreatedAtDesc(id).stream().map(p -> Map.of(
                        "provider", p.getProvider(), "orderId", p.getProviderOrderId(),
                        "paymentId", p.getProviderPaymentId() == null ? "" : p.getProviderPaymentId(),
                        "status", p.getStatus(), "createdAt", p.getCreatedAt())).toList(),
                "events", events.findByDonationIdOrderByReceivedAtAsc(id).stream().map(e -> Map.of("type", e.getEventType(),
                        "outcome", e.getOutcome(), "signatureValid", e.isSignatureValid(), "at", e.getReceivedAt())).toList(),
                "refunds", refunds.findByDonationIdOrderByCreatedAtDesc(id).stream().map(Dtos.RefundView::of).toList(),
                "audit", auditRepo.findByTenantIdAndResourceTypeAndResourceIdOrderByCreatedAtDesc(ngoId, "DONATION", id.toString(),
                        PageRequest.of(0, 50)).map(a -> Map.of("action", a.getAction(), "at", a.getCreatedAt())).getContent());
    }

    @PostMapping("/donations/{id}/refresh")
    public Dtos.DonationView refresh(@PathVariable UUID ngoId, @PathVariable UUID id) {
        guard.require(ngoId, Permissions.DONATION_VIEW);
        donations.findByIdAndNgoId(id, ngoId).orElseThrow(() -> ApiException.notFound("Donation"));
        return Dtos.DonationView.of(svc.refresh(id));
    }

    // ---------------- offline donations, evidence, corrections

    private DonationService.OfflineRequest map(Dtos.OfflineRequest r) {
        return new DonationService.OfflineRequest(r.donorId(), r.donorName(), r.donorEmail(), r.donorPhone(), r.donorPan(),
                r.amount(), r.channel(), r.reference(), r.receivedOn(), r.campaignId(), r.projectId(), r.purpose(), r.wants80g());
    }

    @PostMapping("/donations/offline")
    @ResponseStatus(HttpStatus.CREATED)
    public Dtos.DonationView offline(@PathVariable UUID ngoId, @Valid @RequestBody Dtos.OfflineRequest req) {
        Membership m = guard.require(ngoId, Permissions.DONATION_RECORD);
        guard.requireOperational(ngoId);
        return Dtos.DonationView.of(svc.recordOffline(ngoId, m.userId(), map(req)));
    }

    @PostMapping(value = "/donations/{id}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Dtos.DonationView evidence(@PathVariable UUID ngoId, @PathVariable UUID id, @RequestPart("file") MultipartFile file) throws IOException {
        Membership m = guard.require(ngoId, Permissions.DONATION_RECORD);
        return Dtos.DonationView.of(svc.attachEvidence(ngoId, m.userId(), id, file.getBytes(), file.getOriginalFilename()));
    }

    @PostMapping("/donations/{id}/correction")
    public Dtos.DonationView correct(@PathVariable UUID ngoId, @PathVariable UUID id, @Valid @RequestBody Dtos.Correction req) {
        Membership m = guard.require(ngoId, Permissions.RECONCILIATION_MANAGE);
        return Dtos.DonationView.of(svc.correct(ngoId, m.userId(), id, map(req.corrected()), req.reason()));
    }

    // ---------------- refunds

    @PostMapping("/donations/{id}/refunds")
    @ResponseStatus(HttpStatus.CREATED)
    public Dtos.RefundView requestRefund(@PathVariable UUID ngoId, @PathVariable UUID id, @Valid @RequestBody Dtos.RefundRequest req) {
        Membership m = guard.require(ngoId, Permissions.REFUND_REQUEST);
        return Dtos.RefundView.of(svc.requestRefund(ngoId, m.userId(), id, req.amount(), req.reason()));
    }

    @GetMapping("/refunds")
    public List<Dtos.RefundView> refunds(@PathVariable UUID ngoId) {
        guard.require(ngoId, Permissions.DONATION_VIEW);
        return refunds.findByNgoIdOrderByCreatedAtDesc(ngoId).stream().map(Dtos.RefundView::of).toList();
    }

    @PostMapping("/refunds/{refundId}/decision")
    public Dtos.RefundView decide(@PathVariable UUID ngoId, @PathVariable UUID refundId, @Valid @RequestBody Dtos.RefundDecision req) {
        Membership m = guard.require(ngoId, Permissions.REFUND_APPROVE);
        return Dtos.RefundView.of(svc.decideRefund(ngoId, m.userId(), refundId, req.approve(),
                req.scenario() == null || req.scenario().isBlank() ? null : req.scenario()));
    }

    // ---------------- payment settings (NGO-012 / SET-012)

    @GetMapping("/payment-settings")
    public Map<String, Object> getSettings(@PathVariable UUID ngoId) {
        guard.member(ngoId);
        return settings.findById(ngoId).<Map<String, Object>>map(s -> Map.of("provider", s.getProvider(),
                "merchantReference", s.getMerchantReference(), "status", s.getStatus(),
                "guestCheckoutAllowed", s.isGuestCheckoutAllowed(), "minAmount", s.getMinAmount(), "maxAmount", s.getMaxAmount(),
                "receiptPrefix", s.getReceiptPrefix(), "version", s.getVersion()))
                .orElse(Map.of("status", "NOT_CONFIGURED"));
    }

    /**
     * Saving settings with the MOCK provider activates immediately (development). Real providers stay PENDING until
     * the provider confirms merchant onboarding (provider-specific integration, documented blocker).
     */
    @PutMapping("/payment-settings")
    @Transactional
    public Map<String, Object> saveSettings(@PathVariable UUID ngoId, @Valid @RequestBody Dtos.SettingsRequest req) {
        Membership m = guard.requireAdmin(ngoId);
        if (req.maxAmount().compareTo(req.minAmount()) < 0) throw ApiException.badRequest("INVALID_RANGE", "Maximum must be ≥ minimum");
        Records.PaymentSettings s = settings.findById(ngoId).orElseGet(() -> new Records.PaymentSettings(ngoId));
        String status = "MOCK".equals(req.provider()) ? "ACTIVE" : "PENDING";
        s.update(req.provider(), req.merchantReference().trim(), status, req.guestCheckoutAllowed(), req.minAmount(),
                req.maxAmount(), req.receiptPrefix(), m.userId());
        settings.save(s);
        try {
            registry.put().uri("/internal/v1/ngos/{id}/payment-onboarding/{s}", ngoId, status).retrieve().toBodilessEntity();
        } catch (Exception e) {
            // Registry shows onboarding status only; donation eligibility does not depend on this call succeeding.
        }
        return getSettings(ngoId);
    }

    // ---------------- reconciliation & ledger

    @PostMapping("/reconciliation/runs")
    public Map<String, Object> runRecon(@PathVariable UUID ngoId) {
        Membership m = guard.require(ngoId, Permissions.RECONCILIATION_MANAGE);
        Records.ReconRun r = recon.run(ngoId, m.userId());
        return Map.of("id", r.getId(), "batches", r.getBatches(), "matched", r.getMatched(), "exceptions", r.getExceptions());
    }

    @GetMapping("/reconciliation/settlements")
    public List<Map<String, Object>> settlements(@PathVariable UUID ngoId) {
        guard.require(ngoId, Permissions.DONATION_VIEW);
        return batches.findByNgoIdOrderBySettledOnDesc(ngoId).stream().map(b -> Map.<String, Object>of("id", b.getId(),
                "settlementId", b.getProviderSettlementId(), "settledOn", b.getSettledOn(), "gross", b.getGrossAmount(),
                "fee", b.getFeeAmount(), "tax", b.getTaxAmount(), "net", b.getNetAmount(),
                "bankReference", b.getBankReference() == null ? "" : b.getBankReference(), "status", b.getStatus())).toList();
    }

    @GetMapping("/reconciliation/settlements/{batchId}")
    public List<Map<String, Object>> settlementLines(@PathVariable UUID ngoId, @PathVariable UUID batchId) {
        guard.require(ngoId, Permissions.DONATION_VIEW);
        batches.findByIdAndNgoId(batchId, ngoId).orElseThrow(() -> ApiException.notFound("Settlement"));
        return lines.findByBatchId(batchId).stream().map(l -> Map.<String, Object>of("paymentId", l.getProviderPaymentId(),
                "donationId", l.getDonationId() == null ? "" : l.getDonationId().toString(), "gross", l.getGrossAmount(),
                "fee", l.getFeeAmount(), "tax", l.getTaxAmount(), "net", l.getNetAmount(), "matched", l.isMatched())).toList();
    }

    @GetMapping("/reconciliation/exceptions")
    public List<Dtos.ExceptionView> openExceptions(@PathVariable UUID ngoId, @RequestParam(defaultValue = "OPEN") String status) {
        guard.require(ngoId, Permissions.DONATION_VIEW);
        return exceptions.findByNgoIdAndStatusOrderByCreatedAtDesc(ngoId, status).stream().map(Dtos.ExceptionView::of).toList();
    }

    @PostMapping("/reconciliation/exceptions/{id}/resolve")
    public Dtos.ExceptionView resolve(@PathVariable UUID ngoId, @PathVariable UUID id, @Valid @RequestBody Dtos.ExceptionResolution r) {
        Membership m = guard.require(ngoId, Permissions.RECONCILIATION_MANAGE);
        return Dtos.ExceptionView.of(recon.resolve(ngoId, m.userId(), id, r.ignore(), r.note()));
    }

    @GetMapping("/ledger/balances")
    public List<Map<String, Object>> balances(@PathVariable UUID ngoId) {
        guard.require(ngoId, Permissions.RECONCILIATION_MANAGE);
        return ledger.balances(ngoId);
    }

    @GetMapping("/ledger/entries")
    public List<Map<String, Object>> entries(@PathVariable UUID ngoId, @RequestParam(defaultValue = "100") int limit) {
        guard.require(ngoId, Permissions.RECONCILIATION_MANAGE);
        return ledger.entries(ngoId, limit);
    }

    /** PAY-019: CSV export (never premium-gated). */
    @GetMapping(value = "/donations/export", produces = "text/csv")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> export(@PathVariable UUID ngoId, @RequestParam(required = false) LocalDate from,
                                         @RequestParam(required = false) LocalDate to) {
        guard.require(ngoId, Permissions.DONATION_VIEW);
        guard.require(ngoId, Permissions.REPORT_EXPORT);
        StringBuilder sb = new StringBuilder("donation_id,created_at,paid_at,donor_name,donor_email,amount,refunded,channel,status,campaign_id,reference\n");
        int page = 0;
        Page<Donation> p;
        do {
            p = donations.findAll(spec(ngoId, null, null, null, null, from, to, null), PageRequest.of(page++, 500, Sort.by("createdAt")));
            for (Donation d : p) {
                sb.append(String.join(",", d.getId().toString(), d.getCreatedAt().toString(),
                        d.getPaidAt() == null ? "" : d.getPaidAt().toString(), cell(d.getDonorName()), cell(d.getDonorEmail()),
                        d.getAmount().toPlainString(), d.getRefundedAmount().toPlainString(), d.getChannel().name(),
                        d.getStatus().name(), d.getCampaignId() == null ? "" : d.getCampaignId().toString(),
                        cell(d.getOfflineReference()))).append('\n');
            }
        } while (p.hasNext());
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"donations.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8)).body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String cell(String s) {
        if (s == null) return "";
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    @SuppressWarnings("unused")
    private static Instant now() { return Instant.now(); }
}
