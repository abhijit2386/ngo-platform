package org.ngoplatform.commons.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Service-to-service calls to /internal/** carry X-Internal-Token (a shared secret from a secret file / K8s Secret).
 * The gateway never routes /internal/**. In clusters, add NetworkPolicies/mTLS on top.
 */
public class InternalTokenFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Internal-Token";
    private final byte[] expected;

    public InternalTokenFilter(String secret) {
        this.expected = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String provided = req.getHeader(HEADER);
        if (provided == null || expected.length < 16
                || !MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.UTF_8))) {
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        var auth = new UsernamePasswordAuthenticationToken("internal-service", null,
                List.of(new SimpleGrantedAuthority("ROLE_INTERNAL")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        chain.doFilter(req, res);
    }
}
