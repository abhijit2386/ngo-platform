package org.ngoplatform.document.app;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.ngoplatform.commons.audit.AuditLog;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.events.EventPublisher;
import org.ngoplatform.commons.events.EventTypes;
import org.ngoplatform.commons.security.Membership;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** AGM / EGM / board / committee / staff meetings: agenda, attendance, minutes, resolutions, action items. */
@Service
public class MeetingService {
    public record Meeting(UUID id, UUID ngoId, String type, String title, Instant startsAt, String mode, String venue, String onlineLink,
                          String agenda, String visibility, String financialYear, String status, Integer quorumRequired,
                          String chairperson, String minutes, UUID minutesBy, Instant minutesAt, UUID approvedBy, Instant approvedAt,
                          UUID minutesDocumentId, String cancelReason, UUID createdBy, Instant createdAt, long version) {
        MeetingRules.Status st() { return MeetingRules.Status.valueOf(status); }
    }

    public record Attendee(UUID id, UUID userId, String name, String designation, Boolean present) {}
    public record Resolution(UUID id, String number, String text, String proposedBy, String secondedBy, Integer votesFor,
                             Integer votesAgainst, Integer abstained, String result) {}
    public record Action(UUID id, UUID meetingId, String text, UUID ownerUserId, String ownerName, LocalDate dueDate, String status,
                         UUID doneBy, Instant doneAt) {}
    public record Detail(Meeting meeting, List<Attendee> attendees, List<Resolution> resolutions, List<Action> actions,
                         long presentCount, boolean quorumMet) {}

    public record AttendeeInput(UUID userId, String name, String designation) {}
    public record MeetingInput(String type, String title, Instant startsAt, String mode, String venue, String onlineLink, String agenda,
                               String visibility, Integer quorumRequired, List<AttendeeInput> attendees) {}
    public record ResolutionInput(String text, String proposedBy, String secondedBy, Integer votesFor, Integer votesAgainst,
                                  Integer abstained, String result) {}
    public record ActionInput(String text, UUID ownerUserId, String ownerName, LocalDate dueDate) {}

    private final JdbcTemplate jdbc;
    private final TeamDirectory team;
    private final DocumentService docs;
    private final EventPublisher events;
    private final AuditLog audit;
    private final Clock clock;

    public MeetingService(JdbcTemplate jdbc, TeamDirectory team, DocumentService docs, EventPublisher events, AuditLog audit, Clock clock) {
        this.jdbc = jdbc; this.team = team; this.docs = docs; this.events = events; this.audit = audit; this.clock = clock;
    }

    static final String COLS = "id, ngo_id, type, title, starts_at, mode, venue, online_link, agenda, visibility, financial_year, status, "
            + "quorum_required, chairperson, minutes, minutes_by, minutes_at, approved_by, approved_at, minutes_document_id, cancel_reason, "
            + "created_by, created_at, version";

    static final RowMapper<Meeting> ROW = (rs, i) -> new Meeting(rs.getObject("id", UUID.class), rs.getObject("ngo_id", UUID.class),
            rs.getString("type"), rs.getString("title"), ts(rs, "starts_at"), rs.getString("mode"), rs.getString("venue"),
            rs.getString("online_link"), rs.getString("agenda"), rs.getString("visibility"), rs.getString("financial_year"),
            rs.getString("status"), (Integer) rs.getObject("quorum_required"), rs.getString("chairperson"), rs.getString("minutes"),
            rs.getObject("minutes_by", UUID.class), ts(rs, "minutes_at"), rs.getObject("approved_by", UUID.class), ts(rs, "approved_at"),
            rs.getObject("minutes_document_id", UUID.class), rs.getString("cancel_reason"), rs.getObject("created_by", UUID.class),
            ts(rs, "created_at"), rs.getLong("version"));

    static final RowMapper<Action> ACTION = (rs, i) -> new Action(rs.getObject("id", UUID.class), rs.getObject("meeting_id", UUID.class),
            rs.getString("text"), rs.getObject("owner_user_id", UUID.class), rs.getString("owner_name"), rs.getObject("due_date", LocalDate.class),
            rs.getString("status"), rs.getObject("done_by", UUID.class), ts(rs, "done_at"));

