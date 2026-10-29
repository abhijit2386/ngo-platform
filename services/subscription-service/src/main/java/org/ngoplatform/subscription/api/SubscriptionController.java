package org.ngoplatform.subscription.api;

import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.idempotency.Idempotency;
import org.ngoplatform.commons.security.AccessGuard;
import org.ngoplatform.commons.security.Entitlements;
import org.ngoplatform.commons.security.Membership;
import org.ngoplatform.commons.security.Permissions;
import org.ngoplatform.subscription.app.Catalog;
import org.ngoplatform.subscription.app.InvoicePdf;
import org.ngoplatform.subscription.app.SubscriptionService;
import org.ngoplatform.subscription.domain.Records;
import org.ngoplatform.subscription.domain.Repos;
import org.ngoplatform.subscription.domain.Subscription;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** SUB-001..045 for NGO members. Viewing needs SUBSCRIPTION_VIEW; changing needs SUBSCRIPTION_MANAGE (admins). */
@RestController
@RequestMapping("/api/v1/ngos/{ngoId}/subscription")
public class SubscriptionController {
    private final SubscriptionService svc;
    private final AccessGuard guard;
    private final Catalog catalog;
    private final Idempotency idempotency;
    private final Repos.Payments payments;
    private final Repos.Invoices invoices;
    private final Repos.Changes changes;
    private final Repos.Credits credits;
    private final Repos.Disputes disputes;
    private final InvoicePdf pdf;

    public SubscriptionController(SubscriptionService svc, AccessGuard guard, Catalog catalog, Idempotency idempotency,
                                  Repos.Payments payments, Repos.Invoices invoices, Repos.Changes changes,
                                  Repos.Credits credits, Repos.Disputes disputes, InvoicePdf pdf) {
        this.svc = svc; this.guard = guard; this.catalog = catalog; this.idempotency = idempotency;
        this.payments = payments; this.invoices = invoices; this.changes = changes; this.credits = credits;
        this.disputes = disputes; this.pdf = pdf;
    }

    Dtos.SubscriptionView view(Subscription s) {
        Entitlements e = svc.entitlements(s.getNgoId());
        long days = Math.max(0, Duration.between(Instant.now(), s.getPeriodEnd()).toDays());
        return new Dtos.SubscriptionView(s.getNgoId(), s.getPlanCode(), catalog.plan(s.getPlanCode()).getName(),
                s.getCycle().name(), s.getStatus().name(), s.getPeriodStart(), s.getPeriodEnd(), s.getGraceEndsAt(), days,
                s.getContractedPeriodPrice(), s.getStaffBlocks(), s.getScheduledStaffBlocks(), s.getScheduledPlanCode(),
                s.getScheduledCycle() == null ? null : s.getScheduledCycle().name(), s.isAutoRenew(),
                s.isCancelAtPeriodEnd(), s.isTrialUsed(), s.getBillingContactName(), s.getBillingContactEmail(),
                s.getGstin(), e.values(), e.operationsAllowed(), s.getVersion());
    }

    @GetMapping
    public Dtos.SubscriptionView get(@PathVariable UUID ngoId) {
        guard.member(ngoId); // every member sees plan status (drives UI gating); details need SUBSCRIPTION_VIEW below
        return view(svc.get(ngoId));
    }

    @GetMapping("/entitlements")
    public Entitlements entitlements(@PathVariable UUID ngoId) {
        guard.member(ngoId);
        return svc.entitlements(ngoId);
    }

    @PutMapping("/trial-plan")
    public Dtos.SubscriptionView trialPlan(@PathVariable UUID ngoId, @Valid @RequestBody Dtos.TrialPlan req) {
        Membership m = guard.require(ngoId, Permissions.SUBSCRIPTION_MANAGE);
        return view(svc.changeTrialPlan(ngoId, req.plan(), m.userId()));
    }

