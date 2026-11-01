package org.ngoplatform.notification.app;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.ngoplatform.commons.config.MessagingConfig;
import org.ngoplatform.commons.events.EventEnvelope;
import org.ngoplatform.commons.events.EventHandler;
import org.ngoplatform.commons.events.EventTypes;
import org.ngoplatform.commons.events.IdempotentConsumer;
import org.ngoplatform.notification.app.NotificationPlanner.Recipient;
import org.ngoplatform.notification.app.NotificationPlanner.Request;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/** Maps business events to notifications (spec §20 "notification events"). */
@Component
public class EventsListener implements EventHandler {
    static final String QUEUE = "notification.events";
    static final Set<String> TYPES = Set.of(EventTypes.NGO_VERIFIED, EventTypes.NGO_REJECTED, EventTypes.NGO_INFO_REQUESTED,
            EventTypes.NGO_SUSPENDED, EventTypes.STAFF_INVITED, EventTypes.STAFF_ACCEPTED,
            EventTypes.SUBSCRIPTION_EXPIRING, EventTypes.SUBSCRIPTION_PAYMENT_FAILED,
            EventTypes.CAMPAIGN_SUBMITTED, EventTypes.CAMPAIGN_PUBLISHED, EventTypes.CAMPAIGN_REJECTED,
            EventTypes.DONATION_PAID, EventTypes.CAMPAIGN_EXPENSE_SUBMITTED, EventTypes.CAMPAIGN_EXPENSE_APPROVED,
            EventTypes.CAMPAIGN_EXPENSE_REJECTED);

    private final IdempotentConsumer inbox;
    private final NotificationPlanner planner;
    private final Directory directory;

    public EventsListener(IdempotentConsumer inbox, NotificationPlanner planner, Directory directory) {
        this.inbox = inbox; this.planner = planner; this.directory = directory;
    }

    @Bean
    static Declarables notificationQueues() {
        return MessagingConfig.consumerQueue(QUEUE, TYPES.toArray(String[]::new));
    }

    @Override
    public String consumerName() { return QUEUE; }

    @Override
    public Set<String> eventTypes() { return TYPES; }

    @RabbitListener(queues = QUEUE)
    @Override
    public void on(EventEnvelope e) {
        inbox.handle(QUEUE, e, () -> {
            Request r = toRequest(e);
            if (r != null) planner.plan(r);
        });
    }

