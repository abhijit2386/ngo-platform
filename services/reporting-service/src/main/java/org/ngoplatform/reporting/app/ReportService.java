package org.ngoplatform.reporting.app;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.ngoplatform.commons.audit.AuditLog;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.security.AccessGuard;
import org.ngoplatform.commons.security.Entitlements;
import org.ngoplatform.commons.security.Membership;
import org.ngoplatform.commons.security.Permissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Assembles reports from the owning services and applies {@link Access} (role scope and plan level). */
@Service
public class ReportService {
    private final AccessGuard guard;
    private final Sources sources;
    private final AuditLog audit;
    private final Clock clock;

    @Autowired
    public ReportService(AccessGuard guard, Sources sources, AuditLog audit) {
        this(guard, sources, audit, Clock.system(Period.IST));
    }

    ReportService(AccessGuard guard, Sources sources, AuditLog audit, Clock clock) {
        this.guard = guard; this.sources = sources; this.audit = audit; this.clock = clock;
    }

    public LocalDate today() { return LocalDate.now(clock); }

    public Period period(LocalDate from, LocalDate to) { return Period.of(from, to, today()); }

    /** Any member with REPORT_VIEW; NGO-wide figures only for administrators and Finance. */
    public Membership viewer(UUID ngoId) { return guard.require(ngoId, Permissions.REPORT_VIEW); }

    public Access.Level level(UUID ngoId) {
        Entitlements e = guard.entitlements(ngoId);
        return Access.Level.of(e == null ? null : e.level(Entitlements.REPORTING_LEVEL));
    }

    // ---------------- dashboard (Reports tab)

    public Map<String, Object> dashboard(UUID ngoId) {
        Membership m = viewer(ngoId);
        Period fy = Period.currentFinancialYear(today());
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("period", Reports.PeriodView.of(fy));
        if (Access.ngoWide(m)) {
            Access.Level level = level(ngoId);
            Data.DonationSummary s = sources.donations(ngoId, fy, null);
            Data.Operations ops = sources.operations(ngoId);
            d.put("scope", "NGO");
            d.put("level", level.name());
            d.put("sections", Access.sections(level));
            d.put("locked", Access.locked(level));
            d.put("canExport", Access.canExport(m) && level.atLeast(Access.Level.STANDARD));
            d.put("donationsTotal", s.total());
            d.put("donationCount", s.count());
            d.put("donorCount", s.donors());
            d.put("activeCampaigns", ops.activeCampaigns());
            d.put("activeProjects", ops.activeProjects());
            d.put("pendingApprovals", ops.pending().expenses() + ops.pending().campaigns());
        } else {
            Data.DonationSummary s = sources.donations(ngoId, fy, m.userId());
            Data.Mine mine = sources.member(ngoId, m.userId());
            d.put("scope", "ME");
            d.put("myCollections", s.total());
            d.put("myCollectionCount", s.count());
            d.put("cashInHand", holds(mine.cashBalance()));
            d.put("pendingClaims", mine.pendingClaims().size());
            d.put("myCampaigns", mine.campaigns().size());
        }
        return d;
    }

    // ---------------- NGO-wide

    public Reports.NgoReport ngoReport(UUID ngoId, Period p) {
        Membership m = viewer(ngoId);
        if (!Access.ngoWide(m)) {
            throw ApiException.forbidden("NGO_REPORTS_ADMIN_FINANCE", "NGO-wide reports are for administrators and Finance staff. See My report.");
        }
        Access.Level level = level(ngoId);
        List<String> sections = Access.sections(level);
        Data.DonationSummary s = sources.donations(ngoId, p, null);
        Reports.Donations donations = new Reports.Donations(s.total(), s.count(), s.donors(), s.byMonth(), s.byChannel(),
                s.byCampaign().stream().map(r -> new Data.Row(r.key(), r.key() == null ? null : r.label(), r.amount(), r.count())).toList());
        Reports.Donors donors = null;
        List<Data.CampaignRow> campaigns = null;
        List<Reports.StaffCash> cash = null;
        Data.Pending pending = null;
        if (sections.contains(Access.DONORS)) {
            // donor keys may be e-mail addresses: only names leave this service
            donors = new Reports.Donors(s.newDonors(), s.topDonors().stream().map(r -> new Data.Row(null, r.label(), r.amount(), r.count())).toList());
        }
        if (sections.contains(Access.CAMPAIGNS)) {
            Data.Operations ops = sources.operations(ngoId);
            campaigns = ops.campaigns();
            pending = ops.pending();
            Map<String, String> names = sources.names(ops.staffCash().stream().map(Data.StaffBalance::userId).toList());
            cash = ops.staffCash().stream()
                    .map(b -> new Reports.StaffCash(b.userId(), names.get(b.userId()), b.advanced(), b.spent(), b.returned(),
                            b.reimbursed(), holds(b.balance()), owed(b.balance())))
                    .toList();
        }
        return new Reports.NgoReport(sources.ngoName(ngoId), Reports.PeriodView.of(p), level.name(), sections, Access.locked(level),
                Access.canExport(m) && level.atLeast(Access.Level.STANDARD), donations, donors, campaigns, cash, pending, Instant.now(clock));
    }

