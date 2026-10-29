package org.ngoplatform.commons.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.ngoplatform.commons.config.InternalClients;
import org.ngoplatform.commons.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Resolves memberships from identity-service with a short cache (permission changes propagate within ~30s or on event). */
public class RestMembershipResolver implements MembershipResolver {
    private final RestClient client;
    private final Cache<String, Optional<Membership>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30)).maximumSize(50_000).build();

    public RestMembershipResolver(InternalClients clients, String identityBaseUrl) {
        this.client = clients.forBaseUrl(identityBaseUrl);
    }

    @Override
    public Optional<Membership> find(UUID userId, UUID ngoId) {
        return cache.get(userId + ":" + ngoId, k -> fetch(userId, ngoId));
    }

    private Optional<Membership> fetch(UUID userId, UUID ngoId) {
        try {
            return Optional.ofNullable(client.get()
                    .uri("/internal/v1/memberships/{ngoId}/{userId}", ngoId, userId)
                    .retrieve().body(Membership.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            // Fail closed: authorization data unavailable -> deny with retryable status, never allow.
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AUTHZ_UNAVAILABLE",
                    "Authorization service temporarily unavailable. Please retry.");
        }
    }

    @Override
    public void evict(UUID userId, UUID ngoId) { cache.invalidate(userId + ":" + ngoId); }
}
