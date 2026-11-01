package org.ngoplatform.gateway;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Edge concerns: correlation id, internal-path blocking, request size limit, per-client rate limit, security headers.
 * Rate limiting is per gateway replica (fixed 1-minute window); put a shared limiter at the ingress for multi-replica.
 */
@Component
public class EdgeFilters implements GlobalFilter, Ordered {
    static final String CORRELATION = "X-Correlation-Id";
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9\\-]{8,64}$");

    private final long maxBodyBytes;
    private final int perMinute;
    private final int perMinuteSensitive;
    private final Cache<String, AtomicLong> windows = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1)).maximumSize(200_000).build();

    public EdgeFilters(@Value("${ngo.gateway.max-body-bytes:12582912}") long maxBodyBytes,
                       @Value("${ngo.gateway.rate-per-minute:300}") int perMinute,
                       @Value("${ngo.gateway.rate-per-minute-sensitive:30}") int perMinuteSensitive) {
        this.maxBodyBytes = maxBodyBytes;
        this.perMinute = perMinute;
        this.perMinuteSensitive = perMinuteSensitive;
    }

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 10; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest req = exchange.getRequest();
        String path = req.getPath().value();
        if (path.startsWith("/internal") || path.contains("/internal/")) {
            return reject(exchange, HttpStatus.NOT_FOUND);
        }
        long len = req.getHeaders().getContentLength();
        if (len > maxBodyBytes) {
            return reject(exchange, HttpStatus.PAYLOAD_TOO_LARGE);
        }
        String client = clientKey(req);
        boolean sensitive = path.startsWith("/api/v1/public/donations") || path.startsWith("/api/v1/public/receipts")
                || path.startsWith("/api/v1/public/csr")
                || path.contains("/checkout") || path.startsWith("/api/v1/webhooks");
        int limit = sensitive ? perMinuteSensitive : perMinute;
        long count = windows.get((sensitive ? "s:" : "g:") + client, k -> new AtomicLong()).incrementAndGet();
        if (count > limit) {
            exchange.getResponse().getHeaders().set("Retry-After", "60");
            return reject(exchange, HttpStatus.TOO_MANY_REQUESTS);
        }
        String cid = req.getHeaders().getFirst(CORRELATION);
        if (cid == null || !SAFE_ID.matcher(cid).matches()) cid = UUID.randomUUID().toString();
        final String correlationId = cid;
        ServerHttpRequest mutated = req.mutate().headers(h -> {
            h.set(CORRELATION, correlationId);
            h.remove("X-Internal-Token"); // never let clients forge service credentials
        }).build();
        HttpHeaders out = exchange.getResponse().getHeaders();
        out.set(CORRELATION, correlationId);
        out.set("X-Content-Type-Options", "nosniff");
        out.set("X-Frame-Options", "DENY");
        out.set("Referrer-Policy", "no-referrer");
        out.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        out.set("Cache-Control", "no-store");
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    private static String clientKey(ServerHttpRequest req) {
        String auth = req.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.length() > 40) return "t:" + Integer.toHexString(auth.hashCode());
        String fwd = req.getHeaders().getFirst("X-Forwarded-For");
        if (fwd != null) return "ip:" + fwd.split(",")[0].trim();
        InetSocketAddress remote = req.getRemoteAddress();
        return "ip:" + (remote == null ? "unknown" : remote.getAddress().getHostAddress());
    }

    private static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }
}