    private static Instant ts(ResultSet rs, String c) throws SQLException {
        Timestamp t = rs.getTimestamp(c);
        return t == null ? null : t.toInstant();
    }

    // ---------------- reading

    Meeting find(UUID ngoId, UUID id) {
        List<Meeting> l = jdbc.query("select " + COLS + " from meetings where id = ? and ngo_id = ?", ROW, id, ngoId);
        if (l.isEmpty()) throw ApiException.notFound("Meeting");
        return l.getFirst();
    }

    private boolean invited(UUID meetingId, UUID userId) {
        Integer n = jdbc.queryForObject("select count(*) from meeting_attendees where meeting_id = ? and user_id = ?", Integer.class, meetingId, userId);
        return n != null && n > 0;
    }

    boolean canSee(Membership m, Meeting x) {
        return m.isAdmin() || "TEAM".equals(x.visibility()) || invited(x.id(), m.userId());
    }

    public Detail get(UUID ngoId, Membership m, UUID id) {
        Meeting x = find(ngoId, id);
        if (!canSee(m, x)) throw ApiException.notFound("Meeting");
        return detail(x);
    }

    Detail detail(Meeting x) {
        List<Attendee> att = jdbc.query("select id, user_id, name, designation, present from meeting_attendees where meeting_id = ? order by position, name",
                (rs, i) -> new Attendee(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        (Boolean) rs.getObject(5)), x.id());
        List<Resolution> res = jdbc.query("select id, number, text, proposed_by, seconded_by, votes_for, votes_against, abstained, result "
                        + "from meeting_resolutions where meeting_id = ? order by position",
                (rs, i) -> new Resolution(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        (Integer) rs.getObject(6), (Integer) rs.getObject(7), (Integer) rs.getObject(8), rs.getString(9)), x.id());
        List<Action> acts = jdbc.query("select * from meeting_actions where meeting_id = ? order by created_at", ACTION, x.id());
        long present = att.stream().filter(a -> Boolean.TRUE.equals(a.present())).count();
        return new Detail(x, att, res, acts, present, MeetingRules.quorumMet(x.quorumRequired(), present));
    }

    /** Meetings the member may see; when = upcoming | past | all. */
    public List<Meeting> list(UUID ngoId, Membership m, String when, String type) {
        StringBuilder sql = new StringBuilder("select " + COLS + " from meetings where ngo_id = ?");
        List<Object> args = new ArrayList<>(List.of(ngoId));
        if (!m.isAdmin()) {
            sql.append(" and (visibility = 'TEAM' or exists (select 1 from meeting_attendees a where a.meeting_id = meetings.id and a.user_id = ?))");
            args.add(m.userId());
        }
        if (type != null) { sql.append(" and type = ?"); args.add(MeetingRules.type(type)); }
        if ("upcoming".equals(when)) sql.append(" and status = 'SCHEDULED' and starts_at >= now() - interval '1 day' order by starts_at");
        else if ("past".equals(when)) sql.append(" and (status <> 'SCHEDULED' or starts_at < now() - interval '1 day') order by starts_at desc");
        else sql.append(" order by starts_at desc");
        sql.append(" limit 200");
        return jdbc.query(sql.toString(), ROW, args.toArray());
    }

