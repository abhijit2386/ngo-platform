package org.ngoplatform.identity.app;

import java.util.UUID;
import org.ngoplatform.commons.security.ProfileStatusResolver;
import org.springframework.stereotype.Component;

/** identity-service owns profiles, so the gate reads its own database (adult profile + phone for team members). */
@Component
public class LocalProfileStatusResolver implements ProfileStatusResolver {
    private final MeService me;

    public LocalProfileStatusResolver(MeService me) { this.me = me; }

    @Override
    public boolean isComplete(UUID userId) { return me.isUsable(userId); }
}
