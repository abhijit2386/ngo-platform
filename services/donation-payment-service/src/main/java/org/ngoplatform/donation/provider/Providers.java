package org.ngoplatform.donation.provider;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.ngoplatform.commons.error.ApiException;
import org.springframework.stereotype.Component;

/** Registry of configured provider adapters. Real gateways are added as further PaymentProvider beans. */
@Component
public class Providers {
    private final Map<String, PaymentProvider> byName;

    public Providers(List<PaymentProvider> providers) {
        this.byName = providers.stream().collect(Collectors.toMap(PaymentProvider::name, Function.identity()));
    }

    public PaymentProvider get(String name) {
        PaymentProvider p = byName.get(name);
        if (p == null) throw ApiException.rule("PROVIDER_NOT_AVAILABLE", "Payment provider " + name + " is not configured on this platform");
        return p;
    }

    public java.util.Set<String> available() { return byName.keySet(); }
}
