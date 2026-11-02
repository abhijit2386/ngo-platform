package org.ngoplatform.reporting.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.ngoplatform.commons.audit.AuditLog;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.security.AccessGuard;
import org.ngoplatform.commons.security.Entitlements;
import org.ngoplatform.commons.security.Membership;
import org.ngoplatform.commons.security.Permissions;

/** Owner decisions 2 Nov 2026: NGO-wide = admins + Finance; plan levels BASIC / STANDARD / ADVANCED. */
class ReportServiceTest {
    private final UUID ngo = UUID.randomUUID(), admin = UUID.randomUUID(), staff = UUID.randomUUID(), finance = UUID.randomUUID();
    private final AccessGuard guard = mock(AccessGuard.class);
    private final Sources sources = mock(Sources.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final ReportService svc = new ReportService(guard, sources, audit, Clock.fixed(Instant.parse("2026-11-02T08:00:00Z"), Period.IST));

    private Membership member(UUID user, String role) {
        return new Membership(ngo, user, role, "ACTIVE", Permissions.ROLE_TEMPLATES.get(role));
    }

    private void as(Membership m, String level) {
        when(guard.require(eq(ngo), any())).thenAnswer(inv -> {
            String p = inv.getArgument(1);
            if (!m.can(p)) throw ApiException.forbidden("PERMISSION_DENIED", p);
            return m;
        });
        Entitlements e = mock(Entitlements.class);
        when(e.level(Entitlements.REPORTING_LEVEL)).thenReturn(level);
        when(guard.entitlements(ngo)).thenReturn(e);
    }

    private void figures() {
        Data.Row month = new Data.Row("2026-05", null, new BigDecimal("1500.00"), 2);
        when(sources.donations(eq(ngo), any(), any())).thenReturn(new Data.DonationSummary(new BigDecimal("1500.00"), 2, 2, 1L,
                List.of(month), List.of(new Data.Row("ONLINE", null, new BigDecimal("1500.00"), 2)), List.of(),
                List.of(new Data.Row("meera@ngo.test", "Meera", new BigDecimal("1000.00"), 1))));
        when(sources.operations(ngo)).thenReturn(new Data.Operations(1, 0, List.of(),
                List.of(new Data.StaffBalance(staff.toString(), new BigDecimal("-500"), new BigDecimal("200"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("-300"))),
                new Data.Pending(1, new BigDecimal("200"), 0)));
        when(sources.names(any())).thenReturn(Map.of(staff.toString(), "Sunil"));
        when(sources.member(ngo, staff)).thenReturn(new Data.Mine(List.of(), new BigDecimal("-300"), List.of()));
        when(sources.ngoName(ngo)).thenReturn("Asha Seva");
    }

    @Test
    void staffGetTheirOwnTilesAndCannotOpenNgoReports() {
        as(member(staff, "STAFF"), "ADVANCED");
        figures();
        Map<String, Object> d = svc.dashboard(ngo);
        assertThat(d.get("scope")).isEqualTo("ME");
        assertThat(d.get("cashInHand")).isEqualTo(new BigDecimal("300"));
        verify(sources).donations(eq(ngo), any(), eq(staff)); // own collections only
        assertThatThrownBy(() -> svc.ngoReport(ngo, svc.period(null, null))).isInstanceOf(ApiException.class)
                .hasMessageContaining("administrators and Finance");
        assertThatThrownBy(() -> svc.taxReport(ngo, svc.period(null, null), false)).isInstanceOf(ApiException.class);
        assertThat(svc.myReport(ngo, svc.period(null, null)).cashInHand()).isEqualByComparingTo("300");
    }

    @Test
    void financeSeesNgoWideFigures() {
        as(member(finance, "FINANCE"), "STANDARD");
        figures();
        Map<String, Object> d = svc.dashboard(ngo);
        assertThat(d.get("scope")).isEqualTo("NGO");
        assertThat(d.get("canExport")).isEqualTo(true);
        verify(sources).donations(eq(ngo), any(), isNull());
    }

    @Test
    void basicPlanShowsDonationsOnlyAndNoExport() {
        as(member(admin, "PRIMARY_ADMIN"), "BASIC");
        figures();
        Reports.NgoReport r = svc.ngoReport(ngo, svc.period(null, null));
        assertThat(r.donations().total()).isEqualByComparingTo("1500.00");
        assertThat(r.donors()).isNull();
        assertThat(r.staffCash()).isNull();
        assertThat(r.canExport()).isFalse();
        assertThat(r.locked()).contains(Access.DONORS, Access.EXPORT, Access.TAX_80G);
        verify(sources, never()).operations(ngo);
        assertThatThrownBy(() -> svc.ngoReportForExport(ngo, svc.period(null, null), "PDF")).isInstanceOf(ApiException.class)
                .hasMessageContaining("Growth");
    }

    @Test
    void standardPlanAddsDonorsCampaignsCashAndExportButNot80g() {
        as(member(admin, "PRIMARY_ADMIN"), "STANDARD");
        figures();
        Reports.NgoReport r = svc.ngoReport(ngo, svc.period(null, null));
        assertThat(r.donors().top()).extracting(Data.Row::key).containsOnlyNulls(); // no donor e-mails leave the service
        assertThat(r.donors().top().get(0).label()).isEqualTo("Meera");
        assertThat(r.staffCash().get(0).name()).isEqualTo("Sunil");
        assertThat(r.staffCash().get(0).holds()).isEqualByComparingTo("300");
        assertThat(r.pending().expenses()).isEqualTo(1);
        assertThat(r.canExport()).isTrue();
        svc.ngoReportForExport(ngo, svc.period(null, null), "XLSX");
        verify(audit).record(eq(ngo), eq(admin), eq("REPORT_EXPORTED"), eq("REPORT"), eq("summary"), anyMap());
        assertThatThrownBy(() -> svc.taxReport(ngo, svc.period(null, null), false)).isInstanceOf(ApiException.class)
                .hasMessageContaining("Professional");
    }

    @Test
    void advancedPlanBuildsThe80gRegisterAndAuditsIt() {
        as(member(admin, "SECONDARY_ADMIN"), "ADVANCED");
        figures();
        when(sources.taxRows(eq(ngo), any())).thenReturn(List.of(new Data.TaxRow("d1", Instant.parse("2026-05-01T05:00:00Z"), "Meera",
                "ABCDE1234F", "Pune", "ONLINE", new BigDecimal("1000.00"), "Books")));
        when(sources.receiptNumbers(eq(ngo), any())).thenReturn(Map.of("d1", "80G/26-27/0001"));
        Reports.TaxReport t = svc.taxReport(ngo, svc.period(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 9, 30)), true);
        assertThat(t.form10bd()).hasSize(1);
        assertThat(t.total()).isEqualByComparingTo("1000.00");
        assertThat(t.needsAttention()).isZero();
        verify(audit).record(eq(ngo), eq(admin), eq("TAX_REGISTER_EXPORTED"), eq("REPORT"), eq("80g"), anyMap());
    }

    @Test
    void volunteersHaveNoReportAccess() {
        as(new Membership(ngo, UUID.randomUUID(), "VOLUNTEER", "ACTIVE", Set.of(Permissions.PROJECT_VIEW)), "ADVANCED");
        assertThatThrownBy(() -> svc.dashboard(ngo)).isInstanceOf(ApiException.class);
    }
}
