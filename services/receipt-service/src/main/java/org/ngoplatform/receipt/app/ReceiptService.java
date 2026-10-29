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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * Receipt lifecycle (spec §15.6): PENDING -> GENERATING -> DELIVERY_PENDING -> DELIVERED, with GENERATION_FAILED
 * retried by backoff. Numbering is gap-free per NGO per Indian financial year (row lock on the sequence).
 */
@Service
public class ReceiptService {
    private static final Logger log = LoggerFactory.getLogger(ReceiptService.class);
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");

    private final Repos.Receipts receipts;
    private final Repos.Headers headers;
    private final Repos.Versions versions;
    private final JdbcTemplate jdbc;
    private final Facts facts;
    private final PdfRenderer pdf;
    private final ObjectStorage storage;
    private final EventPublisher events;
    private final AuditLog audit;
    private final EntitlementResolver entitlements;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final byte[] linkSecret;
    private final String publicBase;

    public ReceiptService(Repos.Receipts receipts, Repos.Headers headers, Repos.Versions versions, JdbcTemplate jdbc,
                          Facts facts, PdfRenderer pdf, ObjectStorage storage, EventPublisher events, AuditLog audit,
                          EntitlementResolver entitlements, ObjectMapper mapper, Clock clock,
                          @Value("${ngo.receipts.link-secret}") String linkSecret,
                          @Value("${ngo.receipts.public-base-url}") String publicBase) {
        this.receipts = receipts; this.headers = headers; this.versions = versions; this.jdbc = jdbc; this.facts = facts;
        this.pdf = pdf; this.storage = storage; this.events = events; this.audit = audit; this.entitlements = entitlements;
        this.mapper = mapper; this.clock = clock; this.linkSecret = linkSecret.getBytes(StandardCharsets.UTF_8);
        this.publicBase = publicBase;
    }

    /** DONATION_PAID consumer: create the receipt record idempotently (one live receipt per donation). */
    @Transactional
    public Receipt onDonationPaid(UUID ngoId, UUID donationId, UUID donorId, UUID donorUserId, BigDecimal amount) {
        return receipts.findByDonationIdOrderByCreatedAtDesc(donationId).stream().filter(Receipt::isLive).findFirst()
                .orElseGet(() -> {
                    Receipt r = receipts.saveAndFlush(new Receipt(ngoId, donationId, donorId, donorUserId, amount));
                    event(r, "CREATED", null);
                    return r;
                });
    }

    static String fiscalYear(LocalDate d) {
        int start = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
        return start + "-" + String.format("%02d", (start + 1) % 100);
    }

    String nextNumber(UUID ngoId, String prefix, LocalDate date) {
        String fy = fiscalYear(date);
        jdbc.update("insert into receipt_sequences(ngo_id, fiscal_year, next_value) values (?,?,1) on conflict do nothing", ngoId, fy);
        Long n = jdbc.queryForObject("select next_value from receipt_sequences where ngo_id = ? and fiscal_year = ? for update",
                Long.class, ngoId, fy);
        jdbc.update("update receipt_sequences set next_value = next_value + 1 where ngo_id = ? and fiscal_year = ?", ngoId, fy);
        return prefix + "/" + fy + "/" + String.format("%06d", n);
    }

