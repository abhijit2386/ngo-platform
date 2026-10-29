package org.ngoplatform.subscription.app;

import java.util.UUID;
import org.ngoplatform.commons.security.EntitlementResolver;
import org.ngoplatform.commons.security.Entitlements;
import org.springframework.stereotype.Component;

@Component
public class LocalEntitlementResolver implements EntitlementResolver {
    private final SubscriptionService service;
    public LocalEntitlementResolver(SubscriptionService service) { this.service = service; }
    @Override public Entitlements get(UUID ngoId) { return service.entitlements(ngoId); }
}