    Request toRequest(EventEnvelope e) {
        UUID ngoId = e.tenantId();
        Map<String, Object> vars = new HashMap<>();
        return switch (e.eventType()) {
            case EventTypes.NGO_VERIFIED -> {
                vars.put("ngoName", name(e));
                vars.put("message", nz(e.str("message")));
                yield toAdmins("NGO_APPROVED", ngoId, vars, "/ngo/home", e);
            }
            case EventTypes.NGO_REJECTED -> {
                vars.put("ngoName", name(e));
                vars.put("reason", nz(e.str("reason")));
                yield toAdmins("NGO_REJECTED", ngoId, vars, "/ngo/register/" + ngoId, e);
            }
            case EventTypes.NGO_INFO_REQUESTED -> {
                vars.put("ngoName", name(e));
                vars.put("message", nz(e.str("message")));
                yield toAdmins("NGO_INFO_REQUESTED", ngoId, vars, "/ngo/register/" + ngoId, e);
            }
            case EventTypes.NGO_SUSPENDED -> {
                vars.put("ngoName", name(e));
                yield toAdmins("NGO_SUSPENDED", ngoId, vars, "/ngo/home", e);
            }
            case EventTypes.STAFF_INVITED -> {
                String email = e.str("email");
                vars.put("ngoName", name(e));
                vars.put("roleCode", e.str("role"));
                vars.put("expiresAt", e.str("expiresAt"));
                Recipient to = directory.userByEmail(email).map(Recipient::of)
                        .map(p -> new Recipient(p.userId(), email, p.name(), p.language()))
                        .orElse(new Recipient(null, email, null, "en"));
                yield new Request("STAFF_INVITED", ngoId, List.of(to), vars, "/ngo/start", "INVITATION", e.resourceId(),
                        e.eventId(), true, true);
            }
            case EventTypes.STAFF_ACCEPTED -> {
                UUID member = e.uuid("userId");
                vars.put("ngoName", name(e));
                vars.put("roleCode", e.str("role"));
                vars.put("memberName", directory.user(member).map(p -> p.name() == null || p.name().isBlank() ? p.email() : p.name()).orElse(""));
                List<Recipient> admins = directory.admins(ngoId).stream().filter(p -> !member.equals(p.userId()))
                        .map(Recipient::of).toList();
                yield new Request("STAFF_JOINED", ngoId, admins, vars, "/ngo/staff", "MEMBERSHIP", e.resourceId(),
                        e.eventId(), true, true);
            }
            case EventTypes.SUBSCRIPTION_EXPIRING -> {
                String template = switch (nz(e.str("kind"))) {
                    case "D15", "D7", "D1" -> "SUBSCRIPTION_RENEWAL_DUE";
                    case "EXPIRED", "GRACE_ENDING" -> "SUBSCRIPTION_EXPIRED";
                    case "SUSPENDED" -> "SUBSCRIPTION_SUSPENDED";
                    default -> null;
                };
                if (template == null) yield null;
                vars.put("ngoName", name(e));
                vars.put("planCode", e.str("plan"));
                vars.put("daysLeft", e.str("daysLeft"));
                vars.put("periodEndAt", e.str("periodEnd"));
                yield toAdmins(template, ngoId, vars, "/ngo/subscription", e);
            }
            case EventTypes.SUBSCRIPTION_PAYMENT_FAILED -> {
                vars.put("ngoName", name(e));
                vars.put("amount", e.str("amount"));
                yield toAdmins("SUBSCRIPTION_PAYMENT_FAILED", ngoId, vars, "/ngo/subscription", e);
            }
            case EventTypes.CAMPAIGN_SUBMITTED -> {
                UUID by = e.uuid("submittedBy");
                vars.put("campaignTitle", nz(e.str("title")));
                vars.put("memberName", by == null ? "" : directory.user(by).map(EventsListener::displayName).orElse(""));
                List<Recipient> admins = directory.admins(ngoId).stream().filter(p -> by == null || !by.equals(p.userId())).map(Recipient::of).toList();
                yield new Request("CAMPAIGN_SUBMITTED", ngoId, admins, vars, "/ngo/campaigns/" + e.resourceId(), "CAMPAIGN",
                        e.resourceId(), e.eventId(), true, true);
            }
            case EventTypes.CAMPAIGN_PUBLISHED -> {
                UUID requester = e.uuid("requestedBy");
                if (requester == null || requester.equals(e.uuid("approvedBy"))) yield null; // admin published own draft
                vars.put("campaignTitle", nz(e.str("title")));
                yield toUser("CAMPAIGN_APPROVED", ngoId, requester, vars, e);
            }
            case EventTypes.CAMPAIGN_REJECTED -> {
                vars.put("campaignTitle", nz(e.str("title")));
                vars.put("reason", nz(e.str("reason")));
                yield toUser("CAMPAIGN_REJECTED", ngoId, e.uuid("requestedBy"), vars, e);
            }
            case EventTypes.DONATION_PAID -> donationPaid(e, ngoId, vars);
            case EventTypes.CAMPAIGN_EXPENSE_SUBMITTED -> {
                UUID by = e.uuid("submittedBy");
                expenseVars(e, vars);
                vars.put("memberName", by == null ? "" : directory.user(by).map(EventsListener::displayName).orElse(""));
                List<Recipient> admins = directory.admins(ngoId).stream().filter(p -> by == null || !by.equals(p.userId())).map(Recipient::of).toList();
                yield new Request("EXPENSE_SUBMITTED", ngoId, admins, vars, expenseLink(e), "EXPENSE", e.resourceId(), e.eventId(), true, true);
            }
            case EventTypes.CAMPAIGN_EXPENSE_APPROVED -> {
                UUID by = e.uuid("submittedBy");
                if (by == null || by.equals(e.uuid("decidedBy"))) yield null; // self-approved (single administrator)
                expenseVars(e, vars);
                vars.put("voucherNo", nz(e.str("voucherNo")));
                yield expenseToUser("EXPENSE_APPROVED", ngoId, by, vars, e, false);
            }
            case EventTypes.CAMPAIGN_EXPENSE_REJECTED -> {
                expenseVars(e, vars);
                vars.put("reason", nz(e.str("reason")));
                yield expenseToUser("EXPENSE_REJECTED", ngoId, e.uuid("submittedBy"), vars, e, true);
            }
            default -> null;
        };
    }

