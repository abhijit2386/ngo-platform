package org.ngoplatform.commons.config;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.ngoplatform.commons.security.InternalTokenFilter;
import org.ngoplatform.commons.web.CorrelationIdFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter;

@Configuration
public class SecurityConfig {

    @Bean
    CorrelationIdFilter correlationIdFilter() { return new CorrelationIdFilter(); }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            @Value("${ngo.internal-token}") String internalToken) throws Exception {
        http.csrf(c -> c.disable()) // stateless bearer-token API
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                           .frameOptions(f -> f.deny()))
            .addFilterBefore(new InternalTokenFilter(internalToken), AbstractPreAuthenticatedProcessingFilter.class)
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/api/v1/public/**").permitAll()
                .requestMatchers("/c/**").permitAll() // public campaign share pages (campaign-service)
                .requestMatchers("/api/v1/webhooks/**").permitAll() // verified by signature in the handler
                .requestMatchers("/internal/**").hasRole("INTERNAL")
                .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtAuthConverter())))
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable());
        return http.build();
    }

    static JwtAuthenticationConverter jwtAuthConverter() {
        JwtAuthenticationConverter c = new JwtAuthenticationConverter();
        c.setJwtGrantedAuthoritiesConverter((Converter<Jwt, Collection<GrantedAuthority>>) jwt -> {
            Collection<GrantedAuthority> out = new HashSet<>();
            Object realm = jwt.getClaims().get("realm_access");
            if (realm instanceof Map<?, ?> m && m.get("roles") instanceof List<?> roles) {
                roles.forEach(r -> out.add(new SimpleGrantedAuthority("ROLE_" + r)));
            }
            return out;
        });
        return c;
    }

}
