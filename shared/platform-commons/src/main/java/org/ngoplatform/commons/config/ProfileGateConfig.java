package org.ngoplatform.commons.config;

import org.ngoplatform.commons.security.ProfileGate;
import org.ngoplatform.commons.security.ProfileStatusResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the adults-only profile gate on every servlet service (the reactive gateway is not affected). */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "ngo.profile-gate.enabled", havingValue = "true", matchIfMissing = true)
public class ProfileGateConfig implements WebMvcConfigurer {
    private final ProfileStatusResolver resolver;

    public ProfileGateConfig(ProfileStatusResolver resolver) { this.resolver = resolver; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ProfileGate(resolver)).addPathPatterns("/api/**");
    }
}
