package org.ngoplatform.receipt.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.security.AccessGuard;
import org.ngoplatform.commons.security.CurrentUser;
import org.ngoplatform.commons.security.Membership;
import org.ngoplatform.commons.security.Permissions;
import org.ngoplatform.commons.web.PageResponse;
import org.ngoplatform.receipt.app.ReceiptQueries;
import org.ngoplatform.receipt.app.ReceiptRules;
import org.ngoplatform.receipt.app.ReceiptService;
import org.ngoplatform.receipt.app.ReceiptSettings;
import org.ngoplatform.receipt.app.TemplateDefinition;
import org.ngoplatform.receipt.app.TemplateService;
import org.ngoplatform.receipt.domain.Receipt;
import org.ngoplatform.receipt.domain.Repos;
import org.ngoplatform.receipt.domain.Template;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.HtmlUtils;

/** REC-001..014 (NGO receipts, settings, signatories, templates), DAPP-019..021 (donor), public download + verification. */
@RestController
public class ReceiptController {
    public record TemplateView(UUID id, String name, String kind, String status, boolean active, int currentVersion,
                               Integer publishedVersion, TemplateDefinition definition, boolean hasBackground, long version) {}

    public record CreateTemplate(@NotBlank @Size(max = 80) String name, TemplateDefinition definition) {}
    public record SaveTemplate(@NotNull TemplateDefinition definition, long version) {}
    public record Reason(@NotBlank @Size(max = 300) String reason) {}
    public record Decision(boolean approve, @Size(max = 500) String comment) {}
    public record SettingsRequest(String prefix, String reg12aNumber, String thankYouNote, long version) {}
    public record SignatoryRequest(String name, String designation, Integer position, Long version) {}

    private final ReceiptService receipts;
    private final ReceiptQueries queries;
    private final ReceiptSettings settings;
    private final TemplateService templates;
    private final AccessGuard guard;
    private final Repos.Receipts repo;
    private final Repos.Headers headers;
    private final Repos.Versions versions;

    public ReceiptController(ReceiptService receipts, ReceiptQueries queries, ReceiptSettings settings, TemplateService templates,
                             AccessGuard guard, Repos.Receipts repo, Repos.Headers headers, Repos.Versions versions, ObjectMapper mapper) {
        this.receipts = receipts; this.queries = queries; this.settings = settings; this.templates = templates; this.guard = guard;
        this.repo = repo; this.headers = headers; this.versions = versions;
    }