    /** AGM status for this financial year, upcoming meetings and open action items. */
    public Map<String, Object> summary(UUID ngoId, Membership m) {
        LocalDate today = docs.today();
        String fy = MeetingRules.financialYear(today);
        List<Meeting> agmThisYear = jdbc.query("select " + COLS + " from meetings where ngo_id = ? and type = 'AGM' and financial_year = ? "
                + "and status <> 'CANCELLED' order by starts_at desc limit 1", ROW, ngoId, fy);
        List<Meeting> lastAgm = jdbc.query("select " + COLS + " from meetings where ngo_id = ? and type = 'AGM' and status = 'MINUTES_APPROVED' "
                + "order by starts_at desc limit 1", ROW, ngoId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("financialYear", fy);
        out.put("agmDueBy", MeetingRules.agmDueBy(fy));
        out.put("agmThisYear", agmThisYear.isEmpty() ? null : brief(agmThisYear.getFirst()));
        out.put("lastAgm", lastAgm.isEmpty() ? null : brief(lastAgm.getFirst()));
        out.put("upcoming", list(ngoId, m, "upcoming", null).size());
        out.put("myOpenActions", count("select count(*) from meeting_actions where ngo_id = ? and owner_user_id = ? and status = 'OPEN'", ngoId, m.userId()));
        out.put("minutesToApprove", m.isAdmin() ? count("select count(*) from meetings where ngo_id = ? and status = 'MINUTES_DRAFT'", ngoId) : 0);
        return out;
    }

    private static Map<String, Object> brief(Meeting x) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("id", x.id());
        b.put("title", x.title());
        b.put("startsAt", x.startsAt());
        b.put("status", x.status());
        return b;
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    /** Action items: the member's own (mine) or, for administrators, everyone's. */
    public List<Map<String, Object>> actions(UUID ngoId, Membership m, boolean mine, String status) {
        StringBuilder sql = new StringBuilder("select a.*, mt.title as meeting_title, mt.starts_at as meeting_at from meeting_actions a "
                + "join meetings mt on mt.id = a.meeting_id where a.ngo_id = ?");
        List<Object> args = new ArrayList<>(List.of(ngoId));
        if (mine || !m.isAdmin()) { sql.append(" and a.owner_user_id = ?"); args.add(m.userId()); }
        if (status != null) { sql.append(" and a.status = ?"); args.add("DONE".equals(status) ? "DONE" : "OPEN"); }
        sql.append(" order by a.status desc, a.due_date nulls last, a.created_at limit 300");
        return jdbc.query(sql.toString(), (rs, i) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            Action a = ACTION.mapRow(rs, i);
            r.put("action", a);
            r.put("meetingTitle", rs.getString("meeting_title"));
            r.put("meetingAt", ts(rs, "meeting_at"));
            return r;
        }, args.toArray());
    }

    // ---------------- writing

    @Transactional
    public Detail schedule(UUID ngoId, Membership m, MeetingInput in) {
        MeetingRules.checkManage(m);
        String type = MeetingRules.type(in.type());
        validate(in);
        UUID id = UUID.randomUUID();
        LocalDate day = in.startsAt().atZone(DocumentService.INDIA).toLocalDate();
        jdbc.update("insert into meetings(id, ngo_id, type, title, starts_at, mode, venue, online_link, agenda, visibility, financial_year, status, "
                        + "quorum_required, created_by) values (?,?,?,?,?,?,?,?,?,?,?,'SCHEDULED',?,?)",
                id, ngoId, type, DocumentRules.title(in.title(), null), Timestamp.from(in.startsAt()), MeetingRules.mode(in.mode()),
                DocumentService.limit(in.venue(), 200), DocumentService.limit(in.onlineLink(), 300), DocumentService.limit(in.agenda(), 4000),
                MeetingRules.visibility(in.visibility(), type), MeetingRules.financialYear(day), in.quorumRequired(), m.userId());
        replaceAttendees(ngoId, id, in.attendees());
        Meeting x = find(ngoId, id);
        List<UUID> invitees = jdbc.queryForList("select user_id from meeting_attendees where meeting_id = ? and user_id is not null and user_id <> ?",
                UUID.class, id, m.userId());
        if (!invitees.isEmpty()) {
            events.publish(EventTypes.MEETING_SCHEDULED, ngoId, id, Map.of("title", x.title(), "type", type,
                    "startsAt", x.startsAt().toString(), "userIds", String.join(",", invitees.stream().map(UUID::toString).toList())));
        }
        audit.record(ngoId, m.userId(), "MEETING_SCHEDULED", "MEETING", id, Map.of("type", type));
        return detail(x);
    }

