package org.ngoplatform.donation.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.ngoplatform.commons.audit.AuditLog;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.events.EventPublisher;
import org.ngoplatform.commons.events.EventTypes;
import org.ngoplatform.commons.money.Money;
import org.ngoplatform.commons.security.EntitlementResolver;
import org.ngoplatform.commons.security.Entitlements;
import org.ngoplatform.commons.storage.FileInspector;
import org.ngoplatform.commons.storage.ObjectStorage;
import org.ngoplatform.donation.domain.Donation;
import org.ngoplatform.donation.domain.Records;
import org.ngoplatform.donation.domain.Repos;
import org.ngoplatform.donation.ledger.Ledger;
import org.ngoplatform.donation.provider.PaymentProvider;
import org.ngoplatform.donation.provider.Providers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Donation lifecycle (spec §13): checkout, provider results (webhook or polling), offline donations, corrections,
 * refunds and reconciliation. Every state change is under a row lock and idempotent per provider event.
 */
@Service
public class DonationService {
    private static final Logger log = LoggerFactory.getLogger(DonationService.class);

    public record CheckoutRequest(UUID ngoId, UUID campaignId, UUID projectId, BigDecimal amount, String purpose,
                                  String donorName, String donorEmail, String donorPhone, String donorPan, String donorAddress,
                                  boolean wants80g, String scenario) {}

    public record CheckoutResult(UUID donationId, String status, String provider, String orderId, BigDecimal amount,
                                 Instant expiresAt, boolean mockCheckout) {}

    public record OfflineRequest(UUID donorId, String donorName, String donorEmail, String donorPhone, String donorPan,
                                 BigDecimal amount, Donation.Channel channel, String reference, LocalDate receivedOn,
                                 UUID campaignId, UUID projectId, String purpose, boolean wants80g) {}

    private final Repos.Donations donations;
    private final Repos.Payments payments;
    private final Repos.Events events;
    private final Repos.Settings settings;
    private final Repos.Refunds refunds;
    private final Providers providers;
    private final Clients clients;
    private final Ledger ledger;
    private final EventPublisher publisher;
    private final AuditLog audit;
    private final EntitlementResolver entitlements;
    private final ObjectStorage storage;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final boolean mockEnabled;
    private final Duration orderExpiry;
    private final org.ngoplatform.commons.security.MembershipResolver memberships;

    public DonationService(Repos.Donations donations, Repos.Payments payments, Repos.Events events, Repos.Settings settings,
                           Repos.Refunds refunds, Providers providers, Clients clients, Ledger ledger, EventPublisher publisher,
                           AuditLog audit, EntitlementResolver entitlements, ObjectStorage storage, ObjectMapper mapper,
                           Clock clock, @Value("${ngo.payments.mock-enabled:false}") boolean mockEnabled,
                           @Value("${ngo.payments.order-expiry:PT30M}") Duration orderExpiry,
                           org.ngoplatform.commons.security.MembershipResolver memberships) {
        this.donations = donations; this.payments = payments; this.events = events; this.settings = settings;
        this.refunds = refunds; this.providers = providers; this.clients = clients; this.ledger = ledger;
        this.publisher = publisher; this.audit = audit; this.entitlements = entitlements; this.storage = storage;
        this.mapper = mapper; this.clock = clock; this.mockEnabled = mockEnabled; this.orderExpiry = orderExpiry;
        this.memberships = memberships;
    }

    // ------------------------------------------------------------------ eligibility

    /** Donations are accepted only if the NGO is verified & not on compliance hold, subscription live, payments ACTIVE. */
    Records.PaymentSettings ensureAcceptingDonations(UUID ngoId) {
        return ensureAcceptingDonations(ngoId, clients.ngo(ngoId));
    }

