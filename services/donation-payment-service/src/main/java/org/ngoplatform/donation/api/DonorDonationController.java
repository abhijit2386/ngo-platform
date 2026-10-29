package org.ngoplatform.donation.api;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.idempotency.Idempotency;
import org.ngoplatform.commons.security.AccessGuard;
import org.ngoplatform.commons.security.CurrentUser;
import org.ngoplatform.commons.web.PageResponse;
import org.ngoplatform.donation.app.DonationService;
import org.ngoplatform.donation.domain.Donation;
import org.ngoplatform.donation.domain.Records;
import org.ngoplatform.donation.domain.Repos;
import org.ngoplatform.donation.provider.MockPaymentProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** DAPP-009..018: donor checkout (signed-in or guest), status, history, and provider webhooks. */
@RestController
public class DonorDonationController {
    private final DonationService svc;
    private final AccessGuard guard;
    private final Idempotency idempotency;
    private final Repos.Donations donations;
    private final Repos.Payments payments;
    private final MockPaymentProvider mock;
    private final boolean mockEnabled;

    public DonorDonationController(DonationService svc, AccessGuard guard, Idempotency idempotency, Repos.Donations donations,
                                   Repos.Payments payments, MockPaymentProvider mock,
                                   @Value("${ngo.payments.mock-enabled:false}") boolean mockEnabled) {
        this.svc = svc; this.guard = guard; this.idempotency = idempotency; this.donations = donations;
        this.payments = payments; this.mock = mock; this.mockEnabled = mockEnabled;
    }

    private DonationService.CheckoutRequest map(Dtos.CheckoutRequest r) {
        return new DonationService.CheckoutRequest(r.ngoId(), r.campaignId(), r.projectId(), r.amount(), r.purpose(),
                r.donorName(), r.donorEmail(), r.donorPhone(), r.donorPan(), r.donorAddress(), r.wants80g(),
                r.scenario() == null || r.scenario().isBlank() ? "SUCCESS" : r.scenario());
    }

    @PostMapping("/api/v1/donations")
    @Transactional
    public DonationService.CheckoutResult checkout(@Valid @RequestBody Dtos.CheckoutRequest req,
                                                   @RequestHeader("Idempotency-Key") String key) {
        CurrentUser u = guard.user();
        return idempotency.execute("donation-checkout:" + u.userId(), key, req, DonationService.CheckoutResult.class,
                () -> svc.checkout(map(req), u.userId(), false));
    }

    /** Guest checkout (no account). Rate-limited at the gateway; email required for receipt delivery. */
    @PostMapping("/api/v1/public/donations")
    @Transactional
    public DonationService.CheckoutResult guestCheckout(@Valid @RequestBody Dtos.CheckoutRequest req,
                                                        @RequestHeader("Idempotency-Key") String key) {
        return idempotency.execute("donation-guest", key, req, DonationService.CheckoutResult.class,
                () -> svc.checkout(map(req), null, true));
    }

    @GetMapping("/api/v1/donations/{id}")
    public Dtos.DonationView get(@PathVariable UUID id) {
        CurrentUser u = guard.user();
        Donation d = donations.findById(id).filter(x -> u.userId().equals(x.getDonorUserId()))
                .orElseThrow(() -> ApiException.notFound("Donation"));
        return Dtos.DonationView.of(d);
    }

    /** Payment status recovery after the app was closed or the callback was lost (DAPP-014). */
    @PostMapping("/api/v1/donations/{id}/refresh")
    public Dtos.DonationView refresh(@PathVariable UUID id) {
        CurrentUser u = guard.user();
        donations.findById(id).filter(x -> u.userId().equals(x.getDonorUserId())).orElseThrow(() -> ApiException.notFound("Donation"));
        return Dtos.DonationView.of(svc.refresh(id));
    }

    @PostMapping("/api/v1/public/donations/{id}/refresh")
    public Map<String, String> guestRefresh(@PathVariable UUID id, @RequestParam String orderId) {
        Records.Payment p = payments.findByProviderOrderId(orderId).filter(x -> x.getDonationId().equals(id))
                .orElseThrow(() -> ApiException.notFound("Donation"));
        return Map.of("status", svc.refresh(p.getDonationId()).getStatus().name());
    }

    @GetMapping("/api/v1/donations/mine")
    public PageResponse<Dtos.DonationView> mine(@RequestParam(defaultValue = "0") int page) {
        CurrentUser u = guard.user();
        return PageResponse.of(donations.findByDonorUserIdAndStatusNotInOrderByCreatedAtDesc(u.userId(),
                List.of(Donation.Status.CREATED, Donation.Status.CANCELLED), PageRequest.of(Math.max(0, page), 20)), Dtos.DonationView::of);
    }

    /** The donor's last 80G details (their own data) to prefill the next donation; empty when none. */
    @GetMapping("/api/v1/donations/tax-profile")
    public Map<String, String> taxProfile() {
        CurrentUser u = guard.user();
        return donations.findFirstByDonorUserIdAndWants80gTrueOrderByCreatedAtDesc(u.userId())
                .<Map<String, String>>map(d -> Map.of("pan", d.getDonorPan(), "address", d.getDonorAddress() == null ? "" : d.getDonorAddress()))
                .orElse(Map.of());
    }

    @PostMapping("/api/v1/webhooks/payments/{provider}")
    public Map<String, String> webhook(@PathVariable String provider, @RequestBody String body,
                                       @RequestHeader(value = "X-Signature", required = false) String signature) {
        return Map.of("result", svc.onWebhook(provider.toUpperCase(), body, signature));
    }

    /**
     * DEVELOPMENT ONLY (ngo.payments.mock-enabled): stands in for the provider's hosted payment page. Produces the
     * provider's signed webhooks for the chosen scenario and delivers them to our own webhook handler.
     */
    @PostMapping("/api/v1/dev/mock-payments/{orderId}/complete")
    public Map<String, Object> mockComplete(@PathVariable String orderId, @Valid @RequestBody Dtos.MockComplete req) {
        if (!mockEnabled) throw ApiException.notFound("Route");
        Records.Payment p = payments.findByProviderOrderId(orderId).orElseThrow(() -> ApiException.notFound("Order"));
        if (!"MOCK".equals(p.getProvider())) throw ApiException.notFound("Order");
        if (!MockPaymentProvider.SCENARIOS.contains(req.scenario())) throw ApiException.badRequest("UNKNOWN_SCENARIO", "Unknown scenario");
        List<Map<String, String>> hooks = mock.complete(orderId, req.scenario(), p.getAmount());
        List<String> results = new java.util.ArrayList<>();
        for (Map<String, String> h : hooks) {
            try {
                results.add(svc.onWebhook("MOCK", h.get("body"), h.get("signature")));
            } catch (ApiException e) {
                results.add(e.code());
            }
        }
        Donation d = donations.findById(p.getDonationId()).orElseThrow();
        return Map.of("donationId", d.getId(), "status", d.getStatus().name(), "webhookResults", results);
    }
}
