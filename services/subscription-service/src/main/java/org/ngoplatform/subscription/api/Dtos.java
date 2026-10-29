package org.ngoplatform.subscription.api;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.ngoplatform.subscription.app.Pricing;

public final class Dtos {
    private Dtos() {}

    public record PlanView(String code, String name, BigDecimal monthlyEquivalent, BigDecimal quarterlyPrice,
                           BigDecimal annualPrice, int includedStaff, String accentColor, boolean staffBlocksAllowed,
                           Map<String, String> entitlements) {}

    public record Catalogue(List<PlanView> plans, int trialDays, int graceDays, BigDecimal annualDiscountPercent,
                            int staffBlockSize, BigDecimal staffBlockMonthlyPrice, BigDecimal staffBlockQuarterlyPrice,
                            boolean pricesIncludeGst, String donationCommission) {}

    public record SubscriptionView(UUID ngoId, String plan, String planName, String cycle, String status,
                                   Instant periodStart, Instant periodEnd, Instant graceEndsAt, long daysRemaining,
                                   BigDecimal contractedPeriodPrice, int staffBlocks, Integer scheduledStaffBlocks,
                                   String scheduledPlan, String scheduledCycle, boolean autoRenew,
                                   boolean cancelAtPeriodEnd, boolean trialUsed, String billingContactName,
                                   String billingContactEmail, String gstin, Map<String, String> entitlements,
                                   boolean operationsAllowed, long version) {}

    public record CheckoutRequest(@NotBlank @Pattern(regexp = "COMMUNITY|GROWTH|PROFESSIONAL|ENTERPRISE") String plan,
                                  @NotNull Pricing.Cycle cycle, @Min(0) @Max(100) int blocks) {}

    public record TrialPlan(@NotBlank @Pattern(regexp = "COMMUNITY|GROWTH|PROFESSIONAL|ENTERPRISE") String plan) {}

    public record Downgrade(@NotBlank @Pattern(regexp = "COMMUNITY|GROWTH|PROFESSIONAL") String plan,
                            Pricing.Cycle cycle, boolean confirmed) {}

    public record Blocks(@Min(0) @Max(100) int blocks) {}

    public record Cancellation(boolean cancel, @Size(max = 500) String reason) {}

    public record Toggle(boolean enabled) {}

    public record BillingContact(@NotBlank @Size(max = 120) String name, @NotBlank @Email @Size(max = 254) String email,
                                 @Pattern(regexp = "^$|^[0-9]{2}[A-Za-z]{5}[0-9]{4}[A-Za-z][1-9A-Za-z][Zz][0-9A-Za-z]$",
                                         message = "GSTIN must be 15 characters, e.g. 27ABCDE1234F1Z5") String gstin) {}

    public record PaymentView(UUID id, UUID quoteId, String provider, String orderId, BigDecimal amount,
                              BigDecimal paidAmount, String status, String failureReason, Instant createdAt) {}

    public record InvoiceView(UUID id, String number, BigDecimal total, BigDecimal taxable, BigDecimal gst,
                              String status, Instant issuedAt, Instant periodStart, Instant periodEnd) {}

    public record ChangeView(String kind, String fromPlan, String toPlan, String fromStatus, String toStatus,
                             Integer blocks, BigDecimal amount, String note, Instant at) {}

    public record CreditView(UUID id, BigDecimal amount, String reason, String status, Instant createdAt) {}

    public record DisputeRequest(@NotBlank @Pattern(regexp = "REFUND_REQUEST|DISPUTE") String kind,
                                 UUID paymentId, @NotBlank @Size(min = 10, max = 1000) String reason) {}

    public record DisputeView(UUID id, UUID paymentId, String kind, String reason, String status, String resolution,
                              Instant createdAt) {}

    public record MockOutcome(@NotBlank @Pattern(regexp = "SUCCESS|FAILED|PENDING|CANCELLED") String outcome,
                              BigDecimal amountOverride, boolean duplicate) {}

    public record Resolution(boolean approve, @NotBlank @Size(max = 1000) String note) {}

    public record PlanPrice(@NotNull @DecimalMin("1.00") @Digits(integer = 8, fraction = 2) BigDecimal monthlyPrice) {}

    public record EntitlementUpdate(@NotBlank String key, @NotBlank @Size(max = 60) String value) {}

    public record SettingUpdate(@NotBlank String key, @NotBlank @Size(max = 60) String value) {}
}
