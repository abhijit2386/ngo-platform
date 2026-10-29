package org.ngoplatform.commons.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecord {
    @Id private UUID id;
    @Column(nullable = false) private String scope;
    @Column(name = "idem_key", nullable = false) private String key;
    @Column(name = "request_hash", nullable = false, length = 64) @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR) private String requestHash;
    @Column(name = "response_body") private String responseBody;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected IdempotencyRecord() {}

    public IdempotencyRecord(String scope, String key, String requestHash, String responseBody) {
        this.id = UUID.randomUUID(); this.scope = scope; this.key = key; this.requestHash = requestHash;
        this.responseBody = responseBody; this.createdAt = Instant.now();
    }

    public String getRequestHash() { return requestHash; }
    public String getResponseBody() { return responseBody; }
}