    Records.PaymentSettings ensureAcceptingDonations(UUID ngoId, Map<String, Object> ngo) {
        if (!Boolean.TRUE.equals(ngo.get("donationEligible"))) {
            throw ApiException.rule("NGO_NOT_ACCEPTING_DONATIONS", "This organization is not accepting donations right now");
        }
        Entitlements e = entitlements.get(ngoId);
        if (!e.operationsAllowed()) {
            throw ApiException.rule("NGO_NOT_ACCEPTING_DONATIONS", "This organization is not accepting online donations right now");
        }
        Records.PaymentSettings ps = settings.findById(ngoId)
                .orElseThrow(() -> ApiException.rule("PAYMENTS_NOT_CONFIGURED", "This organization has not set up online payments yet"));
        if (!"ACTIVE".equals(ps.getStatus())) {
            throw ApiException.rule("PAYMENTS_NOT_ACTIVE", "This organization's online payments are not active");
        }
        if ("MOCK".equals(ps.getProvider()) && !mockEnabled) {
            throw ApiException.rule("PROVIDER_NOT_AVAILABLE", "Test payment provider is disabled in this environment");
        }
        return ps;
    }

    // ------------------------------------------------------------------ checkout (DAPP-009..013)

    @Transactional
    public CheckoutResult checkout(CheckoutRequest r, UUID userId, boolean guest) {
        // Owner rule (2026-10-29): an NGO's own administrators and staff do not donate to it through the app.
        if (userId != null && memberships.find(userId, r.ngoId()).filter(org.ngoplatform.commons.security.Membership::isActive).isPresent()) {
            throw ApiException.forbidden("TEAM_MEMBER_DONATION", "Members of this organization's team cannot donate to it in the app");
        }
        Map<String, Object> ngo = clients.ngo(r.ngoId());
        Records.PaymentSettings ps = ensureAcceptingDonations(r.ngoId(), ngo);
        String campaignTitle = r.campaignId() == null ? null : checkCampaignLive(r.ngoId(), r.campaignId());
        if (guest && !ps.isGuestCheckoutAllowed()) throw ApiException.rule("GUEST_NOT_ALLOWED", "Please sign in to donate to this organization");
        if (!Money.isValidAmount(r.amount(), ps.getMinAmount(), ps.getMaxAmount())) {
            throw ApiException.badRequest("INVALID_AMOUNT", "Amount must be between ₹" + ps.getMinAmount().toPlainString()
                    + " and ₹" + ps.getMaxAmount().toPlainString() + " with at most 2 decimals");
        }
        if (r.wants80g() && (r.donorPan() == null || r.donorPan().isBlank())) {
            throw ApiException.badRequest("PAN_REQUIRED_FOR_80G", "PAN is required to request an 80G receipt");
        }
        if (r.wants80g() && (r.donorAddress() == null || r.donorAddress().strip().length() < 10)) {
            throw ApiException.badRequest("ADDRESS_REQUIRED_FOR_80G", "Your address is required on an 80G receipt");
        }
        if (r.wants80g() && !eligible80g(ngo)) {
            throw ApiException.rule("NGO_NOT_80G", "This organization cannot issue 80G receipts");
        }
        if (guest && (r.donorEmail() == null || r.donorEmail().isBlank())) {
            throw ApiException.badRequest("EMAIL_REQUIRED", "Email is required so we can send your receipt");
        }
        // Signed-in donors: the name on the donation and its receipt is the profile name, whatever the app sent.
        String donorName = userId != null ? java.util.Optional.ofNullable(clients.profileName(userId)).orElse(r.donorName().trim())
                : r.donorName().trim();
        UUID donorId = clients.resolveDonor(r.ngoId(), userId, donorName, r.donorEmail(), r.donorPhone(), r.donorPan(), guest);
        Donation d = new Donation(r.ngoId(), donorId, userId, donorName, lower(r.donorEmail()),
                r.wants80g() ? r.donorPan().trim().toUpperCase() : null, r.campaignId(), r.projectId(), Money.round(r.amount()),
                r.purpose(), Donation.Channel.ONLINE, r.wants80g());
        d.details(r.wants80g() ? r.donorAddress().strip() : null, ngo.get("displayName") == null ? null : ngo.get("displayName").toString(),
                campaignTitle);
        d.setExpiresAt(clock.instant().plus(orderExpiry));
        donations.save(d);
        PaymentProvider provider = providers.get(ps.getProvider());
        PaymentProvider.Order order = provider.createPaymentOrder(ps.getMerchantReference(), d.getId().toString(), d.getAmount(), r.scenario());
        Records.Payment p = new Records.Payment(d.getId(), d.getNgoId(), provider.name(), order.orderId(), d.getAmount());
        p.setScenario(r.scenario());
        payments.save(p);
        audit.record(r.ngoId(), userId, "DONATION_CHECKOUT_CREATED", "DONATION", d.getId(),
                Map.of("amount", d.getAmount().toPlainString(), "guest", guest));
        return new CheckoutResult(d.getId(), d.getStatus().name(), provider.name(), order.orderId(), d.getAmount(),
                d.getExpiresAt(), "MOCK".equals(provider.name()));
    }

