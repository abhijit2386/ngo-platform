package org.ngoplatform.receipt.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.security.AccessGuard;
import org.ngoplatform.commons.security.CurrentUser;
import org.ngoplatform.commons.security.Membership;
import org.ngoplatform.commons.security.Permissions;
import org.ngoplatform.commons.web.PageResponse;
import org.ngoplatform.receipt.app.ReceiptService;
import org.ngoplatform.receipt.app.TemplateDefinition;
import org.ngoplatform.receipt.app.TemplateService;
import org.ngoplatform.receipt.domain.Receipt;
import org.ngoplatform.receipt.domain.Repos;
import org.ngoplatform.receipt.domain.Template;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** REC-001..022 and donor receipt access (DAPP-019..021). */
@RestController
public class ReceiptController {
    public record ReceiptView(UUID id, UUID donationId, String receiptNumber, BigDecimal amount, String status, boolean tax80g,
                              UUID templateId, Integer templateVersion, int attempts, String lastError, UUID reissueOf,
                              String voidReason, Instant issuedAt, Instant deliveredAt, Instant createdAt) {
        static ReceiptView of(Receipt r) {
            return new ReceiptView(r.getId(), r.getDonationId(), r.getReceiptNumber(), r.getAmount(), r.getStatus().name(),
                    r.isTax80g(), r.getTemplateId(), r.getTemplateVersion(), r.getAttempts(), r.getLastError(), r.getReissueOf(),
                    r.getVoidReason(), r.getIssuedAt(), r.getDeliveredAt(), r.getCreatedAt());
        }
    }

    public record TemplateView(UUID id, String name, String kind, String status, boolean active, int currentVersion,
                               Integer publishedVersion, TemplateDefinition definition, boolean hasBackground, long version) {}

    public record CreateTemplate(@NotBlank @Size(max = 80) String name, @NotNull TemplateDefinition definition) {}
    public record SaveTemplate(@NotNull TemplateDefinition definition, long version) {}
    public record Reason(@NotBlank @Size(max = 300) String reason) {}
    public record Decision(boolean approve, @Size(max = 500) String comment) {}

    private final ReceiptService receipts;
    private final TemplateService templates;
    private final AccessGuard guard;
    private final Repos.Receipts repo;
    private final Repos.Headers headers;
    private final Repos.Versions versions;
    private final Repos.Approvals approvals;
    private final JdbcTemplate jdbc;

    public ReceiptController(ReceiptService receipts, TemplateService templates, AccessGuard guard, Repos.Receipts repo,
                             Repos.Headers headers, Repos.Versions versions, Repos.Approvals approvals, JdbcTemplate jdbc,
                             ObjectMapper mapper) {
        this.receipts = receipts; this.templates = templates; this.guard = guard; this.repo = repo; this.headers = headers;
        this.versions = versions; this.approvals = approvals; this.jdbc = jdbc;
    }

