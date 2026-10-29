package org.ngoplatform.identity.app;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.ngoplatform.commons.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Keycloak Admin REST API with a confidential service client (client-credentials, realm-management roles
 * manage-users/view-users only). Used to delete under-age sign-ups and, later, deleted accounts.
 */
@Component
public class KeycloakAdmin {
    private static final Logger log = LoggerFactory.getLogger(KeycloakAdmin.class);

    private final RestClient http;
    private final String realm;
    private final String clientId;
    private final String clientSecret;
    private String token;
    private Instant tokenExpires = Instant.EPOCH;

    public KeycloakAdmin(@Value("${ngo.keycloak.base-url}") String baseUrl, @Value("${ngo.keycloak.realm}") String realm,
                         @Value("${ngo.keycloak.admin-client-id}") String clientId,
                         @Value("${ngo.keycloak.admin-client-secret}") String clientSecret) {
        java.net.http.HttpClient jdk = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(jdk);
        rf.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(rf).build();
        this.realm = realm; this.clientId = clientId; this.clientSecret = clientSecret;
    }

    /** Deletes the user (and with it any linked Google/Facebook/Apple identities). Missing users count as deleted. */
    public void deleteUser(UUID userId) {
        try {
            http.delete().uri("/admin/realms/{realm}/users/{id}", realm, userId)
                    .header("Authorization", "Bearer " + token()).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.NotFound e) {
            log.info("keycloak user already absent");
        } catch (RestClientException e) {
            throw unavailable(e);
        }
    }

    /** Blocks sign-in without deleting (used when an account cannot be removed automatically). */
    public void disableUser(UUID userId) {
        try {
            http.put().uri("/admin/realms/{realm}/users/{id}", realm, userId)
                    .header("Authorization", "Bearer " + token()).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("enabled", false)).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.NotFound e) {
            log.info("keycloak user already absent");
        } catch (RestClientException e) {
            throw unavailable(e);
        }
    }

    private synchronized String token() {
        if (token != null && Instant.now().isBefore(tokenExpires)) return token;
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        try {
            Map<String, Object> r = http.post().uri("/realms/{realm}/protocol/openid-connect/token", realm)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (r == null || r.get("access_token") == null) throw new IllegalStateException("no access_token");
            token = r.get("access_token").toString();
            long ttl = r.get("expires_in") instanceof Number n ? n.longValue() : 60;
            tokenExpires = Instant.now().plusSeconds(Math.max(10, ttl - 30));
            return token;
        } catch (RestClientException | IllegalStateException e) {
            throw unavailable(e);
        }
    }

    private static ApiException unavailable(Exception e) {
        log.warn("keycloak admin call failed: {}", e.toString());
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ACCOUNT_SERVICE_UNAVAILABLE",
                "We could not update your account right now. Please try again in a minute.");
    }
}
