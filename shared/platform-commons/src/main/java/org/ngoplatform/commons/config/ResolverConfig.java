package org.ngoplatform.commons.config;

import org.ngoplatform.commons.security.EntitlementResolver;
import org.ngoplatform.commons.security.MembershipResolver;
import org.ngoplatform.commons.security.ProfileStatusResolver;
import org.ngoplatform.commons.security.RestProfileStatusResolver;
import org.ngoplatform.commons.security.RestEntitlementResolver;
import org.ngoplatform.commons.security.RestMembershipResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * Remote resolvers by default; identity-service / subscription-service provide local beans instead.
 * Registered as auto-configuration so @ConditionalOnMissingBean is evaluated after application beans.
 */
@AutoConfiguration
public class ResolverConfig {
    @Bean
    @ConditionalOnMissingBean(MembershipResolver.class)
    MembershipResolver membershipResolver(InternalClients clients, @Value("${ngo.services.identity}") String url) {
        return new RestMembershipResolver(clients, url);
    }

    @Bean
    @ConditionalOnMissingBean(ProfileStatusResolver.class)
    ProfileStatusResolver profileStatusResolver(InternalClients clients, @Value("${ngo.services.identity}") String url) {
        return new RestProfileStatusResolver(clients, url);
    }

    @Bean
    @ConditionalOnMissingBean(EntitlementResolver.class)
    EntitlementResolver entitlementResolver(InternalClients clients, @Value("${ngo.services.subscription}") String url) {
        return new RestEntitlementResolver(clients, url);
    }

    /**
     * JWKS is fetched over the internal network while the accepted issuer is the public URL the app used,
     * so tokens obtained via http://10.0.2.2:8180 (emulator) or https://id.example.org validate inside the cluster.
     */
    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    JwtDecoder jwtDecoder(@Value("${ngo.security.jwk-set-uri}") String jwkSetUri,
                          @Value("${ngo.security.issuers}") List<String> issuers,
                          @Value("${ngo.security.allowed-clients:ngo-mobile}") List<String> clients) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> issuer = jwt -> issuers.contains(String.valueOf(jwt.getIssuer()))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Untrusted issuer", null));
        OAuth2TokenValidator<Jwt> client = jwt -> {
            String azp = jwt.getClaimAsString("azp");
            return azp != null && clients.contains(azp) ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Unknown client", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(), issuer, client));
        return decoder;
    }
}
