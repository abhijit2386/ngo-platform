package org.ngoplatform.donation.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.ngoplatform.commons.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Deterministic development provider (spec §13.2) supporting: SUCCESS, FAILURE, PENDING, CANCELLATION, TIMEOUT,
 * DELAYED_SUCCESS, DUPLICATE_WEBHOOK, INVALID_SIGNATURE, REFUND_SUCCESS, REFUND_FAILURE, PARTIAL_REFUND,
 * SETTLEMENT_MISMATCH. Fee model for settlements: 2% gateway fee + 18% GST on the fee.
 * Orders/payments live in memory and settlements are derived from the service's own paid payments (supplied by caller).
 */
@Component
public class MockPaymentProvider implements PaymentProvider {
    public static final List<String> SCENARIOS = List.of("SUCCESS", "FAILURE", "PENDING", "CANCELLATION", "TIMEOUT",
            "DELAYED_SUCCESS", "DUPLICATE_WEBHOOK", "INVALID_SIGNATURE", "REFUND_SUCCESS", "REFUND_FAILURE", "PARTIAL_REFUND",
            "SETTLEMENT_MISMATCH");

    private final byte[] secret;
    private final ObjectMapper mapper;
    private final Map<String, Status> orders = new ConcurrentHashMap<>();
    private final Map<String, RefundResult> refunds = new ConcurrentHashMap<>();
    /** Paid payments the mock will "settle", keyed by payment id -> gross; filled by the service when a payment is captured. */
    private final Map<String, BigDecimal> captured = new ConcurrentHashMap<>();
    private final Map<String, String> paymentScenario = new ConcurrentHashMap<>();

