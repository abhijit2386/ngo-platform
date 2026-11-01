package org.ngoplatform.receipt.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.ngoplatform.commons.audit.AuditLog;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.events.EventPublisher;
import org.ngoplatform.commons.events.EventTypes;
import org.ngoplatform.commons.security.EntitlementResolver;
import org.ngoplatform.commons.security.Entitlements;
import org.ngoplatform.commons.storage.FileInspector;
import org.ngoplatform.commons.storage.ObjectStorage;
import org.ngoplatform.receipt.domain.Receipt;
import org.ngoplatform.receipt.domain.Repos;
import org.ngoplatform.receipt.domain.Template;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Receipt lifecycle (spec §15.6): PENDING -> GENERATING -> [numbered] -> GENERATED | DELIVERY_PENDING -> DELIVERED,
 * GENERATION_FAILED retried with backoff, VOIDED / REISSUED keep their history.
 * <ol>
 * <li>The number is taken in its own transaction (row lock on the NGO's financial-year sequence) together with the
 * facts printed on the receipt; a retry reuses them, so the series has no gaps and a receipt never changes.</li>
 * <li>The PDF is rendered and stored outside any transaction.</li>
 * <li>The receipt is marked issued; the vault entry and events go out through the outbox in the same transaction.</li>
 * <li>The donor is told (e-mail with a 30-day download link + in-app) through notification-service.</li>
 * </ol>
 */
@Service
public class ReceiptService {
    private static final Logger log = LoggerFactory.getLogger(ReceiptService.class);
    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private final Repos.Receipts receipts;
    private final Repos.Headers headers;
    private final Repos.Versions versions;
    private final JdbcTemplate jdbc;
    private final Facts facts;
    private final ReceiptSettings settings;
    private final PdfRenderer pdf;
    private final ObjectStorage storage;
    private final EventPublisher events;
    private final AuditLog audit;
    private final EntitlementResolver entitlements;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final byte[] linkSecret;
    private final String publicBase;

    public ReceiptService(Repos.Receipts receipts, Repos.Headers headers, Repos.Versions versions, JdbcTemplate jdbc, Facts facts,
                          ReceiptSettings settings, PdfRenderer pdf, ObjectStorage storage, EventPublisher events, AuditLog audit,
                          EntitlementResolver entitlements, ObjectMapper mapper, Clock clock, PlatformTransactionManager txm,
                          @Value("${ngo.receipts.link-secret}") String linkSecret,
                          @Value("${ngo.receipts.public-base-url}") String publicBase) {
        this.receipts = receipts; this.headers = headers; this.versions = versions; this.jdbc = jdbc; this.facts = facts;
        this.settings = settings; this.pdf = pdf; this.storage = storage; this.events = events; this.audit = audit;
        this.entitlements = entitlements; this.mapper = mapper; this.clock = clock; this.tx = new TransactionTemplate(txm);
        this.linkSecret = linkSecret.getBytes(StandardCharsets.UTF_8);
        this.publicBase = publicBase;
    }

    // ------------------------------------------------------------------ creation

    /** DONATION_PAID consumer: create the receipt record idempotently (one live receipt per donation). */
    public Receipt onDonationPaid(UUID ngoId, UUID donationId, UUID donorId, UUID donorUserId, BigDecimal amount) {
        return tx.execute(s -> receipts.findByDonationIdOrderByCreatedAtDesc(donationId).stream()
                .filter(r -> r.isLive() || r.getStatus() == Receipt.Status.VOIDED).findFirst()
                .orElseGet(() -> {
                    Receipt r = receipts.saveAndFlush(new Receipt(ngoId, donationId, donorId, donorUserId, amount));
                    event(r, "CREATED", null);
                    return r;
                }));
    }

    // ------------------------------------------------------------------ generation

    record Printed(Receipt receipt, Map<String, Object> donation, Map<String, Object> ngo, boolean tax80g) {}

    /** Generates (or retries) one receipt. Failures leave it in GENERATION_FAILED with a retry time; nothing is lost. */
    public Receipt generate(UUID receiptId) {
        Receipt claimed = tx.execute(s -> {
            Receipt r = receipts.findById(receiptId).orElseThrow();
            if (r.getStatus() != Receipt.Status.PENDING && r.getStatus() != Receipt.Status.GENERATION_FAILED) return null;
            r.generating();
            return receipts.saveAndFlush(r);
        });
        if (claimed == null) return receipts.findById(receiptId).orElseThrow();
        try {
            Printed p = number(receiptId);
            byte[] bytes = render(p);
            String key = "ngos/" + p.receipt().getNgoId() + "/receipts/" + p.receipt().getFiscalYear() + "/" + p.receipt().getId() + ".pdf";
            storage.put(key, bytes, "application/pdf");
            Receipt done = tx.execute(s -> issued(receiptId, key, bytes, p));
            notifyDonor(done.getId());
            return receipts.findById(receiptId).orElseThrow();
        } catch (Exception e) {
            log.warn("Receipt {} generation failed: {}", receiptId, e.toString());
            String error = e instanceof ApiException a ? a.getMessage() : e.getClass().getSimpleName() + ": " + e.getMessage();
            return tx.execute(s -> {
                Receipt r = receipts.findById(receiptId).orElseThrow();
                if (r.getStatus() != Receipt.Status.GENERATING) return r;
                r.failed(error, clock.instant());
                event(r, "GENERATION_FAILED", r.getLastError());
                if (r.getAttempts() >= 5) {
                    events.publish(EventTypes.RECEIPT_FAILED, r.getNgoId(), r.getId(), Map.of("attempts", r.getAttempts()));
                }
                return receipts.save(r);
            });
        }
    }

    /** Step 1: number + printed facts, committed. A retry finds them already set and prints the same receipt. */
    private Printed number(UUID receiptId) {
        Receipt r0 = receipts.findById(receiptId).orElseThrow();
        Map<String, Object> d = facts.donation(r0.getDonationId());
        String status = str(d.get("status"));
        if (Boolean.TRUE.equals(d.get("voided"))) throw ApiException.rule("DONATION_VOIDED", "The donation was corrected");
        if (!"PAID".equals(status) && !"PARTIALLY_REFUNDED".equals(status)) {
            throw ApiException.rule("DONATION_NOT_PAID", "Donation is not paid (" + status + ")");
        }
        Map<String, Object> ngo = facts.ngo(r0.getNgoId());
        LocalDate paidDate = Instant.parse(str(d.get("paidAt"))).atZone(IST).toLocalDate();
        boolean tax80g = ReceiptRules.eligible80g(Boolean.TRUE.equals(ngo.get("tax80gVerified")),
                ngo.get("tax80gValidUntil") == null ? null : LocalDate.parse(str(ngo.get("tax80gValidUntil"))),
                Boolean.TRUE.equals(d.get("wants80g")), (String) d.get("donorPan"), paidDate);
        Receipt r = tx.execute(s -> {
            Receipt x = receipts.findById(receiptId).orElseThrow();
            if (x.getReceiptNumber() == null) {
                String fy = ReceiptRules.fiscalYear(paidDate);
                String prefix = settings.get(x.getNgoId()).prefix();
                String number = ReceiptRules.number(prefix, fy, next(x.getNgoId(), fy));
                // Owner decision 2026-11-01: the name exactly as the donor wrote it (profile name for signed-in donors,
                // copied at checkout; typed by staff for offline donations).
                String name = str(d.get("donorName")).strip();
                x.numbered(number, uniqueCode(), name.isEmpty() ? "Donor" : name, str(ngo.get("legalName")), paidDate, fy,
                        str(d.get("channel")), (String) d.get("campaignTitle"), tax80g);
                event(x, "NUMBERED", number);
                x = receipts.saveAndFlush(x);
            }
            return x;
        });
        return new Printed(r, d, ngo, tax80g);
    }

    private long next(UUID ngoId, String fy) {
        jdbc.update("insert into receipt_sequences(ngo_id, fiscal_year, next_value) values (?,?,1) on conflict do nothing", ngoId, fy);
        Long n = jdbc.queryForObject("select next_value from receipt_sequences where ngo_id = ? and fiscal_year = ? for update",
                Long.class, ngoId, fy);
        jdbc.update("update receipt_sequences set next_value = next_value + 1 where ngo_id = ? and fiscal_year = ?", ngoId, fy);
        return n;
    }

    private String uniqueCode() {
        for (int i = 0; i < 5; i++) {
            String c = ReceiptRules.verifyCode();
            if (receipts.findByVerifyCode(c).isEmpty()) return c;
        }
        throw new IllegalStateException("could not allocate a verification code");
    }

    /** Step 2: the PDF, from the numbered facts (never from data that changed afterwards). */
    byte[] render(Printed p) throws IOException {
        Receipt r = p.receipt();
        Map<String, Object> d = p.donation(), ngo = p.ngo();
        ReceiptSettings.Settings st = settings.get(r.getNgoId());
        Map<String, String> v = new HashMap<>();
        v.put("receiptNumber", r.getReceiptNumber());
        v.put("date", DATE.format(r.getDonationDate()));
        v.put("donorName", r.getDonorName());
        if (p.tax80g()) {
            if (d.get("donorAddress") != null) v.put("donorAddress", str(d.get("donorAddress")));
            v.put("donorPan", str(d.get("donorPan")));
        }
        v.put("amount", ReceiptRules.inr(r.getAmount()));
        v.put("amountInWords", AmountInWords.of(r.getAmount()));
        String ref = (String) d.get("paymentReference");
        v.put("mode", ReceiptRules.channelLabel(r.getChannel()) + (ref == null || ref.isBlank() ? "" : "  ·  Ref. " + ref));
        String towards = r.getCampaignTitle() != null ? r.getCampaignTitle() : (String) d.get("purpose");
        v.put("towards", towards == null || towards.isBlank() ? "General donation" : towards);
        if (d.get("purpose") != null) v.put("purpose", str(d.get("purpose")));
        v.put("ngoName", r.getNgoName());
        if (ngo.get("address") != null) v.put("ngoAddress", str(ngo.get("address")));
        if (ngo.get("pan") != null) v.put("ngoPan", str(ngo.get("pan")));
        if (ngo.get("registrationNumber") != null) v.put("ngoRegistrationNumber", str(ngo.get("registrationNumber")));
        if (st.reg12aNumber() != null) v.put("ngo12a", st.reg12aNumber());
        if (st.thankYouNote() != null) v.put("thankYou", st.thankYouNote());
        if (p.tax80g()) {
            v.put("tax80gText", "This donation is eligible for deduction under section 80G of the Income-tax Act, 1961. "
                    + "80G registration no. " + ngo.get("tax80gNumber") + ", valid till "
                    + DATE.format(LocalDate.parse(str(ngo.get("tax80gValidUntil")))) + ".");
        }
        v.put("verifyCode", r.getVerifyCode());
        List<PdfRenderer.Signatory> signatories = settings.forPrinting(r.getNgoId());
        if (!signatories.isEmpty()) {
            v.put("signatoryName", signatories.getFirst().name());
            v.put("signatoryDesignation", signatories.getFirst().designation());
        }
        byte[] logo = ngo.get("logoKey") != null ? facts.logo(r.getNgoId()) : null;
        PdfRenderer.Data data = new PdfRenderer.Data(v, logo, p.tax80g(), signatories, verifyUrl(r.getVerifyCode()));
        Template.Header active = activeTemplate(r.getNgoId());
        if (active == null) return pdf.standard(data);
        Template.Version tv = versions.findByTemplateIdAndVersionNo(active.getId(), active.getPublishedVersion()).orElseThrow();
        TemplateDefinition def = mapper.readValue(tv.getDefinition(), TemplateDefinition.class);
        byte[] bg = null;
        if (tv.getBackgroundKey() != null) {
            try (InputStream in = storage.get(tv.getBackgroundKey())) { bg = in.readAllBytes(); }
        }
        Map<String, String> conditionFacts = Map.of("tax80g", String.valueOf(p.tax80g()), "channel", str(r.getChannel()),
                "hasPan", String.valueOf(d.get("donorPan") != null));
        return pdf.custom(def, bg, data, conditionFacts);
    }

    /** Step 3: mark issued; vault entry and events through the outbox (same transaction). */
    private Receipt issued(UUID receiptId, String key, byte[] bytes, Printed p) {
        Receipt r = receipts.findById(receiptId).orElseThrow();
        Template.Header active = activeTemplate(r.getNgoId());
        boolean hasRecipient = r.getDonorUserId() != null || p.donation().get("donorEmail") != null;
        r.generated(key, FileInspector.sha256(bytes), bytes.length, active == null ? null : active.getId(),
                active == null ? null : active.getPublishedVersion(), p.tax80g(), hasRecipient, clock.instant());
        receipts.saveAndFlush(r);
        event(r, "GENERATED", r.getReceiptNumber());
        Map<String, Object> payload = new HashMap<>();
        payload.put("receiptId", r.getId().toString());
        payload.put("donationId", r.getDonationId().toString());
        payload.put("receiptNumber", r.getReceiptNumber());
        if (r.getDonorUserId() != null) payload.put("donorUserId", r.getDonorUserId().toString());
        events.publish(EventTypes.RECEIPT_GENERATED, r.getNgoId(), r.getId(), payload);
        events.publish(EventTypes.DOCUMENT_FILE_REGISTERED, r.getNgoId(), r.getId(), vaultFile(r, false));
        audit.record(r.getNgoId(), null, "RECEIPT_GENERATED", "RECEIPT", r.getId(), Map.of("number", r.getReceiptNumber()));
        return r;
    }

    /** The receipt as an entry of the NGO's document vault (category Receipts; the file stays here). */
    static Map<String, Object> vaultFile(Receipt r, boolean cancelled) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ngoId", r.getNgoId().toString());
        m.put("source", "RECEIPT");
        m.put("sourceRef", r.getId().toString());
        m.put("storageKey", r.getPdfKey());
        m.put("title", (cancelled ? "Cancelled: " : "") + "Receipt " + r.getReceiptNumber() + " - " + r.getDonorName());
        m.put("category", "RECEIPT");
        m.put("contentType", "application/pdf");
        m.put("sizeBytes", r.getPdfSize() == null ? 0 : r.getPdfSize());
        m.put("originalName", r.getReceiptNumber().replace('/', '-') + ".pdf");
        m.put("visibility", "TEAM");
        m.put("requiredPermission", "RECEIPT_VIEW");
        m.put("linkType", "RECEIPT");
        m.put("linkId", r.getId().toString());
        m.put("linkLabel", r.getReceiptNumber());
        if (r.getDonationDate() != null) m.put("documentDate", r.getDonationDate().toString());
        m.put("createdAt", (r.getIssuedAt() == null ? r.getCreatedAt() : r.getIssuedAt()).toString());
        return m;
    }

    /** Designer templates only apply while the plan still has the entitlement; otherwise standard (never blocks receipts). */
    Template.Header activeTemplate(UUID ngoId) {
        var t = headers.findByNgoIdAndActiveTrue(ngoId).orElse(null);
        if (t == null || t.getPublishedVersion() == null) return null;
        Entitlements e = entitlements.get(ngoId);
        boolean allowed = t.getKind() == Template.Header.Kind.ADVANCED ? e.flag(Entitlements.RECEIPT_ADVANCED_DESIGNER)
                : e.flag(Entitlements.RECEIPT_BASIC_DESIGNER);
        return allowed ? t : null;
    }

    // ------------------------------------------------------------------ delivery

    /**
     * Signed-in donors: in-app + e-mail (their account e-mail); guests and offline donors with an e-mail: e-mail with a
     * 30-day download link. Failures are retried by the worker; notification-service is idempotent per receipt.
     */
    public void notifyDonor(UUID receiptId) {
        Receipt r = receipts.findById(receiptId).orElseThrow();
        if (r.getStatus() != Receipt.Status.DELIVERY_PENDING || r.getNotifiedAt() != null) return;
        try {
            Map<String, Object> d = facts.donation(r.getDonationId());
            Map<String, Object> req = new HashMap<>();
            req.put("templateCode", "RECEIPT_ISSUED");
            req.put("ngoId", r.getNgoId());
            if (r.getDonorUserId() != null) req.put("userId", r.getDonorUserId());
            else req.put("email", d.get("donorEmail"));
            req.put("vars", Map.of("ngoName", r.getNgoName(), "receiptNumber", r.getReceiptNumber(),
                    "amount", "₹" + ReceiptRules.inr(r.getAmount()), "downloadUrl", publicLink(r)));
            req.put("link", "/donor/receipts/" + r.getId());
            req.put("resourceType", "RECEIPT");
            req.put("resourceId", r.getId().toString());
            req.put("requestId", r.getId());
            req.put("inApp", r.getDonorUserId() != null);
            req.put("sendEmail", true);
            facts.notify(req);
            tx.executeWithoutResult(s -> receipts.findById(receiptId).ifPresent(x -> {
                x.notified(clock.instant());
                event(x, "DELIVERY_REQUESTED", r.getDonorUserId() != null ? "donor account" : "e-mail");
                receipts.save(x);
            }));
        } catch (RuntimeException e) {
            log.warn("Receipt {} notification failed (will retry): {}", receiptId, e.toString());
        }
    }

    /** NGO "send again": a fresh e-mail/in-app message for an issued receipt. */
    public Receipt resend(UUID ngoId, UUID actor, UUID receiptId) {
        Receipt r = receipts.findByIdAndNgoId(receiptId, ngoId).orElseThrow(() -> ApiException.notFound("Receipt"));
        if (!r.isLive() || !r.isIssued()) throw ApiException.rule("RECEIPT_NOT_ISSUED", "Only issued receipts can be sent");
        Map<String, Object> d = facts.donation(r.getDonationId());
        if (r.getDonorUserId() == null && d.get("donorEmail") == null) {
            throw ApiException.rule("NO_RECIPIENT", "This donor has no e-mail address. Download and share the receipt instead.");
        }
        Map<String, Object> req = new HashMap<>();
        req.put("templateCode", "RECEIPT_ISSUED");
        req.put("ngoId", ngoId);
        if (r.getDonorUserId() != null) req.put("userId", r.getDonorUserId());
        else req.put("email", d.get("donorEmail"));
        req.put("vars", Map.of("ngoName", r.getNgoName(), "receiptNumber", r.getReceiptNumber(),
                "amount", "₹" + ReceiptRules.inr(r.getAmount()), "downloadUrl", publicLink(r)));
        req.put("link", "/donor/receipts/" + r.getId());
        req.put("resourceType", "RECEIPT");
        req.put("resourceId", r.getId().toString());
        req.put("requestId", UUID.randomUUID());
        req.put("inApp", false);
        req.put("sendEmail", true);
        facts.notify(req);
        tx.executeWithoutResult(s -> receipts.findById(receiptId).ifPresent(x -> event(x, "RESENT", null)));
        audit.record(ngoId, actor, "RECEIPT_RESENT", "RECEIPT", receiptId, Map.of());
        return r;
    }

    public void markDelivered(UUID receiptId) {
        tx.executeWithoutResult(s -> receipts.findById(receiptId).ifPresent(r -> {
            if (r.getStatus() == Receipt.Status.DELIVERY_PENDING) {
                r.delivered(clock.instant());
                event(r, "DELIVERED", null);
                receipts.save(r);
            }
        }));
    }

    // ------------------------------------------------------------------ worker

    @Scheduled(fixedDelayString = "${ngo.receipts.worker-ms:5000}", initialDelay = 20000)
    public void worker() {
        Instant now = clock.instant();
        // Receipts stuck in GENERATING (crash mid-generation) are retried.
        for (Receipt r : receipts.findByStatusAndUpdatedAtBefore(Receipt.Status.GENERATING, now.minusSeconds(300))) {
            tx.executeWithoutResult(s -> receipts.findById(r.getId()).ifPresent(x -> { x.failed("interrupted", now); receipts.save(x); }));
        }
        for (Receipt r : receipts.findByStatusAndUpdatedAtBefore(Receipt.Status.PENDING, now.plusSeconds(1))) generate(r.getId());
        for (Receipt r : receipts.findByStatusAndNextAttemptAtBefore(Receipt.Status.GENERATION_FAILED, now)) generate(r.getId());
        for (Receipt r : receipts.findByStatusAndNotifiedAtIsNullAndUpdatedAtBefore(Receipt.Status.DELIVERY_PENDING, now.minusSeconds(60))) {
            notifyDonor(r.getId());
        }
    }

    // ------------------------------------------------------------------ corrections

    /** REC-007: cancel the live receipt and issue a new one (new number) for the same donation. */
    public Receipt reissue(UUID ngoId, UUID actor, UUID receiptId, String reason) {
        String why = ReceiptRules.reason(reason);
        Receipt fresh = tx.execute(s -> {
            Receipt old = receipts.findByIdAndNgoId(receiptId, ngoId).orElseThrow(() -> ApiException.notFound("Receipt"));
            if (!old.isLive()) throw ApiException.rule("RECEIPT_NOT_LIVE", "This receipt was already cancelled");
            if (!old.isIssued()) throw ApiException.rule("RECEIPT_NOT_ISSUED", "This receipt is still being prepared");
            old.voidIt(why, actor, true, clock.instant());
            receipts.saveAndFlush(old);
            event(old, "REISSUED", why);
            events.publish(EventTypes.DOCUMENT_FILE_REGISTERED, ngoId, old.getId(), vaultFile(old, true));
            events.publish(EventTypes.RECEIPT_VOIDED, ngoId, old.getId(), Map.of("reason", why, "reissued", true));
            Receipt n = new Receipt(ngoId, old.getDonationId(), old.getDonorId(), old.getDonorUserId(), old.getAmount());
            n.setReissueOf(old.getId());
            receipts.saveAndFlush(n);
            event(n, "CREATED", "replaces " + old.getReceiptNumber());
            audit.record(ngoId, actor, "RECEIPT_REISSUED", "RECEIPT", old.getId(), Map.of("reason", why, "number", old.getReceiptNumber()));
            return n;
        });
        return generate(fresh.getId());
    }

    /** Donation voided / fully refunded: receipt cancelled (history and number kept). */
    public void voidForDonation(UUID donationId, String reason) {
        tx.executeWithoutResult(s -> receipts.findByDonationIdOrderByCreatedAtDesc(donationId).stream().filter(Receipt::isLive).forEach(r -> {
            r.voidIt(reason, null, false, clock.instant());
            receipts.saveAndFlush(r);
            event(r, "VOIDED", reason);
            if (r.isIssued()) events.publish(EventTypes.DOCUMENT_FILE_REGISTERED, r.getNgoId(), r.getId(), vaultFile(r, true));
            events.publish(EventTypes.RECEIPT_VOIDED, r.getNgoId(), r.getId(), Map.of("reason", reason, "reissued", false));
        }));
    }

    public Receipt retry(UUID ngoId, UUID receiptId) {
        Receipt r = receipts.findByIdAndNgoId(receiptId, ngoId).orElseThrow(() -> ApiException.notFound("Receipt"));
        if (r.getStatus() != Receipt.Status.GENERATION_FAILED) throw ApiException.rule("NOT_FAILED", "Only failed receipts can be retried");
        return generate(receiptId);
    }

    public byte[] pdfBytes(Receipt r) {
        if (r.getPdfKey() == null) throw ApiException.rule("RECEIPT_NOT_READY", "The receipt is still being prepared");
        try (InputStream in = storage.get(r.getPdfKey())) { return in.readAllBytes(); } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Sample data with this NGO's real details, settings, signatories and logo and a fictional donor. */
    PdfRenderer.Data sampleData(UUID ngoId, boolean with80g) {
        Map<String, Object> ngo = facts.ngo(ngoId);
        ReceiptSettings.Settings st = settings.get(ngoId);
        LocalDate today = LocalDate.now(clock.withZone(IST));
        String fy = ReceiptRules.fiscalYear(today);
        Map<String, String> v = new HashMap<>();
        v.put("receiptNumber", ReceiptRules.number(st.prefix(), fy, 1));
        v.put("date", DATE.format(today));
        v.put("donorName", "Sample Donor");
        boolean tax80g = with80g && Boolean.TRUE.equals(ngo.get("tax80gVerified")) && ngo.get("tax80gValidUntil") != null;
        if (tax80g) {
            v.put("donorAddress", "12 Sample Road, Pune, Maharashtra 411001");
            v.put("donorPan", "ABCDE1234F");
            v.put("tax80gText", "This donation is eligible for deduction under section 80G of the Income-tax Act, 1961. "
                    + "80G registration no. " + ngo.get("tax80gNumber") + ", valid till "
                    + DATE.format(LocalDate.parse(str(ngo.get("tax80gValidUntil")))) + ".");
        }
        BigDecimal amount = new BigDecimal("12500");
        v.put("amount", ReceiptRules.inr(amount));
        v.put("amountInWords", AmountInWords.of(amount));
        v.put("mode", "Online payment  ·  Ref. pay_SAMPLE123");
        v.put("towards", "General donation");
        v.put("ngoName", str(ngo.get("legalName")));
        if (ngo.get("address") != null) v.put("ngoAddress", str(ngo.get("address")));
        if (ngo.get("pan") != null) v.put("ngoPan", str(ngo.get("pan")));
        if (ngo.get("registrationNumber") != null) v.put("ngoRegistrationNumber", str(ngo.get("registrationNumber")));
        if (st.reg12aNumber() != null) v.put("ngo12a", st.reg12aNumber());
        if (st.thankYouNote() != null) v.put("thankYou", st.thankYouNote());
        v.put("verifyCode", "SAMPLE0000");
        byte[] logo = ngo.get("logoKey") != null ? facts.logo(ngoId) : null;
        return new PdfRenderer.Data(v, logo, tax80g, settings.forPrinting(ngoId), verifyUrl("SAMPLE0000"));
    }

    /** "See a sample": the standard receipt with this NGO's details, signatories and settings and a sample donor. */
    public byte[] previewStandard(UUID ngoId, boolean with80g) {
        return pdf.standard(sampleData(ngoId, with80g));
    }

    /** Designer preview: exactly how the template prints (sample donor), 80G block shown when the NGO can issue 80G. */
    public byte[] previewTemplate(UUID ngoId, TemplateDefinition def, byte[] background) {
        PdfRenderer.Data d = sampleData(ngoId, true);
        return pdf.custom(def, background, d, Map.of("tax80g", String.valueOf(d.tax80g()), "channel", "ONLINE", "hasPan", "true"));
    }

    // ------------------------------------------------------------------ links

    public String verifyUrl(String code) {
        return publicBase + "/api/v1/public/receipts/verify/" + code;
    }

    /** Guest download link (e-mail), valid 30 days. */
    public String publicLink(Receipt r) {
        long exp = clock.instant().plusSeconds(30L * 24 * 3600).getEpochSecond();
        return publicBase + "/api/v1/public/receipts/" + r.getId() + "/pdf?exp=" + exp + "&sig=" + sign(r.getId() + ":" + exp);
    }

    public boolean verifyLink(UUID id, long exp, String sig) {
        if (exp < clock.instant().getEpochSecond()) return false;
        return java.security.MessageDigest.isEqual(sign(id + ":" + exp).getBytes(StandardCharsets.UTF_8),
                sig == null ? new byte[0] : sig.getBytes(StandardCharsets.UTF_8));
    }

    String sign(String s) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(linkSecret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private void event(Receipt r, String ev, String detail) {
        jdbc.update("insert into receipt_events(id, receipt_id, event, detail) values (?,?,?,?)", UUID.randomUUID(), r.getId(), ev,
                detail == null ? null : detail.substring(0, Math.min(300, detail.length())));
    }

    static String str(Object o) { return o == null ? "" : String.valueOf(o); }
}
