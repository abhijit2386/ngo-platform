package org.ngoplatform.subscription.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.security.AccessGuard;
import org.ngoplatform.commons.security.Permissions;
import org.ngoplatform.subscription.app.SubscriptionService;
import org.ngoplatform.subscription.billing.BillingProvider;
import org.ngoplatform.subscription.billing.MockBillingProvider;
import org.ngoplatform.subscription.domain.Records;
import org.ngoplatform.subscription.domain.Repos;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
public class BillingWebhookController {
    private final BillingProvider provider;
    private final SubscriptionService svc;
    private final Repos.Payments payments;
    private final AccessGuard guard;
    private final ObjectMapper mapper;
    private final boolean mockEnabled;

    public BillingWebhookController(BillingProvider provider, SubscriptionService svc, Repos.Payments payments,
                                    AccessGuard guard, ObjectMapper mapper,
                                    @Value("${ngo.billing.mock-enabled:false}") boolean mockEnabled) {
        this.provider = provider; this.svc = svc; this.payments = payments; this.guard = guard; this.mapper = mapper;
        this.mockEnabled = mockEnabled;
    }

    /** Provider callback. Authenticity comes from the HMAC signature, not from a user session. */
    @PostMapping("/api/v1/webhooks/billing/{provider}")
    public Map<String, String> webhook(@PathVariable("provider") String name, @RequestBody String body,
                                       @RequestHeader(value = "X-Signature", required = false) String signature) {
        BillingProvider.WebhookEvent ev = provider.verifyWebhook(body, signature);
        Records.Payment p = svc.onPaymentResult(ev);
        return Map.of("status", p.getStatus().name());
    }

    /**
     * DEVELOPMENT ONLY (disabled unless ngo.billing.mock-enabled=true): simulates the hosted checkout page completing,
     * by sending a correctly signed webhook. Never enable in production.
     */
    @PostMapping("/api/v1/ngos/{ngoId}/subscription/mock-checkout/{orderId}")
    public Map<String, String> mockComplete(@PathVariable UUID ngoId, @PathVariable String orderId,
                                            @Valid @RequestBody Dtos.MockOutcome req) throws Exception {
        if (!mockEnabled || !(provider instanceof MockBillingProvider mock)) throw ApiException.notFound("Route");
        guard.require(ngoId, Permissions.SUBSCRIPTION_MANAGE);
        Records.Payment p = payments.findByProviderOrderId(orderId).filter(x -> x.getNgoId().equals(ngoId))
                .orElseThrow(() -> ApiException.notFound("Payment"));
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("orderId", orderId);
        ev.put("paymentId", "mock_pay_" + UUID.randomUUID().toString().substring(0, 12));
        ev.put("outcome", req.outcome());
        ev.put("amount", (req.amountOverride() != null ? req.amountOverride() : p.getAmount()).toPlainString());
        String body = mapper.writeValueAsString(ev);
        Records.Payment result = svc.onPaymentResult(provider.verifyWebhook(body, mock.sign(body)));
        if (req.duplicate()) result = svc.onPaymentResult(provider.verifyWebhook(body, mock.sign(body)));
        return Map.of("status", result.getStatus().name());
    }
}
