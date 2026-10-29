package org.ngoplatform.commons.config;

import java.net.http.HttpClient;
import java.time.Duration;
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

    public RestClient forBaseUrl(String baseUrl) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
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
