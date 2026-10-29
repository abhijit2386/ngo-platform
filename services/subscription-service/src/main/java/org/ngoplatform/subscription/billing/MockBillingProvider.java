package org.ngoplatform.subscription.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.ngoplatform.commons.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Deterministic development provider. HMAC-SHA256 signed webhooks, like real gateways. */
@Component
public class MockBillingProvider implements BillingProvider {
    private final byte[] secret;
    private final ObjectMapper mapper;

    public MockBillingProvider(@Value("${ngo.billing.webhook-secret}") String secret, ObjectMapper mapper) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.mapper = mapper;
    }

    @Override public String name() { return "MOCK"; }

    @Override
    public Order createOrder(String reference, BigDecimal amount) {
        return new Order("mock_order_" + UUID.randomUUID().toString().replace("-", ""), amount, "mock");
    }

    public String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public WebhookEvent verifyWebhook(String body, String signature) {
        if (signature == null || !MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", "Webhook signature invalid");
        }
        try {
            JsonNode n = mapper.readTree(body);
            return new WebhookEvent(n.path("orderId").asText(), n.path("paymentId").asText(null),
                    n.path("outcome").asText(), new BigDecimal(n.path("amount").asText("0")));
        } catch (Exception e) {
            throw ApiException.badRequest("MALFORMED_WEBHOOK", "Webhook body unreadable");
        }
    }
}
