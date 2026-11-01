package org.ngoplatform.document.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LinkSignerTest {
    static final String SECRET = "test-secret-at-least-24-characters";

    @Test
    void validForFiveMinutesAndTamperProof() {
        Instant t0 = Instant.parse("2026-11-01T10:00:00Z");
        UUID doc = UUID.randomUUID(), user = UUID.randomUUID();
        String token = new LinkSigner(SECRET, Clock.fixed(t0, ZoneOffset.UTC)).sign(doc, user);
        var g = new LinkSigner(SECRET, Clock.fixed(t0.plusSeconds(299), ZoneOffset.UTC)).verify(token);
        assertThat(g.documentId()).isEqualTo(doc);
        assertThat(g.userId()).isEqualTo(user);
        assertThat(DocumentRulesTest.code(() -> new LinkSigner(SECRET, Clock.fixed(t0.plusSeconds(301), ZoneOffset.UTC)).verify(token))).isEqualTo("LINK_EXPIRED");
        assertThat(DocumentRulesTest.code(() -> new LinkSigner("another-secret-of-24-characters!!", Clock.fixed(t0, ZoneOffset.UTC)).verify(token))).isEqualTo("LINK_INVALID");
        String other = new LinkSigner(SECRET, Clock.fixed(t0, ZoneOffset.UTC)).sign(UUID.randomUUID(), user);
        String forged = other.substring(0, other.indexOf('.')) + token.substring(token.indexOf('.'));
        assertThat(DocumentRulesTest.code(() -> new LinkSigner(SECRET, Clock.fixed(t0, ZoneOffset.UTC)).verify(forged))).isEqualTo("LINK_INVALID");
        assertThat(DocumentRulesTest.code(() -> new LinkSigner(SECRET, Clock.fixed(t0, ZoneOffset.UTC)).verify("garbage"))).isEqualTo("LINK_INVALID");
    }
}
