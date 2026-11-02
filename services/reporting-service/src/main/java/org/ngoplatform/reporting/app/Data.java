package org.ngoplatform.reporting.app;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Figures returned by the internal report endpoints of donation-payment, receipt and campaign-project services. */
public final class Data {
    private Data() {}

    public record Row(String key, String label, BigDecimal amount, long count) {}
    public record DonationSummary(BigDecimal total, long count, long donors, Long newDonors, List<Row> byMonth,
                                  List<Row> byChannel, List<Row> byCampaign, List<Row> topDonors) {}
    /** eightyG: donationId -> 80G receipt number; plain: donations whose receipt is not an 80G receipt. */
    public record ReceiptFacts(java.util.Map<String, String> eightyG, List<String> plain) {}
    public record TaxRow(String donationId, Instant paidAt, String donorName, String pan, String address,
                         String channel, BigDecimal amount, String campaignTitle) {}

    public record CampaignRow(String id, String title, String status, BigDecimal goal, BigDecimal raised, long donations,
                              BigDecimal planned, BigDecimal spent) {}
    public record StaffBalance(String userId, BigDecimal advanced, BigDecimal spent, BigDecimal returned,
                               BigDecimal reimbursed, BigDecimal balance) {}
    public record Pending(long expenses, BigDecimal expenseAmount, long campaigns) {}
    public record Claim(String id, String voucherNo, String campaignTitle, String category, BigDecimal amount, Instant submittedAt) {}
    public record Operations(long activeCampaigns, long activeProjects, List<CampaignRow> campaigns,
                             List<StaffBalance> staffCash, Pending pending) {}
    public record Mine(List<CampaignRow> campaigns, BigDecimal cashBalance, List<Claim> pendingClaims) {}
}
