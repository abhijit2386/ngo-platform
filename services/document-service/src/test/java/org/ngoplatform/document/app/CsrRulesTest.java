package org.ngoplatform.document.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.ngoplatform.commons.error.ApiException;

class CsrRulesTest {
    static final LocalDate TODAY = LocalDate.of(2026, 11, 2);

    @Test
    void recognisesThePapersACsrTeamAsksFor() {
        assertThat(CsrRules.guessKind("80G certificate", List.of())).isEqualTo("REG_80G");
        assertThat(CsrRules.guessKind("12A certificate", List.of())).isEqualTo("REG_12A");
        assertThat(CsrRules.guessKind("PAN card of the NGO", List.of())).isEqualTo("PAN");
        assertThat(CsrRules.guessKind("CSR-1 registration acknowledgement", List.of())).isEqualTo("CSR1");
        assertThat(CsrRules.guessKind("MCA form", List.of("csr1"))).isEqualTo("CSR1");
        assertThat(CsrRules.guessKind("Audited accounts FY 2025-26", List.of())).isEqualTo("AUDITED_ACCOUNTS");
        assertThat(CsrRules.guessKind("Registration certificate", List.of())).isEqualTo("REGISTRATION");
        assertThat(CsrRules.guessKind("Trust deed / MoA", List.of())).isEqualTo("REGISTRATION");
        assertThat(CsrRules.guessKind("FCRA certificate", List.of())).isEqualTo("FCRA");
        assertThat(CsrRules.guessKind("Board of trustees 2026", List.of())).isEqualTo("BOARD_LIST");
        assertThat(CsrRules.guessKind("Annual report 2025-26", List.of())).isEqualTo("ANNUAL_REPORT");
        assertThat(CsrRules.guessKind("School visit photos", List.of("event"))).isNull();
        // "Company" or "panel" are not PAN
        assertThat(CsrRules.guessKind("Panel discussion notes", List.of())).isNull();
    }

    @Test
    void readinessBlocksDeletedOrExpiredAndWarnsAboutMissingPapers() {
        var items = List.of(
                new CsrRules.ItemState("PAN", "PAN card", "ACTIVE", null),
                new CsrRules.ItemState("REG_80G", "80G certificate", "ACTIVE", TODAY.minusDays(1)),
                new CsrRules.ItemState("REG_12A", "12A certificate", "ACTIVE", TODAY.plusDays(10)),
                new CsrRules.ItemState("OTHER", "Brochure", "DELETED", null));
        CsrRules.Readiness r = CsrRules.readiness(items, TODAY);
        assertThat(r.errors()).anyMatch(e -> e.startsWith("DOCUMENT_EXPIRED: 80G certificate"))
                .anyMatch(e -> e.startsWith("DOCUMENT_REMOVED: Brochure"));
        assertThat(r.warnings()).anyMatch(w -> w.startsWith("EXPIRES_SOON: 12A certificate"))
                .contains("MISSING: CSR1", "MISSING: AUDITED_ACCOUNTS", "MISSING: REGISTRATION")
                .doesNotContain("MISSING: PAN", "MISSING: FCRA");
        assertThat(r.ready()).isFalse();
        assertThat(CsrRules.readiness(List.of(), TODAY).errors()).anyMatch(e -> e.startsWith("EMPTY"));
    }

    @Test
    void completePackIsReady() {
        var items = CsrRules.KINDS.stream().filter(CsrRules.Kind::required)
                .map(k -> new CsrRules.ItemState(k.code(), k.label(), "ACTIVE", TODAY.plusYears(1))).toList();
        assertThat(CsrRules.readiness(items, TODAY).ready()).isTrue();
    }

    @Test
    void linkCodesAreLongRandomAndUrlSafe() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String c = CsrRules.newCode();
            assertThat(CsrRules.validCode(c)).isTrue();
            assertThat(seen.add(c)).isTrue();
        }
        assertThat(CsrRules.validCode("../../etc/passwd")).isFalse();
        assertThat(CsrRules.validCode("short")).isFalse();
    }

    @Test
    void checksInput() {
        assertThat(CsrRules.validDays(null)).isEqualTo(30);
        assertThatThrownBy(() -> CsrRules.validDays(91)).isInstanceOf(ApiException.class);
        assertThat(CsrRules.email(" CSR@Tata.com ")).isEqualTo("csr@tata.com");
        assertThatThrownBy(() -> CsrRules.email("not-an-email")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> CsrRules.recipient(" ")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> CsrRules.kind("PASSPORT")).isInstanceOf(ApiException.class);
    }
}
