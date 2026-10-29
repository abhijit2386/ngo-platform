package org.ngoplatform.commons.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.UUID;
import org.ngoplatform.commons.config.InternalClients;
import org.ngoplatform.commons.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Entitlements from subscription-service. Fresh cache 30s; if the service is down we serve the last known value
 * for up to 15 minutes (resilience), after which we fail closed.
 */
public class RestEntitlementResolver implements EntitlementResolver {
    private final RestClient client;
    private final Cache<UUID, Entitlements> fresh = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30)).maximumSize(20_000).build();
    private final Cache<UUID, Entitlements> stale = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(15)).maximumSize(20_000).build();

    public RestEntitlementResolver(InternalClients clients, String subscriptionBaseUrl) {
        this.client = clients.forBaseUrl(subscriptionBaseUrl);
    }

    @Override
    public Entitlements get(UUID ngoId) {
        Entitlements e = fresh.getIfPresent(ngoId);
        if (e != null) return e;
        try {
            e = client.get().uri("/internal/v1/entitlements/{ngoId}", ngoId).retrieve().body(Entitlements.class);
            fresh.put(ngoId, e);
            stale.put(ngoId, e);
            return e;
        } catch (RestClientException ex) {
            Entitlements last = stale.getIfPresent(ngoId);
            if (last != null) return last;
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ENTITLEMENTS_UNAVAILABLE",
                    "Subscription service temporarily unavailable. Please retry.");
        }
    }

    @Override
    public void evict(UUID ngoId) { fresh.invalidate(ngoId); }
}
