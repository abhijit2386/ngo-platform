package org.ngoplatform.subscription.api;

import java.util.UUID;
import org.ngoplatform.commons.security.Entitlements;
import org.ngoplatform.subscription.app.SubscriptionService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1")
public class InternalController {
    private final SubscriptionService svc;
    public InternalController(SubscriptionService svc) { this.svc = svc; }

    @GetMapping("/entitlements/{ngoId}")
    public Entitlements entitlements(@PathVariable UUID ngoId) { return svc.entitlements(ngoId); }
}
