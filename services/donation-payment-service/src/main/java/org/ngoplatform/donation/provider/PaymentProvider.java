package org.ngoplatform.donation.provider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Donation payment gateway abstraction (spec §13.2). Each NGO uses its own merchant account. */
public interface PaymentProvider {
    record Order(String orderId, BigDecimal amount) {}
    record Status(String state, String paymentId, BigDecimal amount) {}          // state: CREATED|PENDING|AUTHORIZED|PAID|FAILED|CANCELLED
    record WebhookEvent(String eventId, String type, String orderId, String paymentId, String state, BigDecimal amount,
                        String refundId, String refundState) {}
    record SettlementLine(String paymentId, BigDecimal gross, BigDecimal fee, BigDecimal tax, BigDecimal net) {}
    record Settlement(String settlementId, LocalDate settledOn, String bankReference, List<SettlementLine> lines) {}
    record RefundResult(String refundId, String state) {}

    String name();
    Order createPaymentOrder(String merchantReference, String receipt, BigDecimal amount, String scenario);
    Status getPaymentStatus(String merchantReference, String orderId);
    /** Throws if the signature is invalid. */
    WebhookEvent verifyWebhook(String body, String signature);
    List<Settlement> getSettlements(String merchantReference, LocalDate from, LocalDate to);
    RefundResult initiateRefund(String merchantReference, String paymentId, BigDecimal amount, String scenario);
    RefundResult getRefundStatus(String merchantReference, String refundId);
}