    /**
     * Thank-you to a signed-in donor (e-mail + in-app) and a "new donation" note to the NGO's administrators (in-app
     * only, no donor name). Offline donations recorded by staff thank nobody here (receipts cover them).
     */
    Request donationPaid(EventEnvelope e, UUID ngoId, Map<String, Object> vars) {
        String ngoName = e.str("ngoName") != null ? e.str("ngoName") : directory.ngoName(ngoId);
        String campaign = e.str("campaignTitle");
        vars.put("amount", Labels.inr(e.str("amount")));
        vars.put("ngoName", ngoName);
        vars.put("campaignTitle", campaign == null ? ngoName : campaign);
        UUID donor = e.uuid("donorUserId");
        if ("ONLINE".equals(e.str("channel")) && donor != null) {
            List<Recipient> to = directory.user(donor).map(Recipient::of).stream().toList();
            planner.plan(new Request("DONATION_THANKS", ngoId, to, new HashMap<>(vars), "/donor/donations", "DONATION",
                    e.resourceId(), e.eventId(), true, true));
        }
        List<Recipient> admins = directory.admins(ngoId).stream().map(Recipient::of).toList();
        String link = e.str("campaignId") == null ? "/ngo/home" : "/ngo/campaigns/" + e.str("campaignId");
        return new Request("DONATION_RECEIVED", ngoId, admins, vars, link, "DONATION", e.resourceId(), e.eventId(), true, false);
    }

    private static void expenseVars(EventEnvelope e, Map<String, Object> vars) {
        vars.put("campaignTitle", nz(e.str("campaignTitle")));
        vars.put("amount", Labels.inr(e.str("amount")));
        vars.put("description", nz(e.str("description")));
    }

    private static String expenseLink(EventEnvelope e) {
        return "/ngo/campaigns/" + e.str("campaignId") + "/expenses/" + e.resourceId();
    }

    private Request expenseToUser(String template, UUID ngoId, UUID userId, Map<String, Object> vars, EventEnvelope e, boolean email) {
        if (userId == null) return null;
        List<Recipient> to = directory.user(userId).map(Recipient::of).stream().toList();
        return new Request(template, ngoId, to, vars, expenseLink(e), "EXPENSE", e.resourceId(), e.eventId(), true, email);
    }

    private Request toUser(String template, UUID ngoId, UUID userId, Map<String, Object> vars, EventEnvelope e) {
        if (userId == null) return null;
        List<Recipient> to = directory.user(userId).map(Recipient::of).stream().toList();
        return new Request(template, ngoId, to, vars, "/ngo/campaigns/" + e.resourceId(), "CAMPAIGN", e.resourceId(),
                e.eventId(), true, true);
    }

    private static String displayName(Directory.Person p) {
        return p.name() == null || p.name().isBlank() ? p.email() : p.name();
    }

    private Request toAdmins(String template, UUID ngoId, Map<String, Object> vars, String link, EventEnvelope e) {
        List<Recipient> admins = directory.admins(ngoId).stream().map(Recipient::of).toList();
        return new Request(template, ngoId, admins, vars, link, "NGO", String.valueOf(ngoId), e.eventId(), true, true);
    }

    /** Prefer the name carried by the event; otherwise ask the registry. */
    private String name(EventEnvelope e) {
        String n = e.str("displayName");
        return n != null && !n.isBlank() ? n : directory.ngoName(e.tenantId());
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
