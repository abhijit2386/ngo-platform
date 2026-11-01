package org.ngoplatform.document.app;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.ngoplatform.commons.config.MessagingConfig;
import org.ngoplatform.commons.events.EventEnvelope;
import org.ngoplatform.commons.events.EventHandler;
import org.ngoplatform.commons.events.EventTypes;
import org.ngoplatform.commons.events.IdempotentConsumer;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/** Files added / removed in other modules appear in / disappear from the vault. */
@Component
public class EventsListener implements EventHandler {
    static final String QUEUE = "document.events";
    static final Set<String> TYPES = Set.of(EventTypes.DOCUMENT_FILE_REGISTERED, EventTypes.DOCUMENT_FILE_REMOVED);
    private final IdempotentConsumer inbox;
    private final DocumentService docs;

    public EventsListener(IdempotentConsumer inbox, DocumentService docs) { this.inbox = inbox; this.docs = docs; }

    @Bean
    static Declarables documentQueues() { return MessagingConfig.consumerQueue(QUEUE, TYPES.toArray(String[]::new)); }

    @Override
    public String consumerName() { return QUEUE; }

    @Override
    public Set<String> eventTypes() { return TYPES; }

    @RabbitListener(queues = QUEUE)
    @Override
    public void on(EventEnvelope e) {
        inbox.handle(QUEUE, e, () -> {
            switch (e.eventType()) {
                case EventTypes.DOCUMENT_FILE_REGISTERED -> docs.registerExternal(external(e.tenantId(), e.payload()));
                case EventTypes.DOCUMENT_FILE_REMOVED -> docs.removeExternal(e.str("source"), e.str("sourceRef"));
                default -> { }
            }
        });
    }

    /** Payload contract of document.file.registered (also used by the backfill lists). */
    static DocumentService.External external(UUID ngoId, java.util.Map<String, Object> p) {
        return new DocumentService.External(ngoId != null ? ngoId : uuid(p.get("ngoId")), str(p.get("source")), str(p.get("sourceRef")),
                str(p.get("storageKey")), str(p.get("title")), str(p.get("category")), str(p.get("contentType")),
                p.get("sizeBytes") instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(p.getOrDefault("sizeBytes", "0"))),
                str(p.get("originalName")), uuid(p.get("uploadedBy")), str(p.get("visibility")), str(p.get("requiredPermission")),
                str(p.get("linkType")), uuid(p.get("linkId")), uuid(p.get("linkParentId")), str(p.get("linkLabel")),
                date(p.get("documentDate")), date(p.get("expiresOn")), p.get("createdAt") == null ? null : Instant.parse(str(p.get("createdAt"))));
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
    private static UUID uuid(Object o) { return o == null || o.toString().isBlank() ? null : UUID.fromString(o.toString()); }
    private static LocalDate date(Object o) { return o == null || o.toString().isBlank() ? null : LocalDate.parse(o.toString().substring(0, 10)); }
}