    /** Generates the PDF. Any failure leaves the receipt in GENERATION_FAILED with a retry time; nothing is lost. */
    @Transactional(noRollbackFor = Exception.class)
    public Receipt generate(UUID receiptId) {
        Receipt r = receipts.findById(receiptId).orElseThrow();
        if (r.getStatus() != Receipt.Status.PENDING && r.getStatus() != Receipt.Status.GENERATION_FAILED) return r;
        r.generating();
        try {
            Map<String, Object> d = facts.donation(r.getDonationId());
            if (!"PAID".equals(d.get("status")) && !"PARTIALLY_REFUNDED".equals(d.get("status"))) {
                throw new IllegalStateException("Donation not paid (" + d.get("status") + ")");
            }
            Map<String, Object> ngo = facts.ngo(r.getNgoId());
            Map<String, Object> donor = facts.donor(r.getNgoId(), r.getDonorId());
            Instant paidAt = Instant.parse(String.valueOf(d.get("paidAt")));
            LocalDate paidDate = paidAt.atZone(IST).toLocalDate();
            // 80G text only when the NGO's 80G registration is verified, valid on the donation date, and PAN was given.
            boolean eligible80g = Boolean.TRUE.equals(ngo.get("tax80gVerified")) && Boolean.TRUE.equals(d.get("wants80g"))
                    && d.get("donorPan") != null && ngo.get("tax80gValidUntil") != null
                    && !LocalDate.parse(String.valueOf(ngo.get("tax80gValidUntil"))).isBefore(paidDate);
            String prefix = "RCPT";
            String number = r.getReceiptNumber() != null ? r.getReceiptNumber() : nextNumber(r.getNgoId(), prefix, paidDate);
            Map<String, String> v = new HashMap<>();
            v.put("receiptNumber", number);
            v.put("date", DATE.format(paidDate));
            v.put("donorName", str(donor.getOrDefault("name", d.get("donorName"))));
            if (donor.get("address") != null) v.put("donorAddress", str(donor.get("address")));
            if (eligible80g) v.put("donorPan", str(d.get("donorPan")));
            BigDecimal amount = new BigDecimal(str(d.get("amount")));
            v.put("amount", String.format("%,.2f", amount));
            v.put("amountInWords", AmountInWords.of(amount));
            v.put("channel", str(d.get("channel")).replace('_', ' '));
            if (d.get("paymentReference") != null) v.put("paymentReference", str(d.get("paymentReference")));
            if (d.get("purpose") != null) v.put("purpose", str(d.get("purpose")));
            v.put("ngoName", str(ngo.get("legalName")));
            if (ngo.get("address") != null) v.put("ngoAddress", str(ngo.get("address")));
            if (ngo.get("pan") != null) v.put("ngoPan", str(ngo.get("pan")));
            if (ngo.get("registrationNumber") != null) v.put("ngoRegistrationNumber", str(ngo.get("registrationNumber")));
            if (ngo.get("signatoryName") != null) v.put("signatoryName", str(ngo.get("signatoryName")));
            if (ngo.get("signatoryDesignation") != null) v.put("signatoryDesignation", str(ngo.get("signatoryDesignation")));
            if (eligible80g) {
                v.put("tax80gText", "Eligible for deduction u/s 80G of the Income-tax Act, 1961. 80G Reg. No. "
                        + ngo.get("tax80gNumber") + " valid till " + ngo.get("tax80gValidUntil") + ".");
            }
            v.put("verifyHint", number);
            byte[] logo = null;
            if (ngo.get("logoKey") != null) {
                logo = facts.logo(r.getNgoId());
            }
            PdfRenderer.Data data = new PdfRenderer.Data(v, logo, eligible80g);
            Map<String, String> conditionFacts = Map.of("tax80g", String.valueOf(eligible80g), "channel", str(d.get("channel")),
                    "hasPan", String.valueOf(d.get("donorPan") != null));
            byte[] bytes;
            UUID templateId = null;
            Integer templateVersion = null;
            var active = activeTemplate(r.getNgoId());
            if (active != null) {
                Template.Version tv = versions.findByTemplateIdAndVersionNo(active.getId(), active.getPublishedVersion()).orElseThrow();
                TemplateDefinition def = mapper.readValue(tv.getDefinition(), TemplateDefinition.class);
                byte[] bg = null;
                String bgType = null;
                if (tv.getBackgroundKey() != null) {
                    try (InputStream in = storage.get(tv.getBackgroundKey())) { bg = in.readAllBytes(); }
                    bgType = tv.getBackgroundKey().endsWith(".pdf") ? "application/pdf" : "image";
                }
                bytes = pdf.custom(def, bg, bgType, data, conditionFacts);
                templateId = active.getId();
                templateVersion = active.getPublishedVersion();
            } else {
                bytes = pdf.standard(data);
            }
            String key = "ngos/" + r.getNgoId() + "/receipts/" + r.getId() + ".pdf";
            storage.put(key, bytes, "application/pdf");
            r.generated(number, key, FileInspector.sha256(bytes), templateId, templateVersion, eligible80g, clock.instant());
            event(r, "GENERATED", number);
            Map<String, Object> payload = new HashMap<>();
            payload.put("receiptId", r.getId().toString());
            payload.put("donationId", r.getDonationId().toString());
            payload.put("receiptNumber", number);
            payload.put("downloadUrl", publicLink(r));
            if (r.getDonorUserId() != null) payload.put("donorUserId", r.getDonorUserId().toString());
            events.publish(EventTypes.RECEIPT_GENERATED, r.getNgoId(), r.getId(), payload);
            audit.record(r.getNgoId(), null, "RECEIPT_GENERATED", "RECEIPT", r.getId(), Map.of("number", number));
        } catch (Exception e) {
            log.warn("Receipt {} generation failed (attempt {}): {}", r.getId(), r.getAttempts(), e.getMessage());
            r.failed(e.getClass().getSimpleName() + ": " + e.getMessage(), clock.instant());
            event(r, "GENERATION_FAILED", r.getLastError());
            if (r.getAttempts() >= 5) {
                events.publish(EventTypes.RECEIPT_FAILED, r.getNgoId(), r.getId(), Map.of("attempts", r.getAttempts()));
            }
        }
        return r;
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

    @Scheduled(fixedDelayString = "${ngo.receipts.worker-ms:5000}", initialDelay = 20000)
    public void worker() {
        Instant now = clock.instant();
        List<Receipt> due = new java.util.ArrayList<>(receipts.findByStatusAndUpdatedAtBefore(Receipt.Status.PENDING, now.plusSeconds(1)));
        due.addAll(receipts.findByStatusAndNextAttemptAtBefore(Receipt.Status.GENERATION_FAILED, now));
        // Receipts stuck in GENERATING (crash mid-generation) are retried.
        due.addAll(receipts.findByStatusAndUpdatedAtBefore(Receipt.Status.GENERATING, now.minusSeconds(300)));
        for (Receipt r : due) {
            if (r.getStatus() == Receipt.Status.GENERATING) { r.failed("stuck", now); receipts.save(r); }
            generate(r.getId());
        }
    }

    @Transactional
    public void markDelivered(UUID receiptId) {
        receipts.findById(receiptId).ifPresent(r -> { r.delivered(clock.instant()); event(r, "DELIVERED", null); });
    }

    /** REC-007: void the live receipt and issue a new one (new number) for the same donation. */
    @Transactional
    public Receipt reissue(UUID ngoId, UUID actor, UUID receiptId, String reason) {
        Receipt old = receipts.findByIdAndNgoId(receiptId, ngoId).orElseThrow(() -> ApiException.notFound("Receipt"));
        if (!old.isLive()) throw ApiException.rule("RECEIPT_NOT_LIVE", "This receipt was already voided");
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("REASON_REQUIRED", "Give a reason");
        old.voidIt(reason, true);
        receipts.saveAndFlush(old);
        event(old, "REISSUED", reason);
        Receipt fresh = new Receipt(ngoId, old.getDonationId(), old.getDonorId(), old.getDonorUserId(), old.getAmount());
        fresh.setReissueOf(old.getId());
        receipts.saveAndFlush(fresh);
        event(fresh, "CREATED", "reissue of " + old.getReceiptNumber());
        audit.record(ngoId, actor, "RECEIPT_REISSUED", "RECEIPT", old.getId(), Map.of("reason", reason));
        return fresh;
    }

    /** Donation voided/fully refunded: receipt is voided (history kept). */
    @Transactional
    public void voidForDonation(UUID donationId, String reason) {
        receipts.findByDonationIdOrderByCreatedAtDesc(donationId).stream().filter(Receipt::isLive).forEach(r -> {
            r.voidIt(reason, false);
            event(r, "VOIDED", reason);
            events.publish(EventTypes.RECEIPT_VOIDED, r.getNgoId(), r.getId(), Map.of("reason", reason));
        });
    }

    @Transactional
    public Receipt retry(UUID ngoId, UUID receiptId) {
        Receipt r = receipts.findByIdAndNgoId(receiptId, ngoId).orElseThrow(() -> ApiException.notFound("Receipt"));
        if (r.getStatus() != Receipt.Status.GENERATION_FAILED) throw ApiException.rule("NOT_FAILED", "Only failed receipts can be retried");
        r.retryNow();
        return generate(receiptId);
    }

    public byte[] pdfBytes(Receipt r) {
        if (r.getPdfKey() == null) throw ApiException.rule("RECEIPT_NOT_READY", "The receipt is still being generated");
        try (InputStream in = storage.get(r.getPdfKey())) { return in.readAllBytes(); } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ signed guest links

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
