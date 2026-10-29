package org.ngoplatform.notification.app;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.ngoplatform.commons.config.InternalClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Looks up recipients and display data from the owning services (events carry ids, not personal data).
 * Failures propagate so the event is redelivered later rather than silently dropped.
 */
@Component
public class Directory {
    public record Person(UUID userId, String email, String name, String language) {}

    private final RestClient identity;
    private final RestClient registry;

    public Directory(InternalClients clients, @Value("${ngo.services.identity}") String identityUrl,
                     @Value("${ngo.services.ngo-registry}") String registryUrl) {
        this.identity = clients.forBaseUrl(identityUrl);
        this.registry = clients.forBaseUrl(registryUrl);
    }

    /** Active primary and secondary administrators of an NGO. */
    public List<Person> admins(UUID ngoId) {
        List<Map<String, String>> rows = identity.get().uri("/internal/v1/tenants/{ngoId}/admins", ngoId).retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return rows == null ? List.of() : rows.stream().map(Directory::person).toList();
    }

    public Optional<Person> user(UUID userId) {
        try {
            Map<String, String> m = identity.get().uri("/internal/v1/users/{id}/contact", userId).retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (m == null) return Optional.empty();
            return Optional.of(new Person(userId, m.get("email"), m.get("name"), m.get("language")));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    /** Registered user with this e-mail, if any (invitations can go to people without an account yet). */
    public Optional<Person> userByEmail(String email) {
        try {
            Map<String, String> m = identity.get().uri(b -> b.path("/internal/v1/users/lookup").queryParam("email", email).build())
                    .retrieve().body(new ParameterizedTypeReference<>() {});
            return m == null ? Optional.empty() : Optional.of(person(m));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    public String ngoName(UUID ngoId) {
        Map<String, Object> m = registry.get().uri("/internal/v1/ngos/{id}", ngoId).retrieve()
                .body(new ParameterizedTypeReference<>() {});
        Object name = m == null ? null : m.get("displayName");
        return name == null ? "" : name.toString();
    }

    private static Person person(Map<String, String> m) {
        return new Person(m.get("userId") == null ? null : UUID.fromString(m.get("userId")), m.get("email"),
                m.get("name"), m.get("language"));
    }
}
