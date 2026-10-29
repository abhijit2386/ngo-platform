package org.ngoplatform.notification.api;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.notification.app.Directory;
import org.ngoplatform.notification.app.NotificationPlanner;
import org.springframework.web.bind.annotation.*;

/** Service-to-service send (X-Internal-Token). Never routed by the gateway. */
@RestController
@RequestMapping("/internal/v1/notifications")
public class InternalController {
    private final NotificationPlanner planner;
    private final Directory directory;

    public InternalController(NotificationPlanner planner, Directory directory) {
        this.planner = planner; this.directory = directory;
    }

    /** Idempotent per requestId (callers pass a stable id, e.g. the receipt id). */
    @PostMapping
    public Dtos.SendResult send(@Valid @RequestBody Dtos.SendRequest r) {
        NotificationPlanner.Recipient to;
        if (r.userId() != null) {
            var p = directory.user(r.userId()).orElseThrow(() -> ApiException.notFound("User"));
            to = new NotificationPlanner.Recipient(r.userId(), r.email() != null ? r.email() : p.email(), p.name(),
                    r.language() != null ? r.language() : p.language());
        } else if (r.email() != null) {
            to = new NotificationPlanner.Recipient(null, r.email(), null, r.language());
        } else {
            throw ApiException.badRequest("NO_RECIPIENT", "userId or email is required");
        }
        var res = planner.plan(new NotificationPlanner.Request(r.templateCode(), r.ngoId(), List.of(to),
                r.vars() == null ? Map.of() : r.vars(), r.link(), r.resourceType(), r.resourceId(), r.requestId(),
                r.inApp(), r.sendEmail()));
        return new Dtos.SendResult(res.inApp(), res.emailsQueued(), res.suppressed());
    }
}