    @Transactional
    public Detail update(UUID ngoId, Membership m, UUID id, MeetingInput in, long expected) {
        MeetingRules.checkManage(m);
        Meeting x = find(ngoId, id);
        MeetingRules.checkEditDetails(x.st());
        String type = MeetingRules.type(in.type());
        validate(in);
        LocalDate day = in.startsAt().atZone(DocumentService.INDIA).toLocalDate();
        int n = jdbc.update("update meetings set type = ?, title = ?, starts_at = ?, mode = ?, venue = ?, online_link = ?, agenda = ?, visibility = ?, "
                        + "financial_year = ?, quorum_required = ?, updated_at = now(), version = version + 1 where id = ? and ngo_id = ? and version = ?",
                type, DocumentRules.title(in.title(), null), Timestamp.from(in.startsAt()), MeetingRules.mode(in.mode()),
                DocumentService.limit(in.venue(), 200), DocumentService.limit(in.onlineLink(), 300), DocumentService.limit(in.agenda(), 4000),
                MeetingRules.visibility(in.visibility(), type), MeetingRules.financialYear(day), in.quorumRequired(), id, ngoId, expected);
        if (n == 0) throw ApiException.conflict("STALE_VERSION", "The meeting was changed by someone else. Reload.");
        if (in.attendees() != null) replaceAttendees(ngoId, id, in.attendees());
        return detail(find(ngoId, id));
    }

    private void validate(MeetingInput in) {
        if (in.startsAt() == null) throw ApiException.badRequest("DATE_REQUIRED", "Choose the date and time");
        if (in.startsAt().isBefore(clock.instant().minusSeconds(3L * 366 * 24 * 3600))) {
            throw ApiException.badRequest("DATE_TOO_OLD", "Meetings older than 3 years cannot be added");
        }
        if (in.quorumRequired() != null && (in.quorumRequired() < 1 || in.quorumRequired() > 1000)) {
            throw ApiException.badRequest("INVALID_QUORUM", "Quorum must be between 1 and 1000");
        }
        if (in.attendees() != null && in.attendees().size() > MeetingRules.MAX_ATTENDEES) {
            throw ApiException.badRequest("TOO_MANY_ATTENDEES", "At most " + MeetingRules.MAX_ATTENDEES + " attendees");
        }
    }

    /** Keeps recorded attendance of people who stay on the list. */
    private void replaceAttendees(UUID ngoId, UUID meetingId, List<AttendeeInput> list) {
        if (list == null) return;
        Map<String, Boolean> present = new LinkedHashMap<>();
        jdbc.query("select coalesce(user_id::text, lower(name)), present from meeting_attendees where meeting_id = ?",
                rs -> { present.put(rs.getString(1), (Boolean) rs.getObject(2)); }, meetingId);
        jdbc.update("delete from meeting_attendees where meeting_id = ?", meetingId);
        Set<String> seen = new HashSet<>();
        // Team members may be sent by id only; their current name comes from the identity service.
        List<UUID> unnamed = list.stream().filter(a -> a.userId() != null && (a.name() == null || a.name().isBlank()))
                .map(AttendeeInput::userId).toList();
        Map<UUID, String> teamNames = unnamed.isEmpty() ? Map.of() : team.names(unnamed);
        int pos = 0;
        for (AttendeeInput a : list) {
            String name = DocumentService.limit(a.name() == null || a.name().isBlank() ? teamNames.get(a.userId()) : a.name(), 120);
            if (a.userId() != null) team.activeMember(ngoId, a.userId());
            if (name == null || name.length() < 2) throw ApiException.badRequest("INVALID_ATTENDEE", "Each attendee needs a name");
            String key = a.userId() != null ? a.userId().toString() : name.toLowerCase();
            if (!seen.add(key)) continue;
            jdbc.update("insert into meeting_attendees(id, meeting_id, user_id, name, designation, present, position) values (?,?,?,?,?,?,?)",
                    UUID.randomUUID(), meetingId, a.userId(), name, DocumentService.limit(a.designation(), 80), present.get(key), pos++);
        }
    }