    /** SUB-018 / SUB-025: price preview (no side effects). */
    @PostMapping("/quote")
    public SubscriptionService.PricedQuote quote(@PathVariable UUID ngoId, @Valid @RequestBody Dtos.CheckoutRequest req) {
        guard.require(ngoId, Permissions.SUBSCRIPTION_VIEW);
        return svc.price(ngoId, new SubscriptionService.CheckoutRequest(req.plan(), req.cycle(), req.blocks()));
    }

    /** SUB-013: creates a payment order. Requires Idempotency-Key so retries never create two orders. */
    @PostMapping("/checkout")
    @Transactional
    public SubscriptionService.Checkout checkout(@PathVariable UUID ngoId, @Valid @RequestBody Dtos.CheckoutRequest req,
                                                 @RequestHeader("Idempotency-Key") String key) {
        Membership m = guard.require(ngoId, Permissions.SUBSCRIPTION_MANAGE);
        return idempotency.execute("subscription-checkout:" + ngoId, key, req, SubscriptionService.Checkout.class,
                () -> svc.checkout(ngoId, new SubscriptionService.CheckoutRequest(req.plan(), req.cycle(), req.blocks()), m.userId()));
    }

    @GetMapping("/payments/{orderId}")
    public Dtos.PaymentView payment(@PathVariable UUID ngoId, @PathVariable String orderId) {
        guard.require(ngoId, Permissions.SUBSCRIPTION_VIEW);
        Records.Payment p = payments.findByProviderOrderId(orderId).filter(x -> x.getNgoId().equals(ngoId))
                .orElseThrow(() -> ApiException.notFound("Payment"));
        return toView(p);
    }

    static Dtos.PaymentView toView(Records.Payment p) {
        return new Dtos.PaymentView(p.getId(), p.getQuoteId(), p.getProvider(), p.getProviderOrderId(), p.getAmount(),
                p.getPaidAmount(), p.getStatus().name(), p.getFailureReason(), p.getCreatedAt());
    }

    @GetMapping("/downgrade-impact")
    public SubscriptionService.DowngradeImpact impact(@PathVariable UUID ngoId, @RequestParam String plan) {
        guard.require(ngoId, Permissions.SUBSCRIPTION_VIEW);
        return svc.downgradeImpact(ngoId, plan);
    }

    @PostMapping("/downgrade")
    public Dtos.SubscriptionView downgrade(@PathVariable UUID ngoId, @Valid @RequestBody Dtos.Downgrade req) {
        Membership m = guard.require(ngoId, Permissions.SUBSCRIPTION_MANAGE);
        return view(svc.scheduleDowngrade(ngoId, req.plan(), req.cycle(), req.confirmed(), m.userId()));
    }

    @DeleteMapping("/downgrade")
    public Dtos.SubscriptionView cancelDowngrade(@PathVariable UUID ngoId) {
        Membership m = guard.require(ngoId, Permissions.SUBSCRIPTION_MANAGE);
        return view(svc.cancelScheduledDowngrade(ngoId, m.userId()));
    }

    @PutMapping("/staff-blocks/scheduled")
    public Dtos.SubscriptionView scheduleBlocks(@PathVariable UUID ngoId, @Valid @RequestBody Dtos.Blocks req) {
        Membership m = guard.require(ngoId, Permissions.SUBSCRIPTION_MANAGE);
        return view(svc.scheduleBlocks(ngoId, req.blocks(), m.userId()));
    }

    @PutMapping("/cancellation")
    public Dtos.SubscriptionView cancellation(@PathVariable UUID ngoId, @Valid @RequestBody Dtos.Cancellation req) {
        Membership m = guard.requireAdmin(ngoId);
        return view(svc.setCancellation(ngoId, req.cancel(), req.reason(), m.userId()));
    }

    @PutMapping("/auto-renew")
    public Dtos.SubscriptionView autoRenew(@PathVariable UUID ngoId, @RequestBody Dtos.Toggle req) {
        Membership m = guard.require(ngoId, Permissions.SUBSCRIPTION_MANAGE);
        return view(svc.setAutoRenew(ngoId, req.enabled(), m.userId()));
    }

