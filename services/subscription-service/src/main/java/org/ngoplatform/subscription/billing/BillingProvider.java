package org.ngoplatform.subscription.billing;

import java.math.BigDecimal;

/**
 * SaaS subscription billing provider (separate from NGO donation providers, spec §13.1/§31).
 * App-store billing rules must be reviewed before enabling a production adapter.
 */
public interface BillingProvider {
    record Order(String orderId, BigDecimal amount, String checkoutHint) {}
    record WebhookEvent(String orderId, String paymentId, String outcome, BigDecimal amount) {}

    String name();
    Order createOrder(String reference, BigDecimal amount);
    /** Returns the parsed event only if the signature is valid. */
    WebhookEvent verifyWebhook(String body, String signature);
}