    private static ResponseEntity<byte[]> pdf(byte[] bytes, String name, boolean attachment) {
        String file = (name == null ? "receipt" : name.replace('/', '-')) + ".pdf";
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, (attachment ? "attachment" : "inline") + "; filename=\"" + file + "\"").body(bytes);
    }

    private Receipt ngoReceipt(UUID ngoId, UUID id) {
        return repo.findByIdAndNgoId(id, ngoId).orElseThrow(() -> ApiException.notFound("Receipt"));
    }

    // ---------------- NGO receipts (REC-001..007)

    @GetMapping("/api/v1/ngos/{ngoId}/receipts")
    public PageResponse<ReceiptQueries.Item> list(@PathVariable UUID ngoId, @RequestParam(required = false) String q,
                                                  @RequestParam(required = false) String status, @RequestParam(required = false) String fy,
                                                  @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        guard.require(ngoId, Permissions.RECEIPT_VIEW);
        return queries.search(ngoId, q, status, fy, Math.max(0, page), Math.min(100, Math.max(1, size)));
    }

    @GetMapping("/api/v1/ngos/{ngoId}/receipts/summary")
    public Map<String, Object> summary(@PathVariable UUID ngoId, @RequestParam(required = false) String fy) {
        guard.require(ngoId, Permissions.RECEIPT_VIEW);
        String year = fy != null && fy.matches("\\d{4}-\\d{2}") ? fy : ReceiptRules.fiscalYear(LocalDate.now(ReceiptService.IST));
        Map<String, Object> m = queries.summary(ngoId, year);
        m.put("prefix", settings.get(ngoId).prefix());
        return m;
    }

    @GetMapping("/api/v1/ngos/{ngoId}/receipts/{id}")
    public Map<String, Object> detail(@PathVariable UUID ngoId, @PathVariable UUID id) {
        guard.require(ngoId, Permissions.RECEIPT_VIEW);
        ngoReceipt(ngoId, id);
        Map<String, Object> m = new LinkedHashMap<>();
        ReceiptQueries.Item it = queries.byId(id);
        m.put("receipt", it);
        m.put("history", queries.history(id));
        ReceiptQueries.Item replacement = queries.replacement(id);
        if (replacement != null) m.put("replacedBy", Map.of("id", replacement.id(), "receiptNumber", String.valueOf(replacement.receiptNumber())));
        if (it.reissueOf() != null) {
            ReceiptQueries.Item old = queries.byId(it.reissueOf());
            if (old != null) m.put("replaces", Map.of("id", old.id(), "receiptNumber", String.valueOf(old.receiptNumber())));
        }
        return m;
    }

    @GetMapping("/api/v1/ngos/{ngoId}/receipts/by-donation/{donationId}")
    public List<ReceiptQueries.Item> byDonation(@PathVariable UUID ngoId, @PathVariable UUID donationId) {
        guard.require(ngoId, Permissions.RECEIPT_VIEW);
        return repo.findByDonationIdOrderByCreatedAtDesc(donationId).stream().filter(r -> r.getNgoId().equals(ngoId))
                .map(r -> queries.byId(r.getId())).toList();
    }

    @GetMapping("/api/v1/ngos/{ngoId}/receipts/{id}/pdf")
    public ResponseEntity<byte[]> ngoPdf(@PathVariable UUID ngoId, @PathVariable UUID id) {
        guard.require(ngoId, Permissions.RECEIPT_VIEW);
        Receipt r = ngoReceipt(ngoId, id);
        return pdf(receipts.pdfBytes(r), r.getReceiptNumber(), false);
    }

    @PostMapping("/api/v1/ngos/{ngoId}/receipts/{id}/reissue")
    public ReceiptQueries.Item reissue(@PathVariable UUID ngoId, @PathVariable UUID id, @Valid @RequestBody Reason req) {
        Membership m = guard.require(ngoId, Permissions.RECEIPT_MANAGE);
        return queries.byId(receipts.reissue(ngoId, m.userId(), id, req.reason()).getId());
    }

    @PostMapping("/api/v1/ngos/{ngoId}/receipts/{id}/retry")
    public ReceiptQueries.Item retry(@PathVariable UUID ngoId, @PathVariable UUID id) {
        guard.require(ngoId, Permissions.RECEIPT_MANAGE);
        return queries.byId(receipts.retry(ngoId, id).getId());
    }

    @PostMapping("/api/v1/ngos/{ngoId}/receipts/{id}/resend")
    public ReceiptQueries.Item resend(@PathVariable UUID ngoId, @PathVariable UUID id) {
        Membership m = guard.require(ngoId, Permissions.RECEIPT_MANAGE);
        return queries.byId(receipts.resend(ngoId, m.userId(), id).getId());
    }

    // ---------------- settings and signatories (admins edit; the team sees names)

    @GetMapping("/api/v1/ngos/{ngoId}/receipts/settings")
    public ReceiptSettings.Settings getSettings(@PathVariable UUID ngoId) {
        guard.require(ngoId, Permissions.RECEIPT_VIEW);
        return settings.get(ngoId);
    }

    @PutMapping("/api/v1/ngos/{ngoId}/receipts/settings")
    public ReceiptSettings.Settings putSettings(@PathVariable UUID ngoId, @RequestBody SettingsRequest req) {
        Membership m = guard.requireAdmin(ngoId);
        return settings.save(ngoId, m.userId(), req.prefix(), req.reg12aNumber(), req.thankYouNote(), req.version());
    }

    @GetMapping("/api/v1/ngos/{ngoId}/receipts/sample")
    public ResponseEntity<byte[]> sample(@PathVariable UUID ngoId, @RequestParam(defaultValue = "true") boolean tax80g) {
        guard.require(ngoId, Permissions.RECEIPT_VIEW);
        return pdf(receipts.previewStandard(ngoId, tax80g), "sample-receipt", false);
    }

    @GetMapping("/api/v1/ngos/{ngoId}/receipts/signatories")
    public List<ReceiptSettings.Signatory> signatories(@PathVariable UUID ngoId) {
        guard.require(ngoId, Permissions.RECEIPT_VIEW);
        return settings.signatories(ngoId);
    }

    @PostMapping("/api/v1/ngos/{ngoId}/receipts/signatories")
    @ResponseStatus(HttpStatus.CREATED)
    public ReceiptSettings.Signatory addSignatory(@PathVariable UUID ngoId, @RequestBody SignatoryRequest req) {
        Membership m = guard.requireAdmin(ngoId);
        return settings.add(ngoId, m.userId(), req.name(), req.designation());
    }

    @PutMapping("/api/v1/ngos/{ngoId}/receipts/signatories/{sid}")
    public ReceiptSettings.Signatory updateSignatory(@PathVariable UUID ngoId, @PathVariable UUID sid, @RequestBody SignatoryRequest req) {
        Membership m = guard.requireAdmin(ngoId);
        if (req.version() == null) throw ApiException.badRequest("VERSION_REQUIRED", "version is required");
        return settings.update(ngoId, m.userId(), sid, req.name(), req.designation(), req.position(), req.version());
    }

    @DeleteMapping("/api/v1/ngos/{ngoId}/receipts/signatories/{sid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeSignatory(@PathVariable UUID ngoId, @PathVariable UUID sid) {
        Membership m = guard.requireAdmin(ngoId);
        settings.remove(ngoId, m.userId(), sid);
    }

    @PostMapping(value = "/api/v1/ngos/{ngoId}/receipts/signatories/{sid}/signature", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ReceiptSettings.Signatory uploadSignature(@PathVariable UUID ngoId, @PathVariable UUID sid,
                                                     @RequestPart("file") MultipartFile file) throws IOException {
        Membership m = guard.requireAdmin(ngoId);
        return settings.uploadSignature(ngoId, m.userId(), sid, file.getBytes());
    }

    @DeleteMapping("/api/v1/ngos/{ngoId}/receipts/signatories/{sid}/signature")
    public ReceiptSettings.Signatory removeSignature(@PathVariable UUID ngoId, @PathVariable UUID sid) {
        Membership m = guard.requireAdmin(ngoId);
        return settings.removeSignature(ngoId, m.userId(), sid);
    }

    /** Signature images are shown to administrators only. */
    @GetMapping("/api/v1/ngos/{ngoId}/receipts/signatories/{sid}/signature")
    public ResponseEntity<byte[]> signature(@PathVariable UUID ngoId, @PathVariable UUID sid) {
        guard.requireAdmin(ngoId);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(settings.signatureImage(ngoId, sid));
    }

    // ---------------- donor access (DAPP-019..021)

    @GetMapping("/api/v1/receipts/mine")
    public PageResponse<ReceiptQueries.Item> mine(@RequestParam(defaultValue = "0") int page) {
        CurrentUser u = guard.user();
        return queries.mine(u.userId(), Math.max(0, page), 20);
    }

    @GetMapping("/api/v1/receipts/{id}")
    public ReceiptQueries.Item donorReceipt(@PathVariable UUID id) {
        CurrentUser u = guard.user();
        Receipt r = repo.findById(id).filter(x -> u.userId().equals(x.getDonorUserId())).orElseThrow(() -> ApiException.notFound("Receipt"));
        return queries.byId(r.getId());
    }

    @GetMapping("/api/v1/receipts/{id}/pdf")
    public ResponseEntity<byte[]> donorPdf(@PathVariable UUID id) {
        CurrentUser u = guard.user();
        Receipt r = repo.findById(id).filter(x -> u.userId().equals(x.getDonorUserId())).orElseThrow(() -> ApiException.notFound("Receipt"));
        return pdf(receipts.pdfBytes(r), r.getReceiptNumber(), false);
    }

    /** Guest retrieval through the signed, expiring link sent by e-mail (spec §12). */
    @GetMapping("/api/v1/public/receipts/{id}/pdf")
    public ResponseEntity<byte[]> guestPdf(@PathVariable UUID id, @RequestParam long exp, @RequestParam String sig) {
        if (!receipts.verifyLink(id, exp, sig)) throw ApiException.forbidden("LINK_INVALID", "This receipt link is invalid or expired");
        Receipt r = repo.findById(id).orElseThrow(() -> ApiException.notFound("Receipt"));
        return pdf(receipts.pdfBytes(r), r.getReceiptNumber(), true);
    }

    /**
     * QR code on every receipt: anyone can check that a receipt is genuine. Shows the NGO, number, date, amount,
     * the donor's initials only, and whether the receipt is still valid.
     */
    @GetMapping("/api/v1/public/receipts/verify/{code}")
    public ResponseEntity<?> verify(@PathVariable String code, @RequestParam(required = false) String format) {
        Receipt r = code.matches("[A-Z0-9]{10}") ? repo.findByVerifyCode(code).orElse(null) : null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("found", r != null && r.isIssued());
        if (r != null && r.isIssued()) {
            m.put("organization", r.getNgoName());
            m.put("receiptNumber", r.getReceiptNumber());
            m.put("date", r.getDonationDate().toString());
            m.put("amount", r.getAmount().toPlainString());
            m.put("donor", ReceiptRules.maskName(r.getDonorName()));
            m.put("status", r.isLive() ? "VALID" : "CANCELLED");
            if (!r.isLive()) m.put("cancelledOn", r.getVoidedAt() == null ? null : r.getVoidedAt().atZone(ReceiptService.IST).toLocalDate().toString());
        }
        if ("json".equals(format)) return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore()).body(m);
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
                .body(verifyPage(m));
    }

    public static String verifyPage(Map<String, Object> m) {
        boolean found = Boolean.TRUE.equals(m.get("found"));
        boolean valid = found && "VALID".equals(m.get("status"));
        String color = valid ? "#087A4B" : "#C62828";
        String title = !found ? "Receipt not found" : valid ? "Genuine receipt" : "This receipt was cancelled";
        StringBuilder rows = new StringBuilder();
        if (found) {
            row(rows, "Organisation", m.get("organization"));
            row(rows, "Receipt number", m.get("receiptNumber"));
            row(rows, "Date", m.get("date"));
            row(rows, "Amount", "₹ " + ReceiptRules.inr(new java.math.BigDecimal(String.valueOf(m.get("amount")))));
            row(rows, "Donor", m.get("donor"));
            if (m.get("cancelledOn") != null) row(rows, "Cancelled on", m.get("cancelledOn"));
        } else {
            rows.append("<p>Check the code printed under the QR code, or contact the organisation that issued the receipt.</p>");
        }
        return "<!doctype html><html lang=en><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>" + title + " - Seva Setu</title><style>body{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;"
                + "background:#F7F8F6;color:#14213D;margin:0;padding:24px}main{max-width:480px;margin:0 auto;background:#fff;"
                + "border:1px solid #E3E6EA;border-radius:16px;padding:24px}h1{font-size:22px;color:" + color + ";margin:0 0 16px}"
                + "dl{display:grid;grid-template-columns:auto 1fr;gap:8px 16px;margin:0}dt{color:#5B6478}dd{margin:0;font-weight:600}"
                + "footer{margin-top:20px;font-size:12px;color:#5B6478}</style></head><body><main><h1>" + title + "</h1><dl>" + rows
                + "</dl><footer>Verified by Seva Setu. Only the donor's initials are shown to protect their privacy.</footer></main></body></html>";
    }

    private static void row(StringBuilder b, String k, Object v) {
        b.append("<dt>").append(HtmlUtils.htmlEscape(k)).append("</dt><dd>").append(HtmlUtils.htmlEscape(String.valueOf(v))).append("</dd>");
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

    /** Which designer this NGO's plan includes (Professional: BASIC, Enterprise: ADVANCED, otherwise NONE). */
    @GetMapping("/api/v1/ngos/{ngoId}/receipt-templates/designer")
    public Map<String, Object> designer(@PathVariable UUID ngoId) {
        guard.require(ngoId, Permissions.RECEIPT_VIEW);
        var e = guard.entitlements(ngoId);
        String level = e.flag(org.ngoplatform.commons.security.Entitlements.RECEIPT_ADVANCED_DESIGNER) ? "ADVANCED"
                : e.flag(org.ngoplatform.commons.security.Entitlements.RECEIPT_BASIC_DESIGNER) ? "BASIC" : "NONE";
        return Map.of("level", level, "textKeys", TemplateDefinition.TEXT_KEYS, "imageKeys", TemplateDefinition.IMAGE_KEYS,
                "requiredKeys", TemplateDefinition.REQUIRED_KEYS);
    }

    @GetMapping("/api/v1/ngos/{ngoId}/receipt-templates/{id}/background")
    public ResponseEntity<byte[]> templateBackground(@PathVariable UUID ngoId, @PathVariable UUID id) {
        guard.require(ngoId, Permissions.RECEIPT_TEMPLATE_DESIGN);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(templates.background(ngoId, id));
    }

    @PostMapping("/api/v1/ngos/{ngoId}/receipt-templates/{id}/activate")
    public TemplateView activate(@PathVariable UUID ngoId, @PathVariable UUID id) {
        Membership m = guard.require(ngoId, Permissions.RECEIPT_TEMPLATE_DESIGN);
        return view(templates.setActive(ngoId, m.userId(), id, true));
    }

    @PostMapping("/api/v1/ngos/{ngoId}/receipt-templates/{id}/deactivate")
    public TemplateView deactivate(@PathVariable UUID ngoId, @PathVariable UUID id) {
        Membership m = guard.require(ngoId, Permissions.RECEIPT_TEMPLATE_DESIGN);
        return view(templates.setActive(ngoId, m.userId(), id, false));
    }

    @GetMapping("/api/v1/ngos/{ngoId}/receipt-templates/{id}/validation")
    public TemplateDefinition.Result validate(@PathVariable UUID ngoId, @PathVariable UUID id) {
        guard.require(ngoId, Permissions.RECEIPT_TEMPLATE_DESIGN);
        return templates.validate(ngoId, id);
    }

    @GetMapping("/api/v1/ngos/{ngoId}/receipt-templates/{id}/preview")
    public ResponseEntity<byte[]> preview(@PathVariable UUID ngoId, @PathVariable UUID id) {
        guard.require(ngoId, Permissions.RECEIPT_TEMPLATE_DESIGN);
        return pdf(templates.preview(ngoId, id), "preview", false);
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