    public MockPaymentProvider(@Value("${ngo.payments.mock-webhook-secret}") String secret, ObjectMapper mapper) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.mapper = mapper;
    }

    @Override public String name() { return "MOCK"; }

    @Override
    public Order createPaymentOrder(String merchantReference, String receipt, BigDecimal amount, String scenario) {
        String id = "mock_order_" + UUID.randomUUID().toString().replace("-", "");
        orders.put(id, new Status("CREATED", null, amount));
        return new Order(id, amount);
    }

    @Override
    public Status getPaymentStatus(String merchantReference, String orderId) {
        Status s = orders.get(orderId);
        if (s == null) throw ApiException.notFound("Provider order");
        return s;
    }

    /** Simulates the provider's hosted checkout finishing; returns the signed webhook bodies the provider would send. */
    public List<Map<String, String>> complete(String orderId, String scenario, BigDecimal amount) {
        String paymentId = "mock_pay_" + UUID.randomUUID().toString().substring(0, 18).replace("-", "");
        List<Map<String, String>> out = new ArrayList<>();
        switch (scenario) {
            case "SUCCESS", "DUPLICATE_WEBHOOK", "SETTLEMENT_MISMATCH", "REFUND_SUCCESS", "REFUND_FAILURE", "PARTIAL_REFUND" -> {
                orders.put(orderId, new Status("PAID", paymentId, amount));
                captured.put(paymentId, amount);
                paymentScenario.put(paymentId, scenario);
                Map<String, String> ev = signed(event("payment.captured", orderId, paymentId, "PAID", amount));
                out.add(ev);
                if ("DUPLICATE_WEBHOOK".equals(scenario)) out.add(ev); // identical event id delivered twice
            }
            case "FAILURE" -> {
                orders.put(orderId, new Status("FAILED", paymentId, amount));
                out.add(signed(event("payment.failed", orderId, paymentId, "FAILED", amount)));
            }
            case "CANCELLATION" -> {
                orders.put(orderId, new Status("CANCELLED", null, amount));
                out.add(signed(event("payment.cancelled", orderId, null, "CANCELLED", amount)));
            }
            case "PENDING" -> {
                orders.put(orderId, new Status("PENDING", paymentId, amount));
                out.add(signed(event("payment.pending", orderId, paymentId, "PENDING", amount)));
            }
            case "TIMEOUT" -> {
                // Customer completes payment but the provider never calls back; recovery polling finds PAID.
                orders.put(orderId, new Status("PAID", paymentId, amount));
                captured.put(paymentId, amount);
            }
            case "DELAYED_SUCCESS" -> {
                // First reports failure, later (out of order) a capture arrives.
                out.add(signed(event("payment.failed", orderId, paymentId, "FAILED", amount)));
                orders.put(orderId, new Status("PAID", paymentId, amount));
                captured.put(paymentId, amount);
                out.add(signed(event("payment.captured", orderId, paymentId, "PAID", amount)));
            }
            case "INVALID_SIGNATURE" -> {
                Map<String, String> ev = signed(event("payment.captured", orderId, paymentId, "PAID", amount));
                ev.put("signature", "0000" + ev.get("signature").substring(4));
                out.add(ev);
            }
            default -> throw ApiException.badRequest("UNKNOWN_SCENARIO", "Scenario must be one of " + SCENARIOS);
        }
        return out;
    }

    private Map<String, Object> event(String type, String orderId, String paymentId, String state, BigDecimal amount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", "evt_" + UUID.randomUUID().toString().replace("-", ""));
        m.put("type", type);
        m.put("orderId", orderId);
        if (paymentId != null) m.put("paymentId", paymentId);
        m.put("state", state);
        m.put("amount", amount.toPlainString());
        return m;
    }

    private Map<String, String> signed(Map<String, Object> event) {
        try {
            String body = mapper.writeValueAsString(event);
            Map<String, String> m = new LinkedHashMap<>();
            m.put("body", body);
            m.put("signature", sign(body));
            return m;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
            return new WebhookEvent(n.path("eventId").asText(), n.path("type").asText(), n.path("orderId").asText(null),
                    n.path("paymentId").asText(null), n.path("state").asText(), new BigDecimal(n.path("amount").asText("0")),
                    n.path("refundId").asText(null), n.path("refundState").asText(null));
        } catch (Exception e) {
            throw ApiException.badRequest("MALFORMED_WEBHOOK", "Webhook body unreadable");
        }
    }

    @Override
    public List<Settlement> getSettlements(String merchantReference, LocalDate from, LocalDate to) {
        // One batch with every captured, not-yet-settled payment of this mock instance.
        List<SettlementLine> lines = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : captured.entrySet()) {
            BigDecimal gross = e.getValue();
            BigDecimal fee = gross.multiply(new BigDecimal("0.02")).setScale(2, RoundingMode.HALF_UP);
            BigDecimal tax = fee.multiply(new BigDecimal("0.18")).setScale(2, RoundingMode.HALF_UP);
            if ("SETTLEMENT_MISMATCH".equals(paymentScenario.get(e.getKey()))) gross = gross.subtract(new BigDecimal("1.00"));
            lines.add(new SettlementLine(e.getKey(), gross, fee, tax, gross.subtract(fee).subtract(tax)));
        }
        captured.clear();
        if (lines.isEmpty()) return List.of();
        return List.of(new Settlement("mock_setl_" + UUID.randomUUID().toString().substring(0, 12), to,
                "UTR" + System.currentTimeMillis(), lines));
    }

    @Override
    public RefundResult initiateRefund(String merchantReference, String paymentId, BigDecimal amount, String scenario) {
        String id = "mock_rfnd_" + UUID.randomUUID().toString().substring(0, 12);
        String sc = scenario != null ? scenario : paymentScenario.getOrDefault(paymentId, "REFUND_SUCCESS");
        RefundResult r = new RefundResult(id, "REFUND_FAILURE".equals(sc) ? "FAILED" : "SUCCEEDED");
        refunds.put(id, r);
        return r;
    }

    @Override
    public RefundResult getRefundStatus(String merchantReference, String refundId) {
        RefundResult r = refunds.get(refundId);
        if (r == null) throw ApiException.notFound("Refund");
        return r;
    }

    /** Lets the service re-register captured payments after a restart (mock state is in memory). */
    public void registerCaptured(String paymentId, BigDecimal amount) { captured.putIfAbsent(paymentId, amount); }
}
