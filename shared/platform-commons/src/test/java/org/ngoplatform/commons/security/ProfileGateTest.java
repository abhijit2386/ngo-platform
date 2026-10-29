package org.ngoplatform.commons.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.ngoplatform.commons.error.ApiException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class ProfileGateTest {
    private final ProfileStatusResolver resolver = mock(ProfileStatusResolver.class);
    private final ProfileGate gate = new ProfileGate(resolver);
    private final UUID user = UUID.randomUUID();

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private void signIn(String... roles) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(user.toString())
                .claim("realm_access", Map.of("roles", List.of(roles))).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private boolean call(String path) {
        return gate.preHandle(new MockHttpServletRequest("GET", path), new MockHttpServletResponse(), new Object());
    }

    @Test
    void profileEndpointsAreOpen() {
        assertThat(ProfileGate.isOpen("/api/v1/me")).isTrue();
        assertThat(ProfileGate.isOpen("/api/v1/me/profile")).isTrue();
        assertThat(ProfileGate.isOpen("/api/v1/me/photo")).isTrue();
        assertThat(ProfileGate.isOpen("/api/v1/me/avatar")).isTrue();
        assertThat(ProfileGate.isOpen("/api/v1/me/deletion-request")).isTrue();
        assertThat(ProfileGate.isOpen("/api/v1/public/ngos")).isTrue();
        assertThat(ProfileGate.isOpen("/api/v1/me/phone")).isTrue();
        assertThat(ProfileGate.isOpen("/api/v1/me/phone/otp")).isTrue();
        assertThat(ProfileGate.isOpen("/api/v1/me/phone/verify")).isTrue();
    }

    @Test
    void everythingElseIsGated() {
        assertThat(ProfileGate.isOpen("/api/v1/me/invitations")).isFalse();
        assertThat(ProfileGate.isOpen("/api/v1/me/invitations/x/accept")).isFalse();
        assertThat(ProfileGate.isOpen("/api/v1/notifications")).isFalse();
        assertThat(ProfileGate.isOpen("/api/v1/ngos/x/staff")).isFalse();
        assertThat(ProfileGate.isOpen("/api/v1/me/profile/extra")).isFalse();
        assertThat(ProfileGate.isOpen("/api/v1/mexico")).isFalse();
    }

    @Test
    void nonApiPathsAreNotThisGatesBusiness() {
        assertThat(ProfileGate.isOpen("/actuator/health")).isTrue();
        assertThat(ProfileGate.isOpen("/internal/v1/events")).isTrue();
    }

    @Test
    void incompleteProfileIsRejected() {
        signIn("DONOR");
        when(resolver.isComplete(user)).thenReturn(false);
        assertThatThrownBy(() -> call("/api/v1/notifications")).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo("PROFILE_INCOMPLETE");
    }

    @Test
    void completeProfilePasses() {
        signIn("DONOR");
        when(resolver.isComplete(user)).thenReturn(true);
        assertThat(call("/api/v1/notifications")).isTrue();
    }

    @Test
    void openPathDoesNotConsultResolver() {
        signIn("DONOR");
        assertThat(call("/api/v1/me/profile")).isTrue();
        verifyNoInteractions(resolver);
    }

    @Test
    void platformStaffAreExempt() {
        signIn("PLATFORM_ADMIN");
        assertThat(call("/api/v1/admin/verifications")).isTrue();
        verifyNoInteractions(resolver);
    }

    @Test
    void anonymousRequestsAreLeftToSecurityRules() {
        assertThat(call("/api/v1/notifications")).isTrue();
        verifyNoInteractions(resolver);
    }
}
