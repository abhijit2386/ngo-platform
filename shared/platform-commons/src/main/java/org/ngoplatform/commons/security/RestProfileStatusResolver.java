package org.ngoplatform.commons.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.ngoplatform.commons.config.InternalClients;
import org.ngoplatform.commons.error.ApiException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Asks identity-service. Only "complete" answers are cached: a user who has just completed the profile is let
 * through on the next request, while a completed profile costs one call per user per minute. The period is short
 * because joining an NGO adds the verified-phone rule.
 */
public class RestProfileStatusResolver implements ProfileStatusResolver {
    private final RestClient client;
    private final Cache<UUID, Boolean> complete = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1)).maximumSize(100_000).build();

    public RestProfileStatusResolver(InternalClients clients, String identityBaseUrl) {
        this.client = clients.forBaseUrl(identityBaseUrl);
    }

    @Override
    public boolean isComplete(UUID userId) {
        if (complete.getIfPresent(userId) != null) return true;
        boolean ok = fetch(userId);
        if (ok) complete.put(userId, Boolean.TRUE);
        return ok;
    }

    private boolean fetch(UUID userId) {
        try {
            Map<String, Object> m = client.get().uri("/internal/v1/users/{id}/profile-status", userId).retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return m != null && Boolean.TRUE.equals(m.get("complete"));
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        } catch (RestClientException e) {
            // Fail closed, like membership checks.
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PROFILE_STATUS_UNAVAILABLE",
                    "Account service temporarily unavailable. Please retry.");
        }
    }

    @Override
    public void evict(UUID userId) { complete.invalidate(userId); }
}
