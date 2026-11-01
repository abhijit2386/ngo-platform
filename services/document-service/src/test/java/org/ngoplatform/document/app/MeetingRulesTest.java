package org.ngoplatform.document.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.ngoplatform.document.app.MeetingRules.Status;

class MeetingRulesTest {
    @Test
    void governingBodyMeetingsAreRestrictedByDefault() {
        assertThat(MeetingRules.visibility(null, "AGM")).isEqualTo("RESTRICTED");
        assertThat(MeetingRules.visibility(null, "BOARD")).isEqualTo("RESTRICTED");
        assertThat(MeetingRules.visibility(null, "STAFF")).isEqualTo("TEAM");
        assertThat(DocumentRulesTest.code(() -> MeetingRules.checkManage(DocumentRulesTest.STAFF))).isEqualTo("ADMIN_REQUIRED");
        MeetingRules.checkManage(DocumentRulesTest.ADMIN);
    }

    @Test
    void minutesAfterTheMeetingAndLockedOnceApproved() {
        Instant now = Instant.parse("2026-11-01T10:00:00Z");
        MeetingRules.checkRecordMinutes(Status.SCHEDULED, now.minusSeconds(60), now);
        assertThat(DocumentRulesTest.code(() -> MeetingRules.checkRecordMinutes(Status.SCHEDULED, now.plusSeconds(60), now))).isEqualTo("MEETING_NOT_HELD");
        assertThat(DocumentRulesTest.code(() -> MeetingRules.checkRecordMinutes(Status.MINUTES_APPROVED, now.minusSeconds(60), now))).isEqualTo("MINUTES_APPROVED");
        assertThat(DocumentRulesTest.code(() -> MeetingRules.checkApprove(Status.SCHEDULED, "x".repeat(30), 3))).isEqualTo("NO_DRAFT_MINUTES");
        assertThat(DocumentRulesTest.code(() -> MeetingRules.checkApprove(Status.MINUTES_DRAFT, "short", 3))).isEqualTo("MINUTES_TOO_SHORT");
        assertThat(DocumentRulesTest.code(() -> MeetingRules.checkApprove(Status.MINUTES_DRAFT, "x".repeat(30), 0))).isEqualTo("NO_ATTENDANCE");
        MeetingRules.checkApprove(Status.MINUTES_DRAFT, "x".repeat(30), 3);
        assertThat(DocumentRulesTest.code(() -> MeetingRules.checkCancel(Status.MINUTES_DRAFT, "Postponed"))).isEqualTo("CANNOT_CANCEL");
    }

    @Test
    void quorumYearAndNumbers() {
        assertThat(MeetingRules.quorumMet(5, 4)).isFalse();
        assertThat(MeetingRules.quorumMet(5, 5)).isTrue();
        assertThat(MeetingRules.quorumMet(null, 0)).isTrue();
        assertThat(MeetingRules.financialYear(LocalDate.of(2026, 3, 31))).isEqualTo("2025-26");
        assertThat(MeetingRules.financialYear(LocalDate.of(2026, 4, 1))).isEqualTo("2026-27");
        assertThat(MeetingRules.agmDueBy("2026-27")).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(MeetingRules.resolutionNo("2026-27", 7)).isEqualTo("RES/2026-27/007");
    }

    @Test
    void minutesPdfRenders() throws Exception {
        UUID id = UUID.randomUUID();
        var m = new MeetingService.Meeting(id, UUID.randomUUID(), "AGM", "Annual General Meeting 2026", Instant.parse("2026-09-20T05:30:00Z"),
                "IN_PERSON", "Community hall, Wadi", null, "1. Adopt accounts for FY 2025-26\n2. Appoint auditor", "RESTRICTED", "2026-27",
                "MINUTES_APPROVED", 3, "Asha Patil", "The meeting started at 11 AM. The accounts were presented by the treasurer.\n\n"
                + "Members discussed the school books campaign. वार्षिक सभा संपन्न झाली.", null, null, null, null, null, null, null, null, 1);
        var d = new MeetingService.Detail(m, List.of(new MeetingService.Attendee(UUID.randomUUID(), null, "Asha Patil", "President", true),
                new MeetingService.Attendee(UUID.randomUUID(), null, "Ravi Kulkarni", "Secretary", true),
                new MeetingService.Attendee(UUID.randomUUID(), null, "Priya Deshmukh", "Treasurer", true),
                new MeetingService.Attendee(UUID.randomUUID(), null, "Sunil More", null, false)),
                List.of(new MeetingService.Resolution(UUID.randomUUID(), "RES/2026-27/001", "the audited accounts for FY 2025-26 be adopted.",
                        "Ravi Kulkarni", "Priya Deshmukh", 3, 0, 0, "PASSED")),
                List.of(new MeetingService.Action(UUID.randomUUID(), id, "File Form 10B with the auditor", null, "Priya Deshmukh",
                        LocalDate.of(2026, 10, 31), "OPEN", null, null)), 3, true);
        byte[] pdf = MinutesPdf.render(d, "Asha Seva", "Ravi Kulkarni", "Asha Patil", Instant.parse("2026-09-25T10:00:00Z"));
        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
            assertThat(text).contains("Minutes of the Annual General Meeting").contains("RES/2026-27/001").contains("Quorum present (3 of 3")
                    .contains("Chairperson").contains("File Form 10B");
        }
    }
}
