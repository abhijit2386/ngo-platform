package org.ngoplatform.commons.config;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.ngoplatform.commons.security.InternalTokenFilter;
import org.ngoplatform.commons.web.CorrelationIdFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.DeferringLoadBalancerInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Factory for service-to-service REST clients: internal token, correlation id, strict timeouts. */
@Configuration
public class InternalClients {
    private final String token;
    private final RestClient.Builder builder;
    private final DeferringLoadBalancerInterceptor loadBalancer;
    /**
     * One JDK HttpClient for the whole service: each HttpClient owns a selector thread that lives until the client
     * is garbage collected, so building one per call (the outbox relay did, once per event) leaked thousands of
     * threads (donation-payment-service reached 1,908 on 2026-11-02).
     */
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Map<String, RestClient> byBaseUrl = new ConcurrentHashMap<>();

    /**
     * When {@code ngo.discovery.enabled} (local development with Eureka), service URLs such as
     * {@code http://identity-service} are resolved through the discovery client; in Kubernetes the same URLs
     * resolve through cluster DNS and no load-balancer interceptor is applied.
     */
    public InternalClients(@Value("${ngo.internal-token}") String token, RestClient.Builder builder,
                           @Value("${ngo.discovery.enabled:false}") boolean discovery,
                           ObjectProvider<DeferringLoadBalancerInterceptor> loadBalancer) {
        this.token = token;
        this.builder = builder;
        this.loadBalancer = discovery ? loadBalancer.getIfAvailable() : null;
    }

    /** Returns the (cached) client for a base URL; safe to call per request. */
    public RestClient forBaseUrl(String baseUrl) {
        return byBaseUrl.computeIfAbsent(baseUrl, this::build);
    }

    /** Number of distinct base URLs with a client (for tests and diagnostics). */
    int cachedClients() { return byBaseUrl.size(); }

    private RestClient build(String baseUrl) {
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(http);
        rf.setReadTimeout(Duration.ofSeconds(5));
        RestClient.Builder b = builder.clone();
        if (loadBalancer != null) b.requestInterceptor(loadBalancer);
        return b
                .baseUrl(baseUrl)
                .requestFactory(rf)
                .requestInterceptor((req, body, exec) -> {
                    req.getHeaders().set(InternalTokenFilter.HEADER, token);
                    req.getHeaders().set(CorrelationIdFilter.HEADER, CorrelationIdFilter.current());
                    return exec.execute(req, body);
                })
                .build();
    }
}
