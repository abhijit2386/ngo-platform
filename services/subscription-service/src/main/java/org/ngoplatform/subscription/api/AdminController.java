package org.ngoplatform.subscription.api;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.ngoplatform.commons.audit.AuditLog;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.security.AccessGuard;
import org.ngoplatform.commons.security.CurrentUser;
import org.ngoplatform.subscription.app.Catalog;
import org.ngoplatform.subscription.app.SubscriptionService;
import org.ngoplatform.subscription.domain.Plan;
import org.ngoplatform.subscription.domain.Records;
import org.ngoplatform.subscription.domain.Repos;
import org.ngoplatform.subscription.domain.Subscription;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** ADMIN-006..010: plan catalogue, entitlements, subscription/payment exceptions, refund review. */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final AccessGuard guard;
    private final Catalog catalog;
    private final Repos.Plans plans;
    private final Repos.Subscriptions subs;
    private final Repos.Payments payments;
    private final Repos.Credits credits;
    private final Repos.Disputes disputes;
    private final SubscriptionService svc;
    private final AuditLog audit;

    public AdminController(AccessGuard guard, Catalog catalog, Repos.Plans plans, Repos.Subscriptions subs,
                           Repos.Payments payments, Repos.Credits credits, Repos.Disputes disputes,
                           SubscriptionService svc, AuditLog audit) {
        this.guard = guard; this.catalog = catalog; this.plans = plans; this.subs = subs; this.payments = payments;
        this.credits = credits; this.disputes = disputes; this.svc = svc; this.audit = audit;
    }

    @GetMapping("/subscriptions/summary")
    public Map<String, Long> summary() {
        guard.requirePlatformSupport();
        Map<String, Long> m = new java.util.LinkedHashMap<>();
        for (Subscription.Status s : Subscription.Status.values()) m.put(s.name(), subs.countByStatus(s));
        return m;
    }

    @GetMapping("/subscriptions/exceptions")
    public Map<String, Object> exceptions() {
        guard.requirePlatformSupport();
        return Map.of(
                "paymentsNeedingReview", payments.findByStatusOrderByCreatedAtAsc(Records.Payment.Status.NEEDS_REVIEW).stream()
                        .map(SubscriptionController::toView).toList(),
                "creditsPendingReview", credits.findByStatusOrderByCreatedAtAsc("PENDING_REVIEW").stream()
                        .map(c -> new Dtos.CreditView(c.getId(), c.getAmount(), c.getReason(), c.getStatus(), c.getCreatedAt())).toList(),
                "openDisputes", disputes.findByStatusOrderByCreatedAtAsc("OPEN").stream().map(SubscriptionController::toView).toList());
    }

    /** Credit resolution: make available for the NGO's next payment, or mark refunded (refund executed at provider). */
    @PostMapping("/subscriptions/credits/{creditId}/{decision}")
    @Transactional
    public Dtos.CreditView resolveCredit(@PathVariable UUID creditId, @PathVariable String decision) {
        CurrentUser u = guard.requirePlatformAdmin();
        Records.Credit c = credits.findById(creditId).orElseThrow(() -> ApiException.notFound("Credit"));
        if (!"PENDING_REVIEW".equals(c.getStatus())) throw ApiException.rule("NOT_PENDING", "Already resolved");
        String status = switch (decision) {
            case "make-available" -> "AVAILABLE";
            case "refunded" -> "REFUNDED";
            case "void" -> "VOID";
            default -> throw ApiException.badRequest("INVALID_DECISION", "make-available | refunded | void");
        };
        c.resolve(status);
        audit.record(c.getNgoId(), u.userId(), "SUBSCRIPTION_CREDIT_" + status, "SUBSCRIPTION_CREDIT", creditId, Map.of());
        return new Dtos.CreditView(c.getId(), c.getAmount(), c.getReason(), c.getStatus(), c.getCreatedAt());
    }

    @PostMapping("/subscriptions/disputes/{id}/resolve")
    @Transactional
    public Dtos.DisputeView resolveDispute(@PathVariable UUID id, @Valid @RequestBody Dtos.Resolution r) {
        CurrentUser u = guard.requirePlatformAdmin();
        Records.Dispute d = disputes.findById(id).orElseThrow(() -> ApiException.notFound("Dispute"));
        if (!"OPEN".equals(d.getStatus())) throw ApiException.rule("NOT_OPEN", "Already resolved");
        d.resolve(r.approve(), r.note());
        audit.record(d.getNgoId(), u.userId(), "SUBSCRIPTION_DISPUTE_" + d.getStatus(), "SUBSCRIPTION_DISPUTE", id, Map.of());
        return SubscriptionController.toView(d);
    }

    @GetMapping("/plans")
    public List<Map<String, Object>> plans() {
        guard.requirePlatformSupport();
        return plans.findAll().stream().map(p -> Map.<String, Object>of("code", p.getCode(), "name", p.getName(),
                "monthlyPrice", p.getMonthlyPrice(), "active", p.isActive(), "entitlements", catalog.entitlements(p.getCode()))).toList();
    }

    /** Price changes apply to new terms only; contracted prices of running terms are preserved. */
    @PutMapping("/plans/{code}/price")
    @Transactional
    public Map<String, Object> price(@PathVariable String code, @Valid @RequestBody Dtos.PlanPrice req) {
        CurrentUser u = guard.requirePlatformAdmin();
        Plan p = plans.findById(code).orElseThrow(() -> ApiException.notFound("Plan"));
        var old = p.getMonthlyPrice();
        p.updatePrice(req.monthlyPrice());
        audit.record(null, u.userId(), "PLAN_PRICE_CHANGED", "PLAN", code, Map.of("from", old.toPlainString(), "to", req.monthlyPrice().toPlainString()));
        return Map.of("code", code, "monthlyPrice", p.getMonthlyPrice());
    }

    @PutMapping("/plans/{code}/entitlements")
    @Transactional
    public Map<String, String> entitlement(@PathVariable String code, @Valid @RequestBody Dtos.EntitlementUpdate req) {
        CurrentUser u = guard.requirePlatformAdmin();
        plans.findById(code).orElseThrow(() -> ApiException.notFound("Plan"));
        catalog.updateEntitlement(code, req.key(), req.value());
        audit.record(null, u.userId(), "ENTITLEMENT_CHANGED", "PLAN", code, Map.of("key", req.key(), "value", req.value()));
        return catalog.entitlements(code);
    }

    @PutMapping("/plans/settings")
    @Transactional
    public Map<String, String> setting(@Valid @RequestBody Dtos.SettingUpdate req) {
        CurrentUser u = guard.requirePlatformAdmin();
        catalog.updateSetting(req.key(), req.value());
        audit.record(null, u.userId(), "BILLING_SETTING_CHANGED", "BILLING_SETTING", req.key(), Map.of("value", req.value()));
        return Map.of(req.key(), req.value());
    }

    /** Operational lever for tests/support: run the lifecycle sweep now. */
    @PostMapping("/subscriptions/lifecycle/run")
    public Map<String, Integer> runLifecycle() {
        guard.requirePlatformAdmin();
        return Map.of("changed", svc.advanceLifecycle());
    }
}
