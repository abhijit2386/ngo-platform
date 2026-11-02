package org.ngoplatform.commons.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClient;

/** Guards against the 2026-11-02 thread leak: one HttpClient (one selector thread) per call to forBaseUrl. */
class InternalClientsTest {

    @SuppressWarnings("unchecked")
    private final InternalClients clients = new InternalClients("t", RestClient.builder(), false,
            mock(ObjectProvider.class));

    @Test
    void sameBaseUrlReturnsTheSameClient() {
        RestClient a = clients.forBaseUrl("http://donor-service");
        RestClient b = clients.forBaseUrl("http://donor-service");
        assertThat(b).isSameAs(a);
        assertThat(clients.cachedClients()).isEqualTo(1);
    }

    @Test
    void repeatedCallsDoNotStartSelectorThreads() {
        clients.forBaseUrl("http://warm-up");
        long before = selectorThreads();
        for (int i = 0; i < 200; i++) {
            clients.forBaseUrl("http://identity-service");
            clients.forBaseUrl("http://ngo-registry-service");
        }
        assertThat(clients.cachedClients()).isEqualTo(3);
        assertThat(selectorThreads()).isLessThanOrEqualTo(before);
    }

    private static long selectorThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().contains("SelectorManager")).count();
    }

}
