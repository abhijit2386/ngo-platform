package org.ngoplatform.commons.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Event routing for the HTTP transport: {@code ngo.events.routes} maps an event type to subscribing services
 * (keys of {@code ngo.services}). With RabbitMQ the same routing is expressed by queue bindings, so this table is
 * only read in local development.
 */
@Component
@ConfigurationProperties(prefix = "ngo")
public class EventRoutes {
    private Map<String, String> services = new LinkedHashMap<>();
    private final Events events = new Events();

    public Map<String, String> getServices() { return services; }
    public void setServices(Map<String, String> services) { this.services = services; }
    public Events getEvents() { return events; }

    public static class Events {
        private Map<String, List<String>> routes = new LinkedHashMap<>();
        public Map<String, List<String>> getRoutes() { return routes; }
        public void setRoutes(Map<String, List<String>> routes) { this.routes = routes; }
    }

    /** Base URLs of the services subscribed to an event type (empty when nobody listens). */
    public List<String> subscribersOf(String eventType) {
        List<String> out = new ArrayList<>();
        for (String svc : events.getRoutes().getOrDefault(eventType, List.of())) {
            String url = services.get(svc);
            if (url == null) throw new IllegalStateException("No URL for service '" + svc + "' (event " + eventType + ")");
            out.add(url);
        }
        return out;
    }
}