    @Transactional
    public Detail recordMinutes(UUID ngoId, Membership m, UUID id, String minutes, String chairperson, Map<UUID, Boolean> attendance, long expected) {
        MeetingRules.checkManage(m);
        Meeting x = find(ngoId, id);
        MeetingRules.checkRecordMinutes(x.st(), x.startsAt(), clock.instant());
        String text = minutes == null ? null : minutes.strip();
        if (text != null && text.length() > 20000) throw ApiException.badRequest("MINUTES_TOO_LONG", "Minutes can be at most 20,000 characters");
        int n = jdbc.update("update meetings set minutes = ?, chairperson = ?, status = 'MINUTES_DRAFT', minutes_by = ?, minutes_at = now(), "
                        + "updated_at = now(), version = version + 1 where id = ? and ngo_id = ? and version = ?",
                text, DocumentService.limit(chairperson, 120), m.userId(), id, ngoId, expected);
        if (n == 0) throw ApiException.conflict("STALE_VERSION", "The meeting was changed by someone else. Reload.");
        if (attendance != null) {
            for (var e : attendance.entrySet()) {
                jdbc.update("update meeting_attendees set present = ? where id = ? and meeting_id = ?", e.getValue(), e.getKey(), id);
            }
        }
        return detail(find(ngoId, id));
    }

    @Transactional
    public Detail addResolution(UUID ngoId, Membership m, UUID id, ResolutionInput in) {
        MeetingRules.checkManage(m);
        Meeting x = find(ngoId, id);
        MeetingRules.checkRecordMinutes(x.st(), x.startsAt(), clock.instant());
        String text = DocumentService.limit(in.text(), 2000);
        if (text == null || text.length() < 5) throw ApiException.badRequest("INVALID_RESOLUTION", "Write the resolution (at least 5 characters)");
        String result = in.result() == null ? "PASSED" : in.result();
        if (!MeetingRules.RESULTS.contains(result)) throw ApiException.badRequest("INVALID_RESULT", "Passed, rejected or deferred");
        for (Integer v : new Integer[]{in.votesFor(), in.votesAgainst(), in.abstained()}) {
            if (v != null && (v < 0 || v > 100000)) throw ApiException.badRequest("INVALID_VOTES", "Votes must be 0 or more");
        }
        Integer pos = jdbc.queryForObject("select coalesce(max(position), -1) + 1 from meeting_resolutions where meeting_id = ?", Integer.class, id);
        jdbc.update("insert into meeting_resolutions(id, meeting_id, ngo_id, text, proposed_by, seconded_by, votes_for, votes_against, abstained, "
                        + "result, position) values (?,?,?,?,?,?,?,?,?,?,?)", UUID.randomUUID(), id, ngoId, text,
                DocumentService.limit(in.proposedBy(), 120), DocumentService.limit(in.secondedBy(), 120), in.votesFor(), in.votesAgainst(),
                in.abstained(), result, pos);
        return detail(x);
    }

    @Transactional
    public Detail removeResolution(UUID ngoId, Membership m, UUID id, UUID resolutionId) {
        MeetingRules.checkManage(m);
        Meeting x = find(ngoId, id);
        MeetingRules.checkEditDetails(x.st());
        jdbc.update("delete from meeting_resolutions where id = ? and meeting_id = ?", resolutionId, id);
        return detail(x);
    }

    @Transactional
    public Detail addAction(UUID ngoId, Membership m, UUID id, ActionInput in) {
        MeetingRules.checkManage(m);
        Meeting x = find(ngoId, id);
        MeetingRules.checkEditDetails(x.st());
        String text = DocumentService.limit(in.text(), 500);
        if (text == null || text.length() < 3) throw ApiException.badRequest("INVALID_ACTION", "Describe the action (at least 3 characters)");
        String owner = DocumentService.limit(in.ownerName(), 120);
        if (in.ownerUserId() != null) {
            team.activeMember(ngoId, in.ownerUserId());
            if (owner == null) owner = team.names(List.of(in.ownerUserId())).get(in.ownerUserId());
        }
        UUID actionId = UUID.randomUUID();
        jdbc.update("insert into meeting_actions(id, meeting_id, ngo_id, text, owner_user_id, owner_name, due_date) values (?,?,?,?,?,?,?)",
                actionId, id, ngoId, text, in.ownerUserId(), owner, in.dueDate());
        if (in.ownerUserId() != null && !in.ownerUserId().equals(m.userId())) {
            events.publish(EventTypes.MEETING_ACTION_ASSIGNED, ngoId, actionId, Map.of("meetingId", id.toString(), "meetingTitle", x.title(),
                    "text", text, "ownerUserId", in.ownerUserId().toString(), "dueDate", in.dueDate() == null ? "" : in.dueDate().toString()));
        }
        return detail(x);
    }

