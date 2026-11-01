package org.ngoplatform.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Path ownership per service. Order matters: specific tenant sub-resources first, registry catch-all last.
 * The gateway contains no business logic.
 */
@Configuration
public class Routes {

    @Bean
    RouteLocator routeLocator(RouteLocatorBuilder b,
                        @Value("${ngo.services.identity}") String identity,
                        @Value("${ngo.services.ngo-registry}") String registry,
                        @Value("${ngo.services.subscription}") String subscription,
                        @Value("${ngo.services.donor}") String donor,
                        @Value("${ngo.services.donation}") String donation,
                        @Value("${ngo.services.receipt}") String receipt,
                        @Value("${ngo.services.campaign}") String campaign,
                        @Value("${ngo.services.operations}") String operations,
                        @Value("${ngo.services.helpdesk}") String helpdesk,
                        @Value("${ngo.services.notification}") String notification,
                        @Value("${ngo.services.reporting}") String reporting,
                        @Value("${ngo.services.document}") String document) {
        return b.routes()
                .route("identity", r -> r.order(1).path("/api/v1/me", "/api/v1/me/**", "/api/v1/users/*/photo", "/api/v1/ngos/*/staff/**",
                        "/api/v1/ngos/*/staff").uri(identity))
                .route("subscription", r -> r.order(1).path("/api/v1/ngos/*/subscription", "/api/v1/ngos/*/subscription/**",
                        "/api/v1/public/plans", "/api/v1/public/plans/**", "/api/v1/admin/subscriptions/**",
                        "/api/v1/admin/plans/**", "/api/v1/webhooks/billing/**").uri(subscription))
                .route("donor", r -> r.order(1).path("/api/v1/ngos/*/donors", "/api/v1/ngos/*/donors/**",
                        "/api/v1/donor/**").uri(donor))
                .route("donation", r -> r.order(1).path("/api/v1/ngos/*/donations", "/api/v1/ngos/*/donations/**",
                        "/api/v1/ngos/*/reconciliation/**", "/api/v1/ngos/*/payment-settings", "/api/v1/ngos/*/ledger/**",
                        "/api/v1/ngos/*/refunds/**", "/api/v1/donations/**", "/api/v1/public/donations/**",
                        "/api/v1/webhooks/payments/**", "/api/v1/admin/payments/**", "/api/v1/dev/mock-payments/**")
                        .uri(donation))
                .route("receipt", r -> r.order(1).path("/api/v1/ngos/*/receipts", "/api/v1/ngos/*/receipts/**",
                        "/api/v1/ngos/*/receipt-templates", "/api/v1/ngos/*/receipt-templates/**", "/api/v1/receipts/**",
                        "/api/v1/public/receipts/**").uri(receipt))
                .route("campaign", r -> r.order(1).path("/api/v1/ngos/*/campaigns", "/api/v1/ngos/*/campaigns/**",
                        "/api/v1/ngos/*/projects", "/api/v1/ngos/*/projects/**", "/api/v1/public/campaigns/**",
                        "/c/*", "/api/v1/ngos/*/expenses/**", "/api/v1/ngos/*/vendors", "/api/v1/ngos/*/vendors/**",
                        "/api/v1/ngos/*/staff-money", "/api/v1/ngos/*/staff-money/**")
                        .uri(campaign))
                .route("document", r -> r.order(1).path("/api/v1/ngos/*/library", "/api/v1/ngos/*/library/**",
                        "/api/v1/ngos/*/meetings", "/api/v1/ngos/*/meetings/**", "/api/v1/ngos/*/action-items", "/api/v1/ngos/*/action-items/**",
                        "/api/v1/public/files/**").uri(document))
                .route("operations", r -> r.order(1).path("/api/v1/ngos/*/volunteers/**", "/api/v1/ngos/*/volunteers",
                        "/api/v1/ngos/*/beneficiaries/**", "/api/v1/ngos/*/beneficiaries", "/api/v1/ngos/*/finance/**",
                        "/api/v1/ngos/*/activities/**", "/api/v1/me/volunteering/**").uri(operations))
                .route("helpdesk", r -> r.order(1).path("/api/v1/ngos/*/helpdesk/**", "/api/v1/support/**",
                        "/api/v1/admin/support/**", "/api/v1/public/kb/**").uri(helpdesk))
                .route("notification", r -> r.order(1).path("/api/v1/notifications/**", "/api/v1/notifications",
                        "/api/v1/admin/notification-templates/**").uri(notification))
                .route("reporting", r -> r.order(1).path("/api/v1/ngos/*/reports/**", "/api/v1/ngos/*/dashboard",
                        "/api/v1/admin/dashboard/**").uri(reporting))
                .route("registry", r -> r.order(100).path("/api/v1/ngos", "/api/v1/ngos/**", "/api/v1/public/ngos/**",
                        "/api/v1/public/ngos", "/api/v1/admin/ngos/**", "/api/v1/admin/verifications/**",
                        "/api/v1/public/app-config", "/api/v1/public/reference").uri(registry))
                .build();
    }
}
