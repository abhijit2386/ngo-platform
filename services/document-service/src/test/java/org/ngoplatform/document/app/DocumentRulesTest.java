package org.ngoplatform.document.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.security.Membership;

class DocumentRulesTest {
    static final UUID NGO = UUID.randomUUID();
    static final UUID ASHA = UUID.randomUUID(), SUNIL = UUID.randomUUID(), PRIYA = UUID.randomUUID();
    static final Membership ADMIN = new Membership(NGO, ASHA, "PRIMARY_ADMIN", "ACTIVE", Set.of("DOCUMENT_VIEW"));
    static final Membership STAFF = new Membership(NGO, SUNIL, "STAFF", "ACTIVE", Set.of("DOCUMENT_VIEW", "EXPENSE_VIEW"));
    static final Membership OTHER = new Membership(NGO, PRIYA, "VOLUNTEER_COORDINATOR", "ACTIVE", Set.of("DOCUMENT_VIEW"));

    static String code(Runnable r) {
        try { r.run(); return null; } catch (ApiException e) { return e.code(); }
    }

    @Test
    void adminsSeeEverythingStaffSeeOwnPlusTeamShared() {
        var restrictedBySunil = new DocumentRules.Doc(SUNIL, "RESTRICTED", null, "UPLOAD");
        var team = new DocumentRules.Doc(ASHA, "TEAM", null, "UPLOAD");
        var restrictedByAsha = new DocumentRules.Doc(ASHA, "RESTRICTED", null, "UPLOAD");
        assertThat(DocumentRules.canSee(ADMIN, restrictedBySunil)).isTrue();
        assertThat(DocumentRules.canSee(STAFF, restrictedBySunil)).isTrue();
        assertThat(DocumentRules.canSee(OTHER, restrictedBySunil)).isFalse();
        assertThat(DocumentRules.canSee(OTHER, team)).isTrue();
        assertThat(DocumentRules.canSee(STAFF, restrictedByAsha)).isFalse();
    }

    @Test
    void moduleFilesNeedThatModulesPermission() {
        var bill = new DocumentRules.Doc(ASHA, "TEAM", "EXPENSE_VIEW", "EXPENSE_BILL");
        assertThat(DocumentRules.canSee(STAFF, bill)).isTrue();
        assertThat(DocumentRules.canSee(OTHER, bill)).isFalse();
        assertThat(code(() -> DocumentRules.checkManage(ADMIN, bill))).isEqualTo("MANAGED_ELSEWHERE");
    }

    @Test
    void onlyUploaderOrAdminManagesUploads() {
        var mine = new DocumentRules.Doc(SUNIL, "TEAM", null, "UPLOAD");
        DocumentRules.checkManage(STAFF, mine);
        DocumentRules.checkManage(ADMIN, mine);
        assertThat(code(() -> DocumentRules.checkManage(OTHER, mine))).isEqualTo("NOT_YOUR_DOCUMENT");
    }

    @Test
    void confidentialCategoriesDefaultToRestricted() {
        assertThat(DocumentRules.visibility(null, "HR")).isEqualTo("RESTRICTED");
        assertThat(DocumentRules.visibility(null, "MEETING")).isEqualTo("RESTRICTED");
        assertThat(DocumentRules.visibility(null, "CAMPAIGN")).isEqualTo("TEAM");
        assertThat(DocumentRules.visibility("TEAM", "HR")).isEqualTo("TEAM");
        assertThat(code(() -> DocumentRules.visibility("PUBLIC", "HR"))).isEqualTo("INVALID_VISIBILITY");
        assertThat(code(() -> DocumentRules.category("SECRET"))).isEqualTo("INVALID_CATEGORY");
    }

    @Test
    void tagsAreNormalisedAndLimited() {
        assertThat(DocumentRules.tags(List.of(" #FY 2026-27 ", "audit", "Audit", ""))).containsExactly("fy 2026-27", "audit");
        assertThat(code(() -> DocumentRules.tags(List.of("<script>")))).isEqualTo("INVALID_TAG");
        assertThat(code(() -> DocumentRules.tags(List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k")))).isEqualTo("TOO_MANY_TAGS");
    }

    @Test
    void binThirtyDaysButFinanceKeptEightYears() {
        LocalDate today = LocalDate.of(2026, 11, 1);
        assertThat(DocumentRules.purgeAfter("PHOTOS_REPORTS", null, today, today)).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(DocumentRules.purgeAfter("FINANCE", LocalDate.of(2026, 4, 10), today, today)).isEqualTo(LocalDate.of(2034, 4, 10));
        assertThat(DocumentRules.purgeAfter("TAX_COMPLIANCE", LocalDate.of(2010, 1, 1), today, today)).isEqualTo(LocalDate.of(2026, 12, 1));
    }

    @Test
    void quotaByPlan() {
        long mb = 1024L * 1024;
        DocumentRules.checkQuota(2047 * mb, mb, 2048);
        assertThat(code(() -> DocumentRules.checkQuota(2047 * mb, mb + 1, 2048))).isEqualTo("ENTITLEMENT_REQUIRED:STORAGE_MB_MAX");
        DocumentRules.checkQuota(Long.MAX_VALUE / 2, mb, -1);
        assertThat(DocumentRules.human(1536L * mb)).isEqualTo("1.5 GB");
    }

    @Test
    void datesAndTitles() {
        LocalDate today = LocalDate.of(2026, 11, 1);
        assertThat(code(() -> DocumentRules.checkDates(today.plusDays(1), null, today))).isEqualTo("DATE_IN_FUTURE");
        assertThat(code(() -> DocumentRules.checkDates(today, today.minusDays(1), today))).isEqualTo("INVALID_EXPIRY");
        assertThat(DocumentRules.title("  80G   certificate ", null)).isEqualTo("80G certificate");
        assertThat(DocumentRules.title(null, "scan_001")).isEqualTo("scan_001");
        assertThatThrownBy(() -> DocumentRules.title(" ", null)).isInstanceOf(ApiException.class);
    }
}