    /** PDF / Excel of the NGO-wide report: REPORT_EXPORT on a Growth plan or higher. Audited. */
    public Reports.NgoReport ngoReportForExport(UUID ngoId, Period p, String format) {
        Membership m = guard.require(ngoId, Permissions.REPORT_EXPORT);
        if (!level(ngoId).atLeast(Access.Level.STANDARD)) {
            throw ApiException.entitlement(Entitlements.REPORTING_LEVEL, "PDF and Excel exports are available on the Growth plan and above.");
        }
        Reports.NgoReport r = ngoReport(ngoId, p);
        audit.record(ngoId, m.userId(), "REPORT_EXPORTED", "REPORT", "summary", Map.of("format", format, "from", p.from().toString(), "to", p.to().toString()));
        return r;
    }

    // ---------------- one member

    public Reports.MyReport myReport(UUID ngoId, Period p) {
        Membership m = viewer(ngoId);
        Data.DonationSummary s = sources.donations(ngoId, p, m.userId());
        Data.Mine mine = sources.member(ngoId, m.userId());
        return new Reports.MyReport(Reports.PeriodView.of(p),
                new Reports.Donations(s.total(), s.count(), s.donors(), s.byMonth(), s.byChannel(), s.byCampaign()),
                mine.campaigns(), holds(mine.cashBalance()), owed(mine.cashBalance()), mine.pendingClaims(), Instant.now(clock));
    }

    // ---------------- 80G register / Form 10BD data

    public Reports.TaxReport taxReport(UUID ngoId, Period p, boolean export) {
        Membership m = viewer(ngoId);
        if (!Access.ngoWide(m)) {
            throw ApiException.forbidden("NGO_REPORTS_ADMIN_FINANCE", "The 80G register is for administrators and Finance staff.");
        }
        if (export && !Access.canExport(m)) throw ApiException.forbidden("REPORT_EXPORT", "You cannot export reports");
        if (!level(ngoId).atLeast(Access.Level.ADVANCED)) {
            throw ApiException.entitlement(Entitlements.REPORTING_LEVEL, "The 80G register and Form 10BD data are available on the Professional plan and above.");
        }
        List<Data.TaxRow> rows = sources.taxRows(ngoId, p);
        Map<String, String> numbers = sources.receiptNumbers(ngoId, rows.stream().map(Data.TaxRow::donationId).toList());
        List<Form10bd.RegisterLine> register = Form10bd.register(rows, numbers);
        List<Form10bd.Line> lines = Form10bd.lines(register);
        BigDecimal total = register.stream().map(Form10bd.RegisterLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        long attention = lines.stream().filter(l -> !l.note().isEmpty()).count();
        // PAN data: who viewed or exported it, and when
        audit.record(ngoId, m.userId(), export ? "TAX_REGISTER_EXPORTED" : "TAX_REGISTER_VIEWED", "REPORT", "80g",
                Map.of("from", p.from().toString(), "to", p.to().toString(), "lines", lines.size()));
        return new Reports.TaxReport(sources.ngoName(ngoId), Reports.PeriodView.of(p), total, register.size(), attention, register, lines, Instant.now(clock));
    }

    static BigDecimal holds(BigDecimal balance) { return balance == null || balance.signum() >= 0 ? BigDecimal.ZERO : balance.negate(); }

    static BigDecimal owed(BigDecimal balance) { return balance == null || balance.signum() <= 0 ? BigDecimal.ZERO : balance; }
}