    private static ResponseEntity<byte[]> pdf(byte[] bytes, String name) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + name.replace('/', '-') + ".pdf\"").body(bytes);
    }

    // ---------------- NGO receipts

    @GetMapping("/api/v1/ngos/{ngoId}/receipts")
    public PageResponse<ReceiptView> list(@PathVariable UUID ngoId, @RequestParam(required = false) Receipt.Status status,
                                          @RequestParam(defaultValue = "0") int page) {
        guard.require(ngoId, Permissions.RECEIPT_VIEW);
        var p = status == null ? repo.findByNgoIdOrderByCreatedAtDesc(ngoId, PageRequest.of(page, 20))
                : repo.findByNgoIdAndStatusOrderByCreatedAtDesc(ngoId, status, PageRequest.of(page, 20));
        return PageResponse.of(p, ReceiptView::of);
    }

    @GetMapping("/api/v1/ngos/{ngoId}/receipts/{id}")
    public Map<String, Object> detail(@PathVariable UUID ngoId, @PathVariable UUID id) {
        guard.require(ngoId, Permissions.RECEIPT_VIEW);
        Receipt r = repo.findByIdAndNgoId(id, ngoId).orElseThrow(() -> ApiException.notFound("Receipt"));
        return Map.of("receipt", ReceiptView.of(r), "history",
                jdbc.queryForList("select event, detail, created_at from receipt_events where receipt_id = ? order by created_at", id));
    }

    @GetMapping("/api/v1/ngos/{ngoId}/receipts/by-donation/{donationId}")
    public List<ReceiptView> byDonation(@PathVariable UUID ngoId, @PathVariable UUID donationId) {
        guard.require(ngoId, Permissions.RECEIPT_VIEW);
        return repo.findByDonationIdOrderByCreatedAtDesc(donationId).stream().filter(r -> r.getNgoId().equals(ngoId)).map(ReceiptView::of).toList();
    }

    @GetMapping("/api/v1/ngos/{ngoId}/receipts/{id}/pdf")
    public ResponseEntity<byte[]> ngoPdf(@PathVariable UUID ngoId, @PathVariable UUID id) {
        guard.require(ngoId, Permissions.RECEIPT_VIEW);
        Receipt r = repo.findByIdAndNgoId(id, ngoId).orElseThrow(() -> ApiException.notFound("Receipt"));
        return pdf(receipts.pdfBytes(r), r.getReceiptNumber());
    }

    @PostMapping("/api/v1/ngos/{ngoId}/receipts/{id}/reissue")
    public ReceiptView reissue(@PathVariable UUID ngoId, @PathVariable UUID id, @Valid @RequestBody Reason req) {
        Membership m = guard.require(ngoId, Permissions.RECEIPT_MANAGE);
        return ReceiptView.of(receipts.reissue(ngoId, m.userId(), id, req.reason()));
    }

    @PostMapping("/api/v1/ngos/{ngoId}/receipts/{id}/retry")
    public ReceiptView retry(@PathVariable UUID ngoId, @PathVariable UUID id) {
        guard.require(ngoId, Permissions.RECEIPT_MANAGE);
        return ReceiptView.of(receipts.retry(ngoId, id));
    }

    // ---------------- donor access

    @GetMapping("/api/v1/receipts/mine")
    public PageResponse<ReceiptView> mine(@RequestParam(defaultValue = "0") int page) {
        CurrentUser u = guard.user();
        return PageResponse.of(repo.findByDonorUserIdOrderByCreatedAtDesc(u.userId(), PageRequest.of(page, 20)), ReceiptView::of);
    }

    @GetMapping("/api/v1/receipts/{id}/pdf")
    public ResponseEntity<byte[]> donorPdf(@PathVariable UUID id) {
        CurrentUser u = guard.user();
        Receipt r = repo.findById(id).filter(x -> u.userId().equals(x.getDonorUserId())).orElseThrow(() -> ApiException.notFound("Receipt"));
        return pdf(receipts.pdfBytes(r), r.getReceiptNumber());
    }

    /** Guest retrieval through the signed, expiring link sent by email (spec §12). */
    @GetMapping("/api/v1/public/receipts/{id}/pdf")
    public ResponseEntity<byte[]> guestPdf(@PathVariable UUID id, @RequestParam long exp, @RequestParam String sig) {
        if (!receipts.verifyLink(id, exp, sig)) throw ApiException.forbidden("LINK_INVALID", "This receipt link is invalid or expired");
        Receipt r = repo.findById(id).orElseThrow(() -> ApiException.notFound("Receipt"));
        return pdf(receipts.pdfBytes(r), r.getReceiptNumber());
    }

    // ---------------- templates

    TemplateView view(Template.Header h) {
        var v = versions.findByTemplateIdAndVersionNo(h.getId(), h.getCurrentVersion()).orElseThrow();
        return new TemplateView(h.getId(), h.getName(), h.getKind().name(), h.getStatus().name(), h.isActive(), h.getCurrentVersion(),
                h.getPublishedVersion(), templates.definition(h.getId(), h.getCurrentVersion()), v.getBackgroundKey() != null, h.getVersion());
    }

    @GetMapping("/api/v1/ngos/{ngoId}/receipt-templates")
    public List<TemplateView> templates(@PathVariable UUID ngoId) {
        guard.require(ngoId, Permissions.RECEIPT_VIEW);
        return headers.findByNgoIdOrderByUpdatedAtDesc(ngoId).stream().map(this::view).toList();
    }

    @PostMapping("/api/v1/ngos/{ngoId}/receipt-templates")
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateView create(@PathVariable UUID ngoId, @Valid @RequestBody CreateTemplate req) {
        Membership m = guard.require(ngoId, Permissions.RECEIPT_TEMPLATE_DESIGN);
        return view(templates.create(ngoId, m.userId(), req.name(), req.definition()));
    }

    @PutMapping("/api/v1/ngos/{ngoId}/receipt-templates/{id}")
    public TemplateView save(@PathVariable UUID ngoId, @PathVariable UUID id, @Valid @RequestBody SaveTemplate req) {
        Membership m = guard.require(ngoId, Permissions.RECEIPT_TEMPLATE_DESIGN);
        return view(templates.saveDraft(ngoId, m.userId(), id, req.definition(), req.version()));
    }

    @PostMapping(value = "/api/v1/ngos/{ngoId}/receipt-templates/{id}/background", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TemplateView background(@PathVariable UUID ngoId, @PathVariable UUID id, @RequestPart("file") MultipartFile file) throws IOException {
        Membership m = guard.require(ngoId, Permissions.RECEIPT_TEMPLATE_DESIGN);
        return view(templates.uploadBackground(ngoId, m.userId(), id, file.getBytes()));
    }

    @GetMapping("/api/v1/ngos/{ngoId}/receipt-templates/{id}/validation")
    public TemplateDefinition.Result validate(@PathVariable UUID ngoId, @PathVariable UUID id) {
        guard.require(ngoId, Permissions.RECEIPT_TEMPLATE_DESIGN);
        return templates.validate(ngoId, id);
    }

    @GetMapping("/api/v1/ngos/{ngoId}/receipt-templates/{id}/preview")
    public ResponseEntity<byte[]> preview(@PathVariable UUID ngoId, @PathVariable UUID id) {
        guard.require(ngoId, Permissions.RECEIPT_TEMPLATE_DESIGN);
        return pdf(templates.preview(ngoId, id), "preview");
    }

    @PostMapping("/api/v1/ngos/{ngoId}/receipt-templates/{id}/submit")
    public TemplateView submit(@PathVariable UUID ngoId, @PathVariable UUID id) {
        Membership m = guard.require(ngoId, Permissions.RECEIPT_TEMPLATE_DESIGN);
        return view(templates.submit(ngoId, m.userId(), id));
    }

    @PostMapping("/api/v1/ngos/{ngoId}/receipt-templates/{id}/decision")
    public TemplateView decide(@PathVariable UUID ngoId, @PathVariable UUID id, @Valid @RequestBody Decision req) {
        Membership m = guard.require(ngoId, Permissions.RECEIPT_TEMPLATE_APPROVE);
        return view(templates.decide(ngoId, m.userId(), id, req.approve(), req.comment()));
    }

    @GetMapping("/api/v1/ngos/{ngoId}/receipt-templates/{id}/versions")
    public List<Map<String, Object>> history(@PathVariable UUID ngoId, @PathVariable UUID id) {
        guard.require(ngoId, Permissions.RECEIPT_VIEW);
        templates.load(ngoId, id);
        return versions.findByTemplateIdOrderByVersionNoDesc(id).stream().map(v -> Map.<String, Object>of("version", v.getVersionNo(),
                "createdBy", v.getCreatedBy(), "createdAt", v.getCreatedAt())).toList();
    }

    @PostMapping("/api/v1/ngos/{ngoId}/receipt-templates/{id}/rollback/{version}")
    public TemplateView rollback(@PathVariable UUID ngoId, @PathVariable UUID id, @PathVariable int version) {
        Membership m = guard.require(ngoId, Permissions.RECEIPT_TEMPLATE_DESIGN);
        return view(templates.rollback(ngoId, m.userId(), id, version));
    }

    @PostMapping("/api/v1/ngos/{ngoId}/receipt-templates/{id}/duplicate")
    public TemplateView duplicate(@PathVariable UUID ngoId, @PathVariable UUID id, @Valid @RequestBody Reason name) {
        Membership m = guard.require(ngoId, Permissions.RECEIPT_TEMPLATE_DESIGN);
        return view(templates.duplicate(ngoId, m.userId(), id, name.reason()));
    }

    @PostMapping("/api/v1/ngos/{ngoId}/receipt-templates/{id}/archive")
    public TemplateView archive(@PathVariable UUID ngoId, @PathVariable UUID id) {
        Membership m = guard.require(ngoId, Permissions.RECEIPT_TEMPLATE_DESIGN);
        return view(templates.archive(ngoId, m.userId(), id));
    }

    @GetMapping("/internal/v1/ngos/{ngoId}/template-usage")
    public Map<String, Long> usage(@PathVariable UUID ngoId) {
        return Map.of("customTemplates", headers.countByNgoIdAndStatusNot(ngoId, Template.Header.Status.ARCHIVED));
    }
}