    /** 80G receipts only while the NGO's 80G registration is verified and still valid. */
    boolean eligible80g(Map<String, Object> ngo) {
        Object until = ngo.get("tax80gValidUntil");
        return Boolean.TRUE.equals(ngo.get("tax80gVerified")) && until != null
                && !LocalDate.parse(until.toString()).isBefore(LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Kolkata"))));
    }

    /** Donations to a campaign are accepted only while it is live and within its dates (India calendar). */
    String checkCampaignLive(UUID ngoId, UUID campaignId) {
        Map<String, Object> c = clients.campaign(campaignId);
        if (!ngoId.toString().equals(String.valueOf(c.get("ngoId")))) throw ApiException.notFound("Campaign");
        LocalDate today = LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Kolkata")));
        if (!"PUBLISHED".equals(c.get("status"))) throw ApiException.rule("CAMPAIGN_NOT_LIVE", "This campaign is not accepting donations");
        if (c.get("startDate") != null && today.isBefore(LocalDate.parse(c.get("startDate").toString()))) {
            throw ApiException.rule("CAMPAIGN_NOT_STARTED", "This campaign has not started yet");
        }
        if (c.get("endDate") != null && today.isAfter(LocalDate.parse(c.get("endDate").toString()))) {
            throw ApiException.rule("CAMPAIGN_ENDED", "This campaign has ended");
        }
        return c.get("title") == null ? null : c.get("title").toString();
    }

    // ------------------------------------------------------------------ provider results

    /** Webhook entry: verify signature, dedupe by provider event id, then apply. */
    @Transactional
    public String onWebhook(String providerName, String body, String signature) {
        PaymentProvider provider = providers.get(providerName);
        PaymentProvider.WebhookEvent ev;
        try {
            ev = provider.verifyWebhook(body, signature);
        } catch (ApiException e) {
            log.warn("Rejected {} webhook with invalid signature", providerName);
            throw e;
        }
        if (events.existsByProviderAndProviderEventId(provider.name(), ev.eventId())) return "DUPLICATE_IGNORED";
        Records.Payment p = ev.orderId() == null ? null : payments.findByProviderOrderId(ev.orderId()).orElse(null);
        events.save(new Records.PaymentEvent(provider.name(), ev.eventId(), p == null ? null : p.getDonationId(), ev.type(),
                true, p == null ? "UNKNOWN_ORDER" : "RECEIVED", body));
        if (p == null) return "UNKNOWN_ORDER";
        return apply(p, ev.state(), ev.paymentId(), ev.amount());
    }

    /** Applies a provider-reported state. Monotonic: never moves PAID back to FAILED (late/out-of-order events). */
    String apply(Records.Payment p, String state, String paymentId, BigDecimal amount) {
        Donation d = donations.lock(p.getDonationId()).orElseThrow();
        Donation.Status target = switch (state) {
            case "PAID" -> Donation.Status.PAID;
            case "AUTHORIZED" -> Donation.Status.AUTHORIZED;
            case "PENDING" -> Donation.Status.PENDING;
            case "FAILED" -> Donation.Status.FAILED;
            case "CANCELLED" -> Donation.Status.CANCELLED;
            default -> null;
        };
        if (target == null) return "IGNORED_STATE";
        if (target == Donation.Status.PAID && amount != null && amount.compareTo(d.getAmount()) != 0) {
            // Amount tampering / mismatch: never mark paid silently.
            publisher.publish(EventTypes.RECON_EXCEPTION, d.getNgoId(), d.getId(),
                    Map.of("kind", "AMOUNT_MISMATCH", "expected", d.getAmount().toPlainString(), "actual", amount.toPlainString()));
            p.update("AMOUNT_MISMATCH", paymentId);
            return "AMOUNT_MISMATCH";
        }
        if (d.getStatus() == target || !d.canMoveTo(target)) {
            log.info("Ignoring {} for donation {} in state {}", state, d.getId(), d.getStatus());
            return "NO_CHANGE";
        }
        p.update(state, paymentId);
        if (target == Donation.Status.PAID) {
            d.markPaid(clock.instant());
            ledger.post(d.getNgoId(), "DONATION_RECEIVED", d.getId().toString(), "Online donation",
                    List.of(Ledger.Line.dr(Ledger.GATEWAY_RECEIVABLE, d.getAmount()), Ledger.Line.cr(Ledger.DONATION_INCOME, d.getAmount())));
            publishPaid(d);
        } else if (target == Donation.Status.FAILED || target == Donation.Status.CANCELLED) {
            d.markFailed(target, state);
            publisher.publish(EventTypes.DONATION_FAILED, d.getNgoId(), d.getId(), Map.of("status", target.name()));
        } else {
            d.moveTo(target);
        }
        return target.name();
    }

    private void publishPaid(Donation d) {
        Map<String, Object> m = new HashMap<>();
        m.put("donationId", d.getId().toString());
        if (d.getDonorId() != null) m.put("donorId", d.getDonorId().toString());
        m.put("amount", d.getAmount().toPlainString());
        m.put("paidAt", d.getPaidAt().toString());
        m.put("channel", d.getChannel().name());
        if (d.getCampaignId() != null) m.put("campaignId", d.getCampaignId().toString());
        if (d.getProjectId() != null) m.put("projectId", d.getProjectId().toString());
        if (d.getDonorUserId() != null) m.put("donorUserId", d.getDonorUserId().toString());
        // Display names only (no personal data): used for thank-you / new-donation messages.
        if (d.getNgoName() != null) m.put("ngoName", d.getNgoName());
        if (d.getCampaignTitle() != null) m.put("campaignTitle", d.getCampaignTitle());
        m.put("wants80g", d.isWants80g());
        publisher.publish(EventTypes.DONATION_PAID, d.getNgoId(), d.getId(), m);
    }

    /** Recovery (app closed, callback lost, provider timeout): ask the provider for the truth. */
    @Transactional
    public Donation refresh(UUID donationId) {
        Donation d = donations.findById(donationId).orElseThrow(() -> ApiException.notFound("Donation"));
        if (d.getChannel() != Donation.Channel.ONLINE) return d;
        if (d.getStatus() == Donation.Status.CREATED || d.getStatus() == Donation.Status.PENDING
                || d.getStatus() == Donation.Status.AUTHORIZED || d.getStatus() == Donation.Status.FAILED) {
            for (Records.Payment p : payments.findByDonationIdOrderByCreatedAtDesc(donationId)) {
                Records.PaymentSettings ps = settings.findById(d.getNgoId()).orElseThrow();
                try {
                    PaymentProvider.Status s = providers.get(p.getProvider()).getPaymentStatus(ps.getMerchantReference(), p.getProviderOrderId());
                    apply(p, s.state(), s.paymentId(), s.amount());
                } catch (ApiException e) {
                    log.warn("Status query failed for order {}: {}", p.getProviderOrderId(), e.code());
                }
            }
        }
        return donations.findById(donationId).orElseThrow();
    }

    /** Scheduled: poll stale orders, expire abandoned ones. */
    @Transactional
    public int recoverStale() {
        Instant now = clock.instant();
        int changed = 0;
        for (Donation d : donations.findByStatusInAndCreatedAtBefore(List.of(Donation.Status.CREATED, Donation.Status.PENDING,
                Donation.Status.AUTHORIZED), now.minus(Duration.ofMinutes(2)))) {
            if (d.getChannel() != Donation.Channel.ONLINE) continue;
            Donation.Status before = d.getStatus();
            Donation after = refresh(d.getId());
            if (after.getStatus() == before && after.getExpiresAt() != null && after.getExpiresAt().isBefore(now)
                    && before == Donation.Status.CREATED) {
                Donation locked = donations.lock(d.getId()).orElseThrow();
                locked.markFailed(Donation.Status.CANCELLED, "EXPIRED");
            }
            if (after.getStatus() != before) changed++;
        }
        return changed;
    }

    // ------------------------------------------------------------------ offline donations (PAY-004/005)

    @Transactional
    public Donation recordOffline(UUID ngoId, UUID actor, OfflineRequest r) {
        if (r.amount() == null || r.amount().signum() <= 0 || r.amount().stripTrailingZeros().scale() > 2
                || r.amount().compareTo(new BigDecimal("100000000")) > 0) {
            throw ApiException.badRequest("INVALID_AMOUNT", "Enter a positive amount with at most 2 decimals");
        }
        if (r.channel() == Donation.Channel.ONLINE) throw ApiException.badRequest("INVALID_CHANNEL", "Use an offline channel");
        if (r.receivedOn() == null || r.receivedOn().isAfter(LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Kolkata"))))) {
            throw ApiException.badRequest("INVALID_DATE", "Received date cannot be in the future");
        }
        if ((r.channel() == Donation.Channel.CHEQUE || r.channel() == Donation.Channel.BANK_TRANSFER
                || r.channel() == Donation.Channel.UPI_DIRECT) && (r.reference() == null || r.reference().isBlank())) {
            throw ApiException.badRequest("REFERENCE_REQUIRED", "Enter the cheque / UTR / transaction reference");
        }
        if (r.wants80g() && (r.donorPan() == null || r.donorPan().isBlank())) {
            throw ApiException.badRequest("PAN_REQUIRED_FOR_80G", "PAN is required for an 80G receipt");
        }
        UUID donorId = r.donorId() != null ? r.donorId()
                : clients.resolveDonor(ngoId, null, r.donorName(), r.donorEmail(), r.donorPhone(), r.donorPan(), false);
        Donation d = new Donation(ngoId, donorId, null, r.donorName().trim(), lower(r.donorEmail()),
                r.wants80g() ? r.donorPan().trim().toUpperCase() : null, r.campaignId(), r.projectId(), Money.round(r.amount()),
                r.purpose(), r.channel(), r.wants80g());
        d.recordOffline(r.reference(), actor, r.receivedOn().atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant());
        donations.save(d);
        String asset = r.channel() == Donation.Channel.CASH ? Ledger.CASH : Ledger.BANK;
        ledger.post(ngoId, "DONATION_RECEIVED", d.getId().toString(), "Offline donation (" + r.channel() + ")",
                List.of(Ledger.Line.dr(asset, d.getAmount()), Ledger.Line.cr(Ledger.DONATION_INCOME, d.getAmount())));
        audit.record(ngoId, actor, "DONATION_RECORDED_OFFLINE", "DONATION", d.getId(),
                Map.of("amount", d.getAmount().toPlainString(), "channel", r.channel().name()));
        publishPaid(d);
        return d;
    }

    @Transactional
    public Donation attachEvidence(UUID ngoId, UUID actor, UUID donationId, byte[] data, String name) {
        Donation d = donations.findByIdAndNgoId(donationId, ngoId).orElseThrow(() -> ApiException.notFound("Donation"));
        if (d.getChannel() == Donation.Channel.ONLINE) throw ApiException.rule("ONLINE_DONATION", "Evidence is for offline donations");
        FileInspector.Inspected f = FileInspector.inspect(data, 10L * 1024 * 1024, java.util.Set.of("application/pdf", "image/png", "image/jpeg"));
        String key = "ngos/" + ngoId + "/donations/" + donationId + "/evidence-" + UUID.randomUUID() + "." + f.extension();
        storage.put(key, data, f.contentType());
        d.setEvidenceKey(key);
        audit.record(ngoId, actor, "DONATION_EVIDENCE_ATTACHED", "DONATION", donationId, Map.of("file", FileInspector.safeName(name)));
        return d;
    }

    /**
     * PAY-017: corrections never edit a posted donation. The original is voided with a reversing journal entry and a
     * corrected donation is recorded; receipts are voided and reissued downstream.
     */
    @Transactional
    public Donation correct(UUID ngoId, UUID actor, UUID donationId, OfflineRequest corrected, String reason) {
        Donation original = donations.lock(donationId).filter(x -> x.getNgoId().equals(ngoId)).orElseThrow(() -> ApiException.notFound("Donation"));
        if (original.getChannel() == Donation.Channel.ONLINE) {
            throw ApiException.rule("ONLINE_NOT_CORRECTABLE", "Online donations are corrected through refunds");
        }
        if (original.isVoided()) throw ApiException.rule("ALREADY_VOIDED", "This donation was already corrected");
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("REASON_REQUIRED", "Give a reason for the correction");
        Donation fixed = recordOffline(ngoId, actor, corrected);
        fixed.setCorrectionOf(original.getId());
        original.voidFor(fixed.getId());
        String asset = original.getChannel() == Donation.Channel.CASH ? Ledger.CASH : Ledger.BANK;
        ledger.post(ngoId, "DONATION_VOIDED", original.getId().toString(), "Correction: " + reason,
                List.of(Ledger.Line.dr(Ledger.DONATION_INCOME, original.getAmount()), Ledger.Line.cr(asset, original.getAmount())));
        publisher.publish(EventTypes.DONATION_VOIDED, ngoId, original.getId(), Map.of("donationId", original.getId().toString(),
                "replacedBy", fixed.getId().toString(), "reason", reason));
        audit.record(ngoId, actor, "DONATION_CORRECTED", "DONATION", original.getId(), Map.of("replacement", fixed.getId().toString()));
        return fixed;
    }

    // ------------------------------------------------------------------ refunds (PAY-014..016)

    @Transactional
    public Records.Refund requestRefund(UUID ngoId, UUID actor, UUID donationId, BigDecimal amount, String reason) {
        Donation d = donations.lock(donationId).filter(x -> x.getNgoId().equals(ngoId)).orElseThrow(() -> ApiException.notFound("Donation"));
        if (d.getStatus() != Donation.Status.PAID && d.getStatus() != Donation.Status.PARTIALLY_REFUNDED) {
            throw ApiException.rule("NOT_REFUNDABLE", "Only paid donations can be refunded");
        }
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2) {
            throw ApiException.badRequest("INVALID_AMOUNT", "Enter a positive refund amount");
        }
        BigDecimal open = refunds.openRefundTotal(donationId);
        if (amount.add(open).compareTo(d.refundable()) > 0) {
            throw ApiException.rule("REFUND_EXCEEDS_AMOUNT", "Refunds (including pending ones) cannot exceed ₹" + d.refundable().subtract(open).toPlainString());
        }
        Records.Refund r = refunds.save(new Records.Refund(donationId, ngoId, Money.round(amount), reason.trim(), actor));
        audit.record(ngoId, actor, "REFUND_REQUESTED", "REFUND", r.getId(), Map.of("amount", amount.toPlainString()));
        publisher.publish(EventTypes.REFUND_REQUESTED, ngoId, r.getId(), Map.of("donationId", donationId.toString(), "amount", amount.toPlainString()));
        return r;
    }

    /** Maker-checker: the approver must differ from the requester (also enforced by a DB constraint). */
    @Transactional
    public Records.Refund decideRefund(UUID ngoId, UUID actor, UUID refundId, boolean approve, String scenario) {
        Records.Refund r = refunds.findByIdAndNgoId(refundId, ngoId).orElseThrow(() -> ApiException.notFound("Refund"));
        if (r.getStatus() != Records.Refund.Status.REQUESTED) throw ApiException.rule("REFUND_NOT_PENDING", "Refund is " + r.getStatus());
        if (r.getRequestedBy().equals(actor)) {
            throw ApiException.forbidden("MAKER_CHECKER", "A different authorised person must approve this refund");
        }
        r.decide(approve, actor);
        audit.record(ngoId, actor, approve ? "REFUND_APPROVED" : "REFUND_REJECTED", "REFUND", refundId, Map.of());
        if (!approve) return r;
        Donation d = donations.lock(r.getDonationId()).orElseThrow();
        if (d.getChannel() == Donation.Channel.ONLINE) {
            Records.Payment p = payments.findByDonationIdOrderByCreatedAtDesc(d.getId()).stream()
                    .filter(x -> x.getProviderPaymentId() != null && "PAID".equals(x.getStatus())).findFirst()
                    .orElseThrow(() -> ApiException.rule("NO_CAPTURED_PAYMENT", "No captured payment to refund"));
            Records.PaymentSettings ps = settings.findById(ngoId).orElseThrow();
            PaymentProvider.RefundResult res = providers.get(p.getProvider())
                    .initiateRefund(ps.getMerchantReference(), p.getProviderPaymentId(), r.getAmount(), scenario);
            r.processing(res.refundId());
            if ("SUCCEEDED".equals(res.state())) completeRefund(d, r);
            else if ("FAILED".equals(res.state())) r.failed("Provider rejected the refund");
        } else {
            // Offline refunds are paid out by the NGO directly; recording completes immediately.
            completeRefund(d, r);
        }
        return r;
    }

    private void completeRefund(Donation d, Records.Refund r) {
        d.recordRefund(r.getAmount());
        r.succeeded();
        String asset = d.getChannel() == Donation.Channel.ONLINE ? Ledger.GATEWAY_RECEIVABLE
                : d.getChannel() == Donation.Channel.CASH ? Ledger.CASH : Ledger.BANK;
        ledger.post(d.getNgoId(), "DONATION_REFUNDED", r.getId().toString(), "Refund",
                List.of(Ledger.Line.dr(Ledger.DONATION_INCOME, r.getAmount()), Ledger.Line.cr(asset, r.getAmount())));
        publisher.publish(EventTypes.DONATION_REFUNDED, d.getNgoId(), d.getId(), Map.of("donationId", d.getId().toString(),
                "refundId", r.getId().toString(), "amount", r.getAmount().toPlainString(),
                "refundedTotal", d.getRefundedAmount().toPlainString(), "status", d.getStatus().name()));
    }

    /** Polls provider for refunds stuck in PROCESSING. */
    @Transactional
    public void pollRefunds() {
        for (Records.Refund r : refunds.findByStatus(Records.Refund.Status.PROCESSING)) {
            Donation d = donations.lock(r.getDonationId()).orElseThrow();
            Records.PaymentSettings ps = settings.findById(d.getNgoId()).orElseThrow();
            Records.Payment p = payments.findByDonationIdOrderByCreatedAtDesc(d.getId()).getFirst();
            PaymentProvider.RefundResult res = providers.get(p.getProvider()).getRefundStatus(ps.getMerchantReference(), r.getProviderRefundId());
            if ("SUCCEEDED".equals(res.state())) completeRefund(d, r);
            else if ("FAILED".equals(res.state())) r.failed("Provider rejected the refund");
        }
    }

    public String json(Object o) {
        try { return mapper.writeValueAsString(o); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private static String lower(String s) { return s == null || s.isBlank() ? null : s.trim().toLowerCase(); }
}