    @PutMapping("/billing-contact")
    public Dtos.SubscriptionView billingContact(@PathVariable UUID ngoId, @Valid @RequestBody Dtos.BillingContact req) {
        Membership m = guard.require(ngoId, Permissions.SUBSCRIPTION_MANAGE);
        return view(svc.billingContact(ngoId, req.name().trim(), req.email().trim().toLowerCase(), req.gstin(), m.userId()));
    }

    @GetMapping("/payments")
    public List<Dtos.PaymentView> payments(@PathVariable UUID ngoId) {
        guard.require(ngoId, Permissions.SUBSCRIPTION_VIEW);
        return payments.findByNgoIdOrderByCreatedAtDesc(ngoId).stream().map(SubscriptionController::toView).toList();
    }

    @GetMapping("/invoices")
    public List<Dtos.InvoiceView> invoices(@PathVariable UUID ngoId) {
        guard.require(ngoId, Permissions.SUBSCRIPTION_VIEW);
        return invoices.findByNgoIdOrderByIssuedAtDesc(ngoId).stream().map(i -> new Dtos.InvoiceView(i.getId(), i.getNumber(),
                i.getTotal(), i.getTaxableAmount(), i.getGstAmount(), i.getStatus(), i.getIssuedAt(), i.getPeriodStart(),
                i.getPeriodEnd())).toList();
    }

    @GetMapping("/invoices/{invoiceId}/pdf")
    public ResponseEntity<byte[]> invoicePdf(@PathVariable UUID ngoId, @PathVariable UUID invoiceId) {
        guard.require(ngoId, Permissions.SUBSCRIPTION_VIEW);
        Records.Invoice inv = invoices.findByIdAndNgoId(invoiceId, ngoId).orElseThrow(() -> ApiException.notFound("Invoice"));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + inv.getNumber().replace('/', '-') + ".pdf\"")
                .body(pdf.render(inv, null));
    }

    @GetMapping("/history")
    public List<Dtos.ChangeView> history(@PathVariable UUID ngoId) {
        guard.require(ngoId, Permissions.SUBSCRIPTION_VIEW);
        return changes.findByNgoIdOrderByCreatedAtDesc(ngoId).stream().map(c -> new Dtos.ChangeView(c.getKind(),
                c.getFromPlan(), c.getToPlan(), c.getFromStatus(), c.getToStatus(), c.getBlocks(), c.getAmount(),
                c.getNote(), c.getCreatedAt())).toList();
    }

    @GetMapping("/credits")
    public List<Dtos.CreditView> credits(@PathVariable UUID ngoId) {
        guard.require(ngoId, Permissions.SUBSCRIPTION_VIEW);
        return credits.findByNgoIdOrderByCreatedAtDesc(ngoId).stream()
                .map(c -> new Dtos.CreditView(c.getId(), c.getAmount(), c.getReason(), c.getStatus(), c.getCreatedAt())).toList();
    }

    @PostMapping("/disputes")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Dtos.DisputeView dispute(@PathVariable UUID ngoId, @Valid @RequestBody Dtos.DisputeRequest req) {
        Membership m = guard.requireAdmin(ngoId);
        if (req.paymentId() != null) {
            payments.findById(req.paymentId()).filter(p -> p.getNgoId().equals(ngoId))
                    .orElseThrow(() -> ApiException.notFound("Payment"));
        }
        Records.Dispute d = disputes.save(new Records.Dispute(ngoId, req.paymentId(), req.kind(), req.reason(), m.userId()));
        return toView(d);
    }

    @GetMapping("/disputes")
    public List<Dtos.DisputeView> disputes(@PathVariable UUID ngoId) {
        guard.require(ngoId, Permissions.SUBSCRIPTION_VIEW);
        return disputes.findByNgoIdOrderByCreatedAtDesc(ngoId).stream().map(SubscriptionController::toView).toList();
    }

    static Dtos.DisputeView toView(Records.Dispute d) {
        return new Dtos.DisputeView(d.getId(), d.getPaymentId(), d.getKind(), d.getReason(), d.getStatus(),
                d.getResolution(), d.getCreatedAt());
    }
}
