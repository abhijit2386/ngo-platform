package org.ngoplatform.subscription.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.ngoplatform.commons.audit.AuditLog;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.events.EventPublisher;
import org.ngoplatform.commons.events.EventTypes;
import org.ngoplatform.commons.money.Money;
import org.ngoplatform.commons.security.Entitlements;
import org.ngoplatform.subscription.billing.BillingProvider;
import org.ngoplatform.subscription.domain.Plan;
import org.ngoplatform.subscription.domain.Records;
import org.ngoplatform.subscription.domain.Repos;
import org.ngoplatform.subscription.domain.Subscription;
import org.ngoplatform.subscription.domain.Subscription.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Subscription lifecycle (spec §7). Every money-changing operation locks the subscription row; quotes carry the
 * subscription version they were priced against so stale or concurrent payments are detected, never mis-applied.
 */
@Service
public class SubscriptionService {
    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);
    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public enum Kind { NEW_TERM, UPGRADE, ADD_BLOCKS, RENEWAL, REACTIVATION }

    public record CheckoutRequest(String plan, Pricing.Cycle cycle, int blocks) {}

    public record PricedQuote(Kind kind, String plan, Pricing.Cycle cycle, int blocks, List<Pricing.Line> lines,
                              BigDecimal total, BigDecimal taxable, BigDecimal gst, Instant termStart, Instant termEnd) {}

    public record Checkout(UUID quoteId, UUID paymentId, String provider, String orderId, BigDecimal amount,
                           Instant expiresAt, PricedQuote quote) {}

    private final Repos.Subscriptions subs;
    private final Repos.Quotes quotes;
    private final Repos.Payments payments;
    private final Repos.Invoices invoices;
    private final Repos.Changes changes;
    private final Repos.Credits credits;
    private final Catalog catalog;
    private final BillingProvider billing;
    private final EventPublisher events;
    private final AuditLog audit;
    private final ObjectMapper mapper;
    private final Clock clock;

    public SubscriptionService(Repos.Subscriptions subs, Repos.Quotes quotes, Repos.Payments payments,
                               Repos.Invoices invoices, Repos.Changes changes, Repos.Credits credits, Catalog catalog,
                               BillingProvider billing, EventPublisher events, AuditLog audit, ObjectMapper mapper,
                               Clock clock) {
        this.subs = subs; this.quotes = quotes; this.payments = payments; this.invoices = invoices;
        this.changes = changes; this.credits = credits; this.catalog = catalog; this.billing = billing;
        this.events = events; this.audit = audit; this.mapper = mapper; this.clock = clock;
    }

    Instant now() { return clock.instant(); }

    static Instant addMonths(Instant start, int months) {
        return start.atZone(IST).plusMonths(months).toInstant();
    }

    static LocalDate day(Instant i) { return i.atZone(IST).toLocalDate(); }

    // ------------------------------------------------------------------ trial

    /** Trial begins when the verified workspace becomes usable (NGO_VERIFIED). One trial per NGO. */
    @Transactional
    public Subscription startTrial(UUID ngoId) {
        var existing = subs.findByNgoId(ngoId);
        if (existing.isPresent()) return existing.get();
        Instant start = now();
        Subscription s = Subscription.startTrial(ngoId, catalog.setting("default_trial_plan"), start,
                start.plus(Duration.ofDays(catalog.intSetting("trial_days"))));
        subs.save(s);
        changes.save(new Records.Change(ngoId, "TRIAL_STARTED", null, s.getPlanCode(), null, "TRIAL", null, null, null, null));
        publishStatus(s, null);
        return s;
    }

    @Transactional
    public Subscription changeTrialPlan(UUID ngoId, String plan, UUID actor) {
        Subscription s = lock(ngoId);
        if (s.getStatus() != Status.TRIAL) throw ApiException.rule("NOT_IN_TRIAL", "Plan can be switched freely only during the trial");
        Plan p = catalog.plan(plan);
        String old = s.getPlanCode();
        s.changeTrialPlan(p.getCode());
        changes.save(new Records.Change(ngoId, "TRIAL_PLAN_CHANGED", old, p.getCode(), "TRIAL", "TRIAL", null, null, actor, null));
        events.publish(EventTypes.SUBSCRIPTION_CHANGED, ngoId, s.getId(), Map.of("plan", p.getCode()));
        return s;
    }

    // ------------------------------------------------------------------ entitlements

    @Transactional(readOnly = true)
    public Entitlements entitlements(UUID ngoId) {
        return subs.findByNgoId(ngoId).map(this::entitlementsOf)
                .orElseGet(() -> new Entitlements(ngoId, "NONE", "NONE", Map.of()));
    }

    Entitlements entitlementsOf(Subscription s) {
        Map<String, String> values = new HashMap<>(catalog.entitlements(s.getPlanCode()));
        if (s.getStaffBlocks() > 0 && catalog.plan(s.getPlanCode()).isStaffBlocksAllowed()) {
            int base = Integer.parseInt(values.getOrDefault(Entitlements.STAFF_MAX, "0"));
            if (base >= 0) {
                values.put(Entitlements.STAFF_MAX, String.valueOf(base + s.getStaffBlocks() * catalog.intSetting("staff_block_size")));
            }
        }
        return new Entitlements(s.getNgoId(), s.getPlanCode(), s.getStatus().name(), values);
    }

    // ------------------------------------------------------------------ pricing

    public Subscription get(UUID ngoId) {
        return subs.findByNgoId(ngoId).orElseThrow(() -> ApiException.rule("NO_SUBSCRIPTION",
                "Subscription starts after your organization is verified"));
    }

    private Subscription lock(UUID ngoId) {
        return subs.lockByNgoId(ngoId).orElseThrow(() -> ApiException.rule("NO_SUBSCRIPTION",
                "Subscription starts after your organization is verified"));
    }

    /** Determines what a checkout means for the current subscription and prices it. */
    @Transactional(readOnly = true)
    public PricedQuote price(UUID ngoId, CheckoutRequest req) {
        return price(get(ngoId), req);
    }

    PricedQuote price(Subscription s, CheckoutRequest req) {
        Pricing pricing = catalog.pricing();
        Plan target = catalog.plan(req.plan());
        Plan current = catalog.plan(s.getPlanCode());
        if (req.blocks() < 0 || req.blocks() > 100) throw ApiException.badRequest("INVALID_BLOCKS", "Staff blocks must be 0-100");
        if (req.blocks() > 0 && !target.isStaffBlocksAllowed()) {
            throw ApiException.rule("BLOCKS_NOT_ALLOWED", "Only Enterprise can purchase additional staff. Upgrade to add more staff.");
        }
        Instant now = now();
        List<Pricing.Line> lines = Pricing.lines();
        Status st = s.getStatus();
        int months = Pricing.months(req.cycle());

        if (st == Status.TRIAL || st == Status.EXPIRED || st == Status.SUSPENDED || st == Status.CANCELLED
                || st == Status.ARCHIVED) {
            Kind kind = st == Status.TRIAL ? Kind.NEW_TERM : Kind.REACTIVATION;
            // Paying during a trial keeps the remaining trial days; reactivation charges only the new period.
            Instant start = st == Status.TRIAL && s.getPeriodEnd().isAfter(now) ? s.getPeriodEnd() : now;
            Instant end = addMonths(start, months);
            lines.add(new Pricing.Line("PLAN", target.getName() + " plan, " + label(req.cycle()),
                    pricing.periodPrice(target.getMonthlyPrice(), req.cycle())));
            if (req.blocks() > 0) {
                lines.add(new Pricing.Line("STAFF_BLOCKS", req.blocks() + " x 10 additional staff, " + label(req.cycle()),
                        Money.round(pricing.blockPeriodPrice(req.cycle()).multiply(BigDecimal.valueOf(req.blocks())))));
            }
            return priced(pricing, kind, target, req, lines, start, end);
        }

        // ACTIVE or GRACE from here on.
        boolean renewalWindow = st == Status.GRACE || !s.getPeriodEnd().isAfter(now.plus(Duration.ofDays(30)));
        if (target.getRank() == current.getRank() && req.cycle().name().equals(s.getCycle().name())
                && req.blocks() > s.getStaffBlocks() && st == Status.ACTIVE && !renewalWindow) {
            int add = req.blocks() - s.getStaffBlocks();
            BigDecimal remaining = Pricing.remainingFraction(day(s.getPeriodStart()), day(s.getPeriodEnd()), day(now));
            lines.add(new Pricing.Line("STAFF_BLOCKS_PRORATED", add + " x 10 additional staff (prorated)",
                    pricing.proratedBlocks(add, req.cycle(), remaining)));
            return priced(pricing, Kind.ADD_BLOCKS, target, req, lines, s.getPeriodStart(), s.getPeriodEnd());
        }
        if (target.getRank() > current.getRank() && st == Status.ACTIVE) {
            if (!req.cycle().name().equals(s.getCycle().name())) {
                throw ApiException.rule("CYCLE_CHANGE_AT_RENEWAL", "Billing cycle can be changed at renewal; upgrade within your current cycle");
            }
            BigDecimal remaining = Pricing.remainingFraction(day(s.getPeriodStart()), day(s.getPeriodEnd()), day(now));
            BigDecimal diff = pricing.upgradeDifference(s.getContractedPeriodPrice(),
                    pricing.periodPrice(target.getMonthlyPrice(), req.cycle()), remaining);
            lines.add(new Pricing.Line("UPGRADE_PRORATED", current.getName() + " to " + target.getName()
                    + " for the remaining " + remainingDays(s, now) + " days", diff));
            int extraBlocks = req.blocks() - (target.isStaffBlocksAllowed() ? s.getStaffBlocks() : 0);
            if (extraBlocks > 0) {
                lines.add(new Pricing.Line("STAFF_BLOCKS_PRORATED", extraBlocks + " x 10 additional staff (prorated)",
                        pricing.proratedBlocks(extraBlocks, req.cycle(), remaining)));
            }
            return priced(pricing, Kind.UPGRADE, target, req, lines, s.getPeriodStart(), s.getPeriodEnd());
        }
        if (renewalWindow) {
            if (s.isCancelAtPeriodEnd() && st == Status.ACTIVE) {
                throw ApiException.rule("CANCELLATION_PENDING", "Undo the cancellation to renew");
            }
            Instant start = st == Status.GRACE ? s.getPeriodEnd() : s.getPeriodEnd();
            Instant end = addMonths(start, months);
            if (!end.isAfter(now)) { start = now; end = addMonths(now, months); }
            lines.add(new Pricing.Line("PLAN", target.getName() + " plan renewal, " + label(req.cycle()),
                    pricing.periodPrice(target.getMonthlyPrice(), req.cycle())));
            if (req.blocks() > 0) {
                lines.add(new Pricing.Line("STAFF_BLOCKS", req.blocks() + " x 10 additional staff, " + label(req.cycle()),
                        Money.round(pricing.blockPeriodPrice(req.cycle()).multiply(BigDecimal.valueOf(req.blocks())))));
            }
            return priced(pricing, Kind.RENEWAL, target, req, lines, start, end);
        }
        if (target.getRank() < current.getRank()) {
            throw ApiException.rule("DOWNGRADE_AT_RENEWAL", "Downgrades take effect at renewal. Use 'Schedule downgrade'.");
        }
        throw ApiException.rule("NOTHING_TO_PAY", "Your subscription already covers this. Renewal opens 30 days before expiry.");
    }

    private PricedQuote priced(Pricing pricing, Kind kind, Plan target, CheckoutRequest req, List<Pricing.Line> lines,
                               Instant start, Instant end) {
        Pricing.Quote q = pricing.quote(lines);
        return new PricedQuote(kind, target.getCode(), req.cycle(), req.blocks(), q.lines(), q.total(), q.taxable(),
                q.gst(), start, end);
    }

    private static long remainingDays(Subscription s, Instant now) {
        return Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(day(now), day(s.getPeriodEnd())));
    }

    private static String label(Pricing.Cycle c) { return c == Pricing.Cycle.ANNUAL ? "12 months" : "3 months"; }

    // ------------------------------------------------------------------ checkout

    @Transactional
    public Checkout checkout(UUID ngoId, CheckoutRequest req, UUID actor) {
        Subscription s = lock(ngoId);
        PricedQuote pq = price(s, req);
        if (pq.total().signum() <= 0) {
            throw ApiException.rule("NOTHING_TO_PAY", "This change has no charge");
        }
        if (pq.kind() == Kind.UPGRADE || pq.kind() == Kind.RENEWAL || pq.kind() == Kind.NEW_TERM || pq.kind() == Kind.REACTIVATION) {
            ensureStaffFits(ngoId, pq.plan(), pq.blocks());
        }
        // Only one open quote per NGO: superseded quotes are cancelled (their orders become unpayable).
        for (Records.Quote open : quotes.findByNgoIdAndStatus(ngoId, Records.Quote.Status.OPEN)) {
            open.setStatus(Records.Quote.Status.CANCELLED);
        }
        quotes.flush();
        Instant expires = now().plus(Duration.ofMinutes(catalog.intSetting("quote_ttl_minutes")));
        Records.Quote q = quotes.save(new Records.Quote(ngoId, Records.Quote.Kind.valueOf(pq.kind().name()), pq.plan(),
                pq.cycle().name(), pq.blocks(), pq.total(), json(pq), s.getVersion(), expires, actor));
        BillingProvider.Order order = billing.createOrder(q.getId().toString(), pq.total());
        Records.Payment p = payments.save(new Records.Payment(ngoId, q.getId(), billing.name(), order.orderId(), pq.total()));
        audit.record(ngoId, actor, "SUBSCRIPTION_CHECKOUT_CREATED", "SUBSCRIPTION_QUOTE", q.getId(),
                Map.of("kind", pq.kind().name(), "plan", pq.plan(), "amount", pq.total().toPlainString()));
        return new Checkout(q.getId(), p.getId(), billing.name(), order.orderId(), pq.total(), expires, pq);
    }

    /** Staff capacity of the target must hold everyone currently active (spec §6.3 / §7.6). */
    private void ensureStaffFits(UUID ngoId, String plan, int blocks) {
        Long active = usage.activeStaff(ngoId);
        if (active == null) return; // identity unreachable: payment application re-checks nothing; capacity enforced on invites
        int base = Integer.parseInt(catalog.entitlements(plan).getOrDefault(Entitlements.STAFF_MAX, "0"));
        if (base < 0) return;
        long capacity = base + (long) blocks * catalog.intSetting("staff_block_size");
        if (active > capacity) {
            throw ApiException.rule("STAFF_CONFLICT", active + " staff are active but " + plan + " allows " + capacity
                    + ". Deactivate " + (active - capacity) + " staff or choose a larger plan/more blocks.");
        }
    }

    private UsageClient usage;

    @org.springframework.beans.factory.annotation.Autowired
    void setUsage(UsageClient usage) { this.usage = usage; }

    private String json(Object o) {
        try { return mapper.writeValueAsString(o); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    // ------------------------------------------------------------------ payment results

    /**
     * Applies a verified provider result. Idempotent per order; tolerant of duplicates, late arrival and wrong amounts.
     * Money received that cannot be applied safely is parked as a credit for review, never lost or silently applied.
     */
    @Transactional
    public Records.Payment onPaymentResult(BillingProvider.WebhookEvent ev) {
        Records.Payment p = payments.findByProviderOrderId(ev.orderId())
                .orElseThrow(() -> ApiException.notFound("Payment order"));
        if (p.getStatus() == Records.Payment.Status.PAID || p.getStatus() == Records.Payment.Status.NEEDS_REVIEW
                || p.getStatus() == Records.Payment.Status.REFUNDED) {
            log.info("Duplicate billing callback ignored for order {}", ev.orderId());
            return p;
        }
        switch (ev.outcome()) {
            case "PENDING" -> { p.markPending(); return p; }
            case "FAILED", "CANCELLED" -> {
                if (p.getStatus() != Records.Payment.Status.FAILED) {
                    p.markFailed(ev.outcome());
                    events.publish(EventTypes.SUBSCRIPTION_PAYMENT_FAILED, p.getNgoId(), p.getId(),
                            Map.of("amount", p.getAmount().toPlainString()));
                }
                return p;
            }
            case "SUCCESS" -> { /* continue */ }
            default -> throw ApiException.badRequest("UNKNOWN_OUTCOME", "Unknown outcome " + ev.outcome());
        }
        Subscription s = lock(p.getNgoId());
        Records.Quote q = quotes.findById(p.getQuoteId()).orElseThrow();
        String problem = null;
        if (ev.amount() == null || ev.amount().compareTo(p.getAmount()) != 0) problem = "AMOUNT_MISMATCH";
        else if (q.getStatus() == Records.Quote.Status.CANCELLED) problem = "QUOTE_SUPERSEDED";
        else if (q.getSubscriptionVersion() != s.getVersion()) problem = "STALE_SUBSCRIPTION_VERSION";
        if (problem != null) {
            p.markReview(ev.paymentId(), ev.amount(), problem);
            q.setStatus(Records.Quote.Status.NEEDS_REVIEW);
            if (ev.amount() != null && ev.amount().signum() > 0) {
                credits.save(new Records.Credit(p.getNgoId(), ev.amount(), "Payment received but not applied: " + problem,
                        p.getId(), "PENDING_REVIEW"));
            }
            changes.save(new Records.Change(p.getNgoId(), "PAYMENT_NEEDS_REVIEW", s.getPlanCode(), q.getTargetPlan(),
                    s.getStatus().name(), s.getStatus().name(), null, ev.amount(), null, problem));
            audit.record(p.getNgoId(), null, "SUBSCRIPTION_PAYMENT_NEEDS_REVIEW", "SUBSCRIPTION_PAYMENT", p.getId(), Map.of("reason", problem));
            return p;
        }
        // A late success for an expired-but-unchanged quote is still applied: the price was accepted and nothing changed.
        p.markPaid(ev.paymentId(), ev.amount());
        q.setStatus(Records.Quote.Status.PAID);
        apply(s, q, p);
        return p;
    }

    private void apply(Subscription s, Records.Quote q, Records.Payment p) {
        PricedQuote pq;
        try { pq = mapper.readValue(q.getBreakdown(), PricedQuote.class); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
        Pricing pricing = catalog.pricing();
        Plan target = catalog.plan(q.getTargetPlan());
        Pricing.Cycle cycle = Pricing.Cycle.valueOf(q.getTargetCycle());
        String fromPlan = s.getPlanCode();
        Status fromStatus = s.getStatus();
        switch (q.getKind()) {
            case NEW_TERM, REACTIVATION, RENEWAL -> s.startTerm(target.getCode(), Subscription.Cycle.valueOf(cycle.name()),
                    pq.termStart(), pq.termEnd(), pricing.periodPrice(target.getMonthlyPrice(), cycle), q.getTargetBlocks(),
                    pricing.blockPeriodPrice(cycle));
            case UPGRADE -> {
                s.upgrade(target.getCode(), pricing.periodPrice(target.getMonthlyPrice(), cycle));
                if (target.isStaffBlocksAllowed() && q.getTargetBlocks() > s.getStaffBlocks()) {
                    s.addBlocks(q.getTargetBlocks() - s.getStaffBlocks(), pricing.blockPeriodPrice(cycle));
                }
            }
            case ADD_BLOCKS -> s.addBlocks(q.getTargetBlocks() - s.getStaffBlocks(), pricing.blockPeriodPrice(cycle));
        }
        subs.saveAndFlush(s);
        issueInvoice(s, p, pq);
        changes.save(new Records.Change(s.getNgoId(), q.getKind().name(), fromPlan, target.getCode(), fromStatus.name(),
                s.getStatus().name(), s.getStaffBlocks(), p.getPaidAmount(), q.getCreatedBy(), null));
        audit.record(s.getNgoId(), q.getCreatedBy(), "SUBSCRIPTION_" + q.getKind().name() + "_APPLIED", "SUBSCRIPTION",
                s.getId(), Map.of("plan", target.getCode(), "amount", p.getPaidAmount().toPlainString()));
        events.publish(EventTypes.SUBSCRIPTION_ACTIVATED, s.getNgoId(), s.getId(),
                Map.of("plan", s.getPlanCode(), "status", s.getStatus().name(), "periodEnd", s.getPeriodEnd().toString(),
                        "kind", q.getKind().name()));
        publishStatus(s, fromStatus);
    }

    private void issueInvoice(Subscription s, Records.Payment p, PricedQuote pq) {
        if (invoices.existsByPaymentId(p.getId())) return;
        LocalDate today = day(now());
        int fyStart = today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1;
        String number = String.format("INV/%d-%02d/%06d", fyStart, (fyStart + 1) % 100, invoices.nextNumber());
        invoices.save(new Records.Invoice(number, s.getNgoId(), p.getId(), pq.total(), pq.taxable(), pq.gst(),
                json(pq.lines()), s.getBillingContactName(), s.getGstin(), pq.termStart(), pq.termEnd()));
    }

    // ------------------------------------------------------------------ downgrade / blocks / cancel

    public record DowngradeImpact(String fromPlan, String toPlan, long activeStaff, long staffLimit, long excessStaff,
                                  Long activeCampaigns, long campaignLimit, Long activeProjects, long projectLimit,
                                  Long customTemplates, boolean receiptDesignerLost, List<String> featuresLost,
                                  boolean blocking, Instant effectiveAt) {}

    @Transactional(readOnly = true)
    public DowngradeImpact downgradeImpact(UUID ngoId, String plan) {
        Subscription s = get(ngoId);
        Plan target = catalog.plan(plan);
        Plan current = catalog.plan(s.getPlanCode());
        if (target.getRank() >= current.getRank()) throw ApiException.rule("NOT_A_DOWNGRADE", "Choose a lower plan");
        Map<String, String> now = entitlementsOf(s).values();
        Map<String, String> next = catalog.entitlements(plan);
        long staffLimit = Long.parseLong(next.getOrDefault(Entitlements.STAFF_MAX, "0"));
        Long active = usage.activeStaff(ngoId);
        long activeStaff = active == null ? -1 : active;
        long excess = staffLimit < 0 || active == null ? 0 : Math.max(0, active - staffLimit);
        Map<String, Long> ops = usage.operationsUsage(ngoId);
        Long templates = usage.customTemplates(ngoId);
        List<String> lost = new ArrayList<>();
        for (String k : List.of(Entitlements.RECEIPT_BASIC_DESIGNER, Entitlements.RECEIPT_ADVANCED_DESIGNER,
                Entitlements.PRIORITY_SUPPORT)) {
            if ("true".equals(now.get(k)) && !"true".equals(next.get(k))) lost.add(k);
        }
        for (String k : List.of(Entitlements.REPORTING_LEVEL, Entitlements.VOLUNTEER_FEATURE_LEVEL,
                Entitlements.DONOR_SEGMENTATION_LEVEL, Entitlements.CUSTOM_REPORTING)) {
            if (!java.util.Objects.equals(now.get(k), next.get(k))) lost.add(k + ":" + now.get(k) + "->" + next.get(k));
        }
        long campaignLimit = Long.parseLong(next.getOrDefault(Entitlements.CAMPAIGN_ACTIVE_MAX, "0"));
        long projectLimit = Long.parseLong(next.getOrDefault(Entitlements.PROJECT_ACTIVE_MAX, "0"));
        Long campaigns = ops == null ? null : ops.get("activeCampaigns");
        Long projects = ops == null ? null : ops.get("activeProjects");
        boolean blocking = excess > 0;
        return new DowngradeImpact(current.getCode(), plan, activeStaff, staffLimit, excess, campaigns, campaignLimit,
                projects, projectLimit, templates, lost.stream().anyMatch(x -> x.startsWith("RECEIPT_")), lost, blocking,
                s.getPeriodEnd());
    }

    @Transactional
    public Subscription scheduleDowngrade(UUID ngoId, String plan, Pricing.Cycle cycle, boolean confirmed, UUID actor) {
        DowngradeImpact impact = downgradeImpact(ngoId, plan);
        if (!confirmed) throw ApiException.rule("CONFIRMATION_REQUIRED", "Review the downgrade impact and confirm");
        if (impact.blocking()) {
            throw ApiException.rule("STAFF_CONFLICT", impact.excessStaff() + " staff exceed the " + plan
                    + " limit. Deactivate them before scheduling this downgrade.");
        }
        Subscription s = lock(ngoId);
        if (s.getStatus() != Status.ACTIVE) throw ApiException.rule("NOT_ACTIVE", "Only active subscriptions can be downgraded");
        s.scheduleDowngrade(plan, Subscription.Cycle.valueOf((cycle == null ? Pricing.Cycle.valueOf(s.getCycle().name()) : cycle).name()));
        if (!catalog.plan(plan).isStaffBlocksAllowed()) s.scheduleBlocks(0);
        changes.save(new Records.Change(ngoId, "DOWNGRADE_SCHEDULED", s.getPlanCode(), plan, s.getStatus().name(),
                s.getStatus().name(), null, null, actor, "effective " + s.getPeriodEnd()));
        audit.record(ngoId, actor, "SUBSCRIPTION_DOWNGRADE_SCHEDULED", "SUBSCRIPTION", s.getId(), Map.of("to", plan));
        return s;
    }

    @Transactional
    public Subscription cancelScheduledDowngrade(UUID ngoId, UUID actor) {
        Subscription s = lock(ngoId);
        s.cancelScheduledDowngrade();
        changes.save(new Records.Change(ngoId, "DOWNGRADE_CANCELLED", null, null, null, null, null, null, actor, null));
        return s;
    }

    /** Removal of blocks takes effect at renewal and may not cut below occupied seats. */
    @Transactional
    public Subscription scheduleBlocks(UUID ngoId, int blocks, UUID actor) {
        Subscription s = lock(ngoId);
        if (!catalog.plan(s.getPlanCode()).isStaffBlocksAllowed()) {
            throw ApiException.rule("BLOCKS_NOT_ALLOWED", "Only Enterprise can have additional staff blocks");
        }
        if (blocks < 0 || blocks >= s.getStaffBlocks()) {
            throw ApiException.badRequest("INVALID_BLOCKS", "Choose fewer blocks than you have now (" + s.getStaffBlocks() + ")");
        }
        Long active = usage.activeStaff(ngoId);
        int base = Integer.parseInt(catalog.entitlements(s.getPlanCode()).get(Entitlements.STAFF_MAX));
        long capacity = base + (long) blocks * catalog.intSetting("staff_block_size");
        if (active == null) throw ApiException.rule("USAGE_UNAVAILABLE", "Could not verify current staff. Please retry.");
        if (active > capacity) {
            throw ApiException.rule("STAFF_CONFLICT", active + " staff are active; reducing to " + capacity
                    + " seats needs " + (active - capacity) + " deactivations first");
        }
        s.scheduleBlocks(blocks);
        changes.save(new Records.Change(ngoId, "BLOCKS_REDUCTION_SCHEDULED", null, null, null, null, blocks, null, actor, null));
        audit.record(ngoId, actor, "SUBSCRIPTION_BLOCKS_SCHEDULED", "SUBSCRIPTION", s.getId(), Map.of("blocks", blocks));
        return s;
    }

    @Transactional
    public Subscription setCancellation(UUID ngoId, boolean cancel, String reason, UUID actor) {
        Subscription s = lock(ngoId);
        if (s.getStatus() == Status.TRIAL && cancel) {
            Status from = s.getStatus();
            s.transition(Status.CANCELLED);
            publishStatus(s, from);
        } else if (s.getStatus() == Status.ACTIVE) {
            s.setCancelAtPeriodEnd(cancel);
        } else {
            throw ApiException.rule("CANNOT_CANCEL", "Subscription is " + s.getStatus());
        }
        changes.save(new Records.Change(ngoId, cancel ? "CANCELLATION_REQUESTED" : "CANCELLATION_WITHDRAWN", s.getPlanCode(),
                s.getPlanCode(), null, null, null, null, actor, reason));
        audit.record(ngoId, actor, cancel ? "SUBSCRIPTION_CANCEL_REQUESTED" : "SUBSCRIPTION_CANCEL_WITHDRAWN", "SUBSCRIPTION",
                s.getId(), Map.of());
        return s;
    }

    @Transactional
    public Subscription setAutoRenew(UUID ngoId, boolean on, UUID actor) {
        Subscription s = lock(ngoId);
        s.setAutoRenew(on);
        audit.record(ngoId, actor, "SUBSCRIPTION_AUTORENEW_" + (on ? "ON" : "OFF"), "SUBSCRIPTION", s.getId(), Map.of());
        return s;
    }

    @Transactional
    public Subscription billingContact(UUID ngoId, String name, String email, String gstin, UUID actor) {
        Subscription s = lock(ngoId);
        s.billingContact(name, email, gstin == null || gstin.isBlank() ? null : gstin.toUpperCase());
        audit.record(ngoId, actor, "BILLING_CONTACT_UPDATED", "SUBSCRIPTION", s.getId(), Map.of());
        return s;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Called by the scheduler (and tests) to move subscriptions through time-based states. */
    @Transactional
    public int advanceLifecycle() {
        Instant now = now();
        int changed = 0;
        int graceDays = catalog.intSetting("grace_days");
        for (Subscription s : subs.findByStatusInAndPeriodEndBefore(List.of(Status.TRIAL, Status.ACTIVE), now)) {
            Subscription locked = lock(s.getNgoId());
            Status from = locked.getStatus();
            if (from == Status.TRIAL) {
                locked.transition(Status.EXPIRED);
            } else if (locked.isCancelAtPeriodEnd()) {
                locked.transition(Status.CANCELLED);
            } else {
                locked.enterGrace(locked.getPeriodEnd().plus(Duration.ofDays(graceDays)));
            }
            changes.save(new Records.Change(locked.getNgoId(), "STATUS_CHANGED", locked.getPlanCode(), locked.getPlanCode(),
                    from.name(), locked.getStatus().name(), null, null, null, "period ended"));
            publishStatus(locked, from);
            changed++;
        }
        for (Subscription s : subs.findByStatusAndGraceEndsAtBefore(Status.GRACE, now)) {
            Subscription locked = lock(s.getNgoId());
            locked.transition(Status.SUSPENDED);
            changes.save(new Records.Change(locked.getNgoId(), "STATUS_CHANGED", locked.getPlanCode(), locked.getPlanCode(),
                    "GRACE", "SUSPENDED", null, null, null, "grace ended"));
            publishStatus(locked, Status.GRACE);
            changed++;
        }
        Instant archiveBefore = now.minus(Duration.ofDays(catalog.intSetting("archive_after_days")));
        for (Subscription s : subs.findByStatusInAndUpdatedAtBefore(List.of(Status.SUSPENDED, Status.CANCELLED, Status.EXPIRED), archiveBefore)) {
            Subscription locked = lock(s.getNgoId());
            Status from = locked.getStatus();
            locked.transition(Status.ARCHIVED); // data retained; reactivation still possible
            publishStatus(locked, from);
            changed++;
        }
        for (Records.Quote q : quotes.findByStatusAndExpiresAtBefore(Records.Quote.Status.OPEN, now)) {
            q.setStatus(Records.Quote.Status.EXPIRED);
        }
        return changed;
    }

    void publishStatus(Subscription s, Status from) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("plan", s.getPlanCode());
        payload.put("status", s.getStatus().name());
        if (from != null) payload.put("from", from.name());
        payload.put("periodEnd", s.getPeriodEnd().toString());
        events.publish(EventTypes.SUBSCRIPTION_STATUS_CHANGED, s.getNgoId(), s.getId(), payload);
    }

    public static Instant atStartOfDay(LocalDate d) { return d.atStartOfDay(ZoneOffset.UTC).toInstant(); }
}
