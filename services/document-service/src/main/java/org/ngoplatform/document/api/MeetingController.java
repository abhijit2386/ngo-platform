package org.ngoplatform.document.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.security.AccessGuard;
import org.ngoplatform.commons.security.Membership;
import org.ngoplatform.commons.security.Permissions;
import org.ngoplatform.document.app.MeetingService;
import org.ngoplatform.document.app.NgoNames;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Meetings and minutes (GOVERNANCE_VIEW to see, administrators manage). */
@RestController
public class MeetingController {
    public record AttendeeRequest(UUID userId, String name, String designation) {}
    public record MeetingRequest(String type, String title, Instant startsAt, String mode, String venue, String onlineLink, String agenda,
                                 String visibility, Integer quorumRequired, List<AttendeeRequest> attendees, Long version) {
        MeetingService.MeetingInput input() {
            return new MeetingService.MeetingInput(type, title, startsAt, mode, venue, onlineLink, agenda, visibility, quorumRequired,
                    attendees == null ? null : attendees.stream().map(a -> new MeetingService.AttendeeInput(a.userId(), a.name(), a.designation())).toList());
        }
    }
    public record MinutesRequest(String minutes, String chairperson, Map<UUID, Boolean> attendance, Long version) {}
    public record ResolutionRequest(String text, String proposedBy, String secondedBy, Integer votesFor, Integer votesAgainst,
                                    Integer abstained, String result) {}
    public record ActionRequest(String text, UUID ownerUserId, String ownerName, LocalDate dueDate) {}
    public record VersionRequest(Long version) {}
    public record ReasonRequest(String reason) {}
    public record DoneRequest(boolean done) {}

    private final MeetingService svc;
    private final AccessGuard guard;
    private final NgoNames ngoNames;

    public MeetingController(MeetingService svc, AccessGuard guard, NgoNames ngoNames) {
        this.svc = svc; this.guard = guard; this.ngoNames = ngoNames;
    }

    private static final String BASE = "/api/v1/ngos/{ngoId}/meetings";

    @GetMapping(BASE)
    public List<MeetingService.Meeting> list(@PathVariable UUID ngoId, @RequestParam(defaultValue = "all") String when,
                                             @RequestParam(required = false) String type) {
        Membership m = guard.require(ngoId, Permissions.GOVERNANCE_VIEW);
        return svc.list(ngoId, m, when, type == null || type.isBlank() ? null : type);
    }

    @GetMapping(BASE + "/summary")
    public Map<String, Object> summary(@PathVariable UUID ngoId) {
        return svc.summary(ngoId, guard.require(ngoId, Permissions.GOVERNANCE_VIEW));
    }

    @PostMapping(BASE)
    @ResponseStatus(HttpStatus.CREATED)
    public MeetingService.Detail schedule(@PathVariable UUID ngoId, @RequestBody MeetingRequest req) {
        return svc.schedule(ngoId, guard.require(ngoId, Permissions.GOVERNANCE_MANAGE), req.input());
    }

    @GetMapping(BASE + "/{id}")
    public MeetingService.Detail get(@PathVariable UUID ngoId, @PathVariable UUID id) {
        return svc.get(ngoId, guard.require(ngoId, Permissions.GOVERNANCE_VIEW), id);
    }

    @PutMapping(BASE + "/{id}")
    public MeetingService.Detail update(@PathVariable UUID ngoId, @PathVariable UUID id, @RequestBody MeetingRequest req) {
        return svc.update(ngoId, guard.require(ngoId, Permissions.GOVERNANCE_MANAGE), id, req.input(), version(req.version()));
    }

    @PostMapping(BASE + "/{id}/minutes")
    public MeetingService.Detail minutes(@PathVariable UUID ngoId, @PathVariable UUID id, @RequestBody MinutesRequest req) {
        return svc.recordMinutes(ngoId, guard.require(ngoId, Permissions.GOVERNANCE_MANAGE), id, req.minutes(), req.chairperson(),
                req.attendance(), version(req.version()));
    }

    @PostMapping(BASE + "/{id}/resolutions")
    public MeetingService.Detail addResolution(@PathVariable UUID ngoId, @PathVariable UUID id, @RequestBody ResolutionRequest r) {
        return svc.addResolution(ngoId, guard.require(ngoId, Permissions.GOVERNANCE_MANAGE), id,
                new MeetingService.ResolutionInput(r.text(), r.proposedBy(), r.secondedBy(), r.votesFor(), r.votesAgainst(), r.abstained(), r.result()));
    }

    @DeleteMapping(BASE + "/{id}/resolutions/{rid}")
    public MeetingService.Detail removeResolution(@PathVariable UUID ngoId, @PathVariable UUID id, @PathVariable UUID rid) {
        return svc.removeResolution(ngoId, guard.require(ngoId, Permissions.GOVERNANCE_MANAGE), id, rid);
    }

    @PostMapping(BASE + "/{id}/actions")
    public MeetingService.Detail addAction(@PathVariable UUID ngoId, @PathVariable UUID id, @RequestBody ActionRequest a) {
        return svc.addAction(ngoId, guard.require(ngoId, Permissions.GOVERNANCE_MANAGE), id,
                new MeetingService.ActionInput(a.text(), a.ownerUserId(), a.ownerName(), a.dueDate()));
    }

    @DeleteMapping(BASE + "/{id}/actions/{aid}")
    public MeetingService.Detail removeAction(@PathVariable UUID ngoId, @PathVariable UUID id, @PathVariable UUID aid) {
        return svc.removeAction(ngoId, guard.require(ngoId, Permissions.GOVERNANCE_MANAGE), id, aid);
    }

    @PostMapping(BASE + "/{id}/approve")
    public MeetingService.Detail approve(@PathVariable UUID ngoId, @PathVariable UUID id, @RequestBody VersionRequest req) {
        Membership m = guard.require(ngoId, Permissions.GOVERNANCE_MANAGE);
        return svc.approve(ngoId, m, id, version(req.version()), ngoNames.name(ngoId));
    }

    @PostMapping(BASE + "/{id}/cancel")
    public MeetingService.Detail cancel(@PathVariable UUID ngoId, @PathVariable UUID id, @RequestBody ReasonRequest req) {
        return svc.cancel(ngoId, guard.require(ngoId, Permissions.GOVERNANCE_MANAGE), id, req.reason());
    }

    @GetMapping("/api/v1/ngos/{ngoId}/action-items")
    public List<Map<String, Object>> actions(@PathVariable UUID ngoId, @RequestParam(defaultValue = "true") boolean mine,
                                             @RequestParam(required = false) String status) {
        return svc.actions(ngoId, guard.require(ngoId, Permissions.GOVERNANCE_VIEW), mine, status);
    }

    @PutMapping("/api/v1/ngos/{ngoId}/action-items/{aid}")
    public MeetingService.Action setDone(@PathVariable UUID ngoId, @PathVariable UUID aid, @RequestBody DoneRequest req) {
        return svc.setActionStatus(ngoId, guard.require(ngoId, Permissions.GOVERNANCE_VIEW), aid, req.done());
    }

    private static long version(Long v) {
        if (v == null) throw ApiException.badRequest("VERSION_REQUIRED", "version is required");
        return v;
    }
}
