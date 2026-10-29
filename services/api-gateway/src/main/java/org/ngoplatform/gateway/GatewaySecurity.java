package org.ngoplatform.gateway;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

/** Defense in depth: the edge rejects invalid tokens early; every service validates again. */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurity {

    @Bean
    SecurityWebFilterChain chain(ServerHttpSecurity http) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(a -> a
                        .pathMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .pathMatchers("/api/v1/public/**", "/api/v1/webhooks/**", "/c/**").permitAll()
                        .pathMatchers("/api/v1/**").authenticated()
                        .anyExchange().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> {}))
                .build();
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(@Value("${ngo.security.jwk-set-uri}") String jwks,
                                  @Value("${ngo.security.issuers}") List<String> issuers) {
        NimbusReactiveJwtDecoder d = NimbusReactiveJwtDecoder.withJwkSetUri(jwks).build();
        OAuth2TokenValidator<Jwt> issuer = jwt -> issuers.contains(String.valueOf(jwt.getIssuer()))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Untrusted issuer", null));
        d.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(), issuer));
        return d;
    }
}