    /** The owner or an administrator ticks an action off (also after the minutes are approved). */
    @Transactional
    public Action setActionStatus(UUID ngoId, Membership m, UUID actionId, boolean done) {
        List<Action> l = jdbc.query("select * from meeting_actions where id = ? and ngo_id = ?", ACTION, actionId, ngoId);
        if (l.isEmpty()) throw ApiException.notFound("Action item");
        Action a = l.getFirst();
        if (!m.isAdmin() && !m.userId().equals(a.ownerUserId())) throw ApiException.forbidden("NOT_YOUR_ACTION", "Only the owner or an administrator can update this");
        jdbc.update("update meeting_actions set status = ?, done_by = ?, done_at = ? where id = ?", done ? "DONE" : "OPEN",
                done ? m.userId() : null, done ? Timestamp.from(clock.instant()) : null, actionId);
        return jdbc.query("select * from meeting_actions where id = ?", ACTION, actionId).getFirst();
    }

    @Transactional
    public Detail removeAction(UUID ngoId, Membership m, UUID id, UUID actionId) {
        MeetingRules.checkManage(m);
        MeetingRules.checkEditDetails(find(ngoId, id).st());
        jdbc.update("delete from meeting_actions where id = ? and meeting_id = ?", actionId, id);
        return detail(find(ngoId, id));
    }

    @Transactional
    public Detail cancel(UUID ngoId, Membership m, UUID id, String reason) {
        MeetingRules.checkManage(m);
        Meeting x = find(ngoId, id);
        MeetingRules.checkCancel(x.st(), reason);
        jdbc.update("update meetings set status = 'CANCELLED', cancel_reason = ?, updated_at = now(), version = version + 1 where id = ?",
                reason.strip(), id);
        audit.record(ngoId, m.userId(), "MEETING_CANCELLED", "MEETING", id, Map.of());
        return detail(find(ngoId, id));
    }

    /**
     * Approves the minutes: resolutions get their numbers, the minutes PDF is filed in the document vault (category
     * Meetings, same visibility as the meeting) and the meeting is locked.
     */
    @Transactional
    public Detail approve(UUID ngoId, Membership m, UUID id, long expected, String ngoName) {
        MeetingRules.checkManage(m);
        Meeting x = find(ngoId, id);
        if (x.version() != expected) throw ApiException.conflict("STALE_VERSION", "The minutes were changed after you opened them. Review the latest version.");
        Detail d = detail(x);
        MeetingRules.checkApprove(x.st(), x.minutes(), d.presentCount());
        String fy = x.financialYear();
        for (Resolution r : d.resolutions()) {
            Integer n = jdbc.queryForObject("insert into resolution_counters(ngo_id, fy, last_no) values (?,?,1) on conflict (ngo_id, fy) "
                    + "do update set last_no = resolution_counters.last_no + 1 returning last_no", Integer.class, ngoId, fy);
            jdbc.update("update meeting_resolutions set number = ? where id = ?", MeetingRules.resolutionNo(fy, n), r.id());
        }
        Detail numbered = detail(x);
        Map<UUID, String> names = team.names(java.util.Arrays.asList(x.minutesBy(), m.userId()));
        byte[] pdf = MinutesPdf.render(numbered, ngoName, names.get(x.minutesBy()), names.get(m.userId()), clock.instant());
        LocalDate day = x.startsAt().atZone(DocumentService.INDIA).toLocalDate();
        UUID docId = docs.store(ngoId, m.userId(), "MEETING_MINUTES", id.toString(), "Minutes - " + x.title(), "MEETING", x.visibility(),
                pdf, "MEETING", id, x.title(), day);
        jdbc.update("update meetings set status = 'MINUTES_APPROVED', approved_by = ?, approved_at = now(), minutes_document_id = ?, "
                + "updated_at = now(), version = version + 1 where id = ?", m.userId(), docId, id);
        audit.record(ngoId, m.userId(), "MINUTES_APPROVED", "MEETING", id, Map.of("resolutions", numbered.resolutions().size()));
        return detail(find(ngoId, id));
    }
}
