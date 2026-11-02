package org.ngoplatform.reporting.app;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Report shapes returned to the app and rendered to PDF / Excel. Sections the plan does not include are null. */
public final class Reports {
    private Reports() {}

    public record PeriodView(LocalDate from, LocalDate to, String label) {
        static PeriodView of(Period p) { return new PeriodView(p.from(), p.to(), p.label()); }
    }

    public record Donations(BigDecimal total, long count, long donors, List<Data.Row> byMonth, List<Data.Row> byChannel,
                            List<Data.Row> byCampaign) {}
    public record Donors(Long newDonors, List<Data.Row> top) {}
    /** holds: NGO cash the member still holds; owed: what the NGO owes the member. */
    public record StaffCash(String userId, String name, BigDecimal advanced, BigDecimal spent, BigDecimal returned,
                            BigDecimal reimbursed, BigDecimal holds, BigDecimal owed) {}

    public record NgoReport(String ngoName, PeriodView period, String level, List<String> sections, List<String> locked,
                            boolean canExport, Donations donations, Donors donors, List<Data.CampaignRow> campaigns,
                            List<StaffCash> staffCash, Data.Pending pending, Instant generatedAt) {}

    public record MyReport(PeriodView period, Donations collections, List<Data.CampaignRow> campaigns, BigDecimal cashInHand,
                           BigDecimal owedToYou, List<Data.Claim> pendingClaims, Instant generatedAt) {}

    public record TaxReport(String ngoName, PeriodView period, BigDecimal total, long donations, long needsAttention,
                            List<Form10bd.RegisterLine> register, List<Form10bd.Line> form10bd, Instant generatedAt) {}
}
