package org.ngoplatform.document.app;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.security.Membership;

/**
 * Meetings and minutes (owner request 2026-11-01). Administrators schedule meetings, record and approve minutes;
 * team members see team meetings and meetings they are invited to, and tick off their action items.
 * Approved minutes are final (DB trigger); resolutions get numbers RES/&lt;FY&gt;/nnn on approval.
 * AGM: one per financial year (April-March); the summary shows whether this year's AGM is held.
 */
public final class MeetingRules {
    private MeetingRules() {}

    public enum Status { SCHEDULED, MINUTES_DRAFT, MINUTES_APPROVED, CANCELLED }

    public static final Set<String> TYPES = Set.of("AGM", "EGM", "BOARD", "COMMITTEE", "STAFF", "OTHER");
    public static final Set<String> MODES = Set.of("IN_PERSON", "ONLINE", "HYBRID");
    /** Meetings of the governing body are restricted to invited members by default. */
    public static final Set<String> RESTRICTED_BY_DEFAULT = Set.of("AGM", "EGM", "BOARD");
    public static final Set<String> RESULTS = Set.of("PASSED", "REJECTED", "DEFERRED");
    public static final int MAX_ATTENDEES = 200;

    public static void checkManage(Membership m) {
        if (!m.isAdmin()) throw ApiException.forbidden("ADMIN_REQUIRED", "Only administrators manage meetings and minutes");
    }

    public static String type(String t) {
        if (t == null || !TYPES.contains(t)) throw ApiException.badRequest("INVALID_TYPE", "Choose the type of meeting");
        return t;
    }

    public static String mode(String m) {
        if (m == null || m.isBlank()) return "IN_PERSON";
        if (!MODES.contains(m)) throw ApiException.badRequest("INVALID_MODE", "Choose in person, online or both");
        return m;
    }

    public static String visibility(String v, String type) {
        if (v == null || v.isBlank()) return RESTRICTED_BY_DEFAULT.contains(type) ? "RESTRICTED" : "TEAM";
        if (!DocumentRules.VISIBILITIES.contains(v)) throw ApiException.badRequest("INVALID_VISIBILITY", "Choose who can see this meeting");
        return v;
    }

    public static void checkEditDetails(Status s) {
        if (s == Status.MINUTES_APPROVED) throw ApiException.rule("MINUTES_APPROVED", "The minutes are approved; the meeting can no longer change");
        if (s == Status.CANCELLED) throw ApiException.rule("MEETING_CANCELLED", "This meeting was cancelled");
    }

    /** Minutes are recorded once the meeting has started, until they are approved. */
    public static void checkRecordMinutes(Status s, Instant startsAt, Instant now) {
        checkEditDetails(s);
        if (startsAt.isAfter(now)) throw ApiException.rule("MEETING_NOT_HELD", "Minutes can be recorded once the meeting has started");
    }

    public static void checkApprove(Status s, String minutes, long presentCount) {
        if (s != Status.MINUTES_DRAFT) throw ApiException.rule("NO_DRAFT_MINUTES", "Record the minutes before approving them");
        if (minutes == null || minutes.strip().length() < 20) throw ApiException.rule("MINUTES_TOO_SHORT", "Write the minutes (at least 20 characters)");
        if (presentCount == 0) throw ApiException.rule("NO_ATTENDANCE", "Mark who was present");
    }

    public static void checkCancel(Status s, String reason) {
        if (s != Status.SCHEDULED) throw ApiException.rule("CANNOT_CANCEL", "Only a scheduled meeting can be cancelled");
        DocumentRules.title(reason, null); // 2-200 characters
    }

    /** Quorum met when the number present reaches the required number (no requirement = met). */
    public static boolean quorumMet(Integer required, long present) { return required == null || present >= required; }

    public static String resolutionNo(String fy, int n) { return "RES/" + fy + "/" + String.format("%03d", n); }

    /** Indian financial year (April-March), e.g. 2026-27. */
    public static String financialYear(LocalDate d) {
        int start = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
        return start + "-" + String.format("%02d", (start + 1) % 100);
    }

    /**
     * The AGM held during a financial year adopts the previous year's accounts and is usually due within six months
     * of that year's end, i.e. by 30 September (check the NGO's own bye-laws / Act).
     */
    public static LocalDate agmDueBy(String fy) { return LocalDate.of(Integer.parseInt(fy.substring(0, 4)), 9, 30); }
}
