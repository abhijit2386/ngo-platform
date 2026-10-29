package org.ngoplatform.commons.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.ngoplatform.commons.error.ApiException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotency-Key handling for financial mutations (spec §24). Same key + same request => same stored response;
 * same key + different request => 422. The key row commits in the caller's transaction, so a crash leaves no key.
 */
@Component
public class Idempotency {
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9\\-_]{8,100}$");
    private final IdempotencyRepository repo;
    private final ObjectMapper mapper;

    public Idempotency(IdempotencyRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T execute(String scope, String key, Object request, Class<T> type, Supplier<T> action) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_INVALID", "Idempotency-Key header is required (8-100 chars)");
        }
        String hash = hash(request);
        Optional<IdempotencyRecord> existing = repo.findByScopeAndKey(scope, key);
        if (existing.isPresent()) {
            if (!existing.get().getRequestHash().equals(hash)) {
                throw ApiException.rule("IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was used for a different request");
            }
            try {
                return mapper.readValue(existing.get().getResponseBody(), type);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Stored idempotent response unreadable", e);
            }
        }
        T result = action.get();
        try {
            repo.saveAndFlush(new IdempotencyRecord(scope, key, hash, mapper.writeValueAsString(result)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return result;
    }

    private String hash(Object request) {
        try {
            byte[] json = mapper.writeValueAsBytes(request);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
