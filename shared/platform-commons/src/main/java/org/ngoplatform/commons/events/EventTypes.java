package org.ngoplatform.commons.events;

/** Routing keys on the "ngo.events" topic exchange. Format: <context>.<entity>.<past-tense-verb>. */
public final class EventTypes {
    private EventTypes() {}
    public static final String EXCHANGE = "ngo.events";
    public static final String DLX = "ngo.events.dlx";

    public static final String NGO_REGISTERED = "ngo.registration.submitted";
    public static final String NGO_VERIFIED = "ngo.verification.approved";
    public static final String NGO_REJECTED = "ngo.verification.rejected";
    public static final String NGO_INFO_REQUESTED = "ngo.verification.info-requested";
    public static final String NGO_SUSPENDED = "ngo.account.suspended";
    public static final String NGO_DONATION_ELIGIBILITY_CHANGED = "ngo.donation-eligibility.changed";

    public static final String STAFF_INVITED = "identity.staff.invited";
    public static final String STAFF_ACCEPTED = "identity.staff.accepted";
    public static final String MEMBERSHIP_CHANGED = "identity.membership.changed";

    public static final String SUBSCRIPTION_ACTIVATED = "subscription.subscription.activated";
    public static final String SUBSCRIPTION_CHANGED = "subscription.subscription.changed";
    public static final String SUBSCRIPTION_EXPIRING = "subscription.subscription.expiring";
    public static final String SUBSCRIPTION_STATUS_CHANGED = "subscription.subscription.status-changed";
    public static final String SUBSCRIPTION_PAYMENT_FAILED = "subscription.payment.failed";

    public static final String DONATION_PAID = "donation.donation.paid";
    public static final String DONATION_FAILED = "donation.donation.failed";
    public static final String DONATION_REFUNDED = "donation.donation.refunded";
    public static final String DONATION_VOIDED = "donation.donation.voided";
    public static final String REFUND_REQUESTED = "donation.refund.requested";
    public static final String RECON_EXCEPTION = "donation.reconciliation.exception";

    public static final String RECEIPT_GENERATED = "receipt.receipt.generated";
    public static final String RECEIPT_FAILED = "receipt.receipt.generation-failed";
    public static final String RECEIPT_VOIDED = "receipt.receipt.voided";

    public static final String CAMPAIGN_SUBMITTED = "campaign.campaign.submitted";
    public static final String CAMPAIGN_PUBLISHED = "campaign.campaign.published";
    public static final String CAMPAIGN_REJECTED = "campaign.campaign.rejected";
    public static final String CAMPAIGN_EXPENSE_SUBMITTED = "campaign.expense.submitted";
    public static final String CAMPAIGN_EXPENSE_APPROVED = "campaign.expense.approved";
    public static final String CAMPAIGN_EXPENSE_REJECTED = "campaign.expense.rejected";
    public static final String EXPENSE_APPROVED = "operations.expense.approved";

    /** A file kept by another service (registration document, expense bill, receipt) is listed in the document vault. */
    public static final String DOCUMENT_FILE_REGISTERED = "document.file.registered";
    public static final String DOCUMENT_FILE_REMOVED = "document.file.removed";
    public static final String MEETING_SCHEDULED = "document.meeting.scheduled";
    public static final String MEETING_ACTION_ASSIGNED = "document.meeting.action-assigned";

    public static final String TICKET_CREATED = "helpdesk.ticket.created";
    public static final String TICKET_UPDATED = "helpdesk.ticket.updated";
    public static final String TICKET_ESCALATED = "helpdesk.ticket.escalated";

    public static final String NOTIFICATION_REQUESTED = "notification.notification.requested";
    public static final String NOTIFICATION_DELIVERED = "notification.delivery.succeeded";
    public static final String NOTIFICATION_FAILED = "notification.delivery.failed";
}
