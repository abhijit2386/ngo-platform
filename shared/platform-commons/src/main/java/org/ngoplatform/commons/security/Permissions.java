package org.ngoplatform.commons.security;

import java.util.Map;
import java.util.Set;

/**
 * Business permission codes (stable technical contract). The authoritative assignment of
 * permissions to members lives in identity-service; this class only names them.
 */
public final class Permissions {
    private Permissions() {}

    public static final String NGO_PROFILE_MANAGE = "NGO_PROFILE_MANAGE";
    public static final String NGO_SENSITIVE_CHANGE = "NGO_SENSITIVE_CHANGE";
    public static final String STAFF_VIEW = "STAFF_VIEW";
    public static final String STAFF_MANAGE = "STAFF_MANAGE";
    public static final String SUBSCRIPTION_VIEW = "SUBSCRIPTION_VIEW";
    public static final String SUBSCRIPTION_MANAGE = "SUBSCRIPTION_MANAGE";
    public static final String DONOR_VIEW = "DONOR_VIEW";
    public static final String DONOR_MANAGE = "DONOR_MANAGE";
    public static final String DONATION_VIEW = "DONATION_VIEW";
    public static final String DONATION_RECORD = "DONATION_RECORD";
    public static final String REFUND_REQUEST = "REFUND_REQUEST";
    public static final String REFUND_APPROVE = "REFUND_APPROVE";
    public static final String RECONCILIATION_MANAGE = "RECONCILIATION_MANAGE";
    public static final String RECEIPT_VIEW = "RECEIPT_VIEW";
    public static final String RECEIPT_MANAGE = "RECEIPT_MANAGE";
    public static final String RECEIPT_TEMPLATE_DESIGN = "RECEIPT_TEMPLATE_DESIGN";
    public static final String RECEIPT_TEMPLATE_APPROVE = "RECEIPT_TEMPLATE_APPROVE";
    public static final String CAMPAIGN_VIEW = "CAMPAIGN_VIEW";
    public static final String CAMPAIGN_MANAGE = "CAMPAIGN_MANAGE";
    public static final String CAMPAIGN_APPROVE = "CAMPAIGN_APPROVE";
    public static final String PROJECT_VIEW = "PROJECT_VIEW";
    public static final String PROJECT_MANAGE = "PROJECT_MANAGE";
    public static final String VOLUNTEER_VIEW = "VOLUNTEER_VIEW";
    public static final String VOLUNTEER_MANAGE = "VOLUNTEER_MANAGE";
    public static final String BENEFICIARY_VIEW = "BENEFICIARY_VIEW";
    public static final String BENEFICIARY_MANAGE = "BENEFICIARY_MANAGE";
    public static final String EXPENSE_VIEW = "EXPENSE_VIEW";
    public static final String EXPENSE_RECORD = "EXPENSE_RECORD";
    public static final String EXPENSE_APPROVE = "EXPENSE_APPROVE";
    public static final String REPORT_VIEW = "REPORT_VIEW";
    public static final String REPORT_EXPORT = "REPORT_EXPORT";
    public static final String DOCUMENT_VIEW = "DOCUMENT_VIEW";
    public static final String DOCUMENT_UPLOAD = "DOCUMENT_UPLOAD";
    public static final String GOVERNANCE_VIEW = "GOVERNANCE_VIEW";
    public static final String GOVERNANCE_MANAGE = "GOVERNANCE_MANAGE";
    public static final String HELPDESK_USE = "HELPDESK_USE";
    public static final String HELPDESK_AGENT = "HELPDESK_AGENT";
    public static final String HELPDESK_CONFIGURE = "HELPDESK_CONFIGURE";

    public static final Set<String> ALL = Set.of(NGO_PROFILE_MANAGE, NGO_SENSITIVE_CHANGE, STAFF_VIEW, STAFF_MANAGE,
            SUBSCRIPTION_VIEW, SUBSCRIPTION_MANAGE, DONOR_VIEW, DONOR_MANAGE, DONATION_VIEW, DONATION_RECORD,
            REFUND_REQUEST, REFUND_APPROVE, RECONCILIATION_MANAGE, RECEIPT_VIEW, RECEIPT_MANAGE,
            RECEIPT_TEMPLATE_DESIGN, RECEIPT_TEMPLATE_APPROVE, CAMPAIGN_VIEW, CAMPAIGN_MANAGE, CAMPAIGN_APPROVE,
            PROJECT_VIEW, PROJECT_MANAGE, VOLUNTEER_VIEW, VOLUNTEER_MANAGE, BENEFICIARY_VIEW, BENEFICIARY_MANAGE,
            EXPENSE_VIEW, EXPENSE_RECORD, EXPENSE_APPROVE, REPORT_VIEW, REPORT_EXPORT, HELPDESK_USE, HELPDESK_AGENT,
            HELPDESK_CONFIGURE, DOCUMENT_VIEW, DOCUMENT_UPLOAD, GOVERNANCE_VIEW, GOVERNANCE_MANAGE);

    /**
     * Default role templates. NGOs can customise per member; identity-service stores the effective set.
     * Campaigns: every team role except VOLUNTEER may draft and submit (CAMPAIGN_MANAGE); only administrators
     * approve and publish - owner decision 2026-10-29 (campaign-service also checks the admin role).
     * Campaign expenses: anyone who runs campaigns records them with bills (EXPENSE_RECORD); only administrators
     * approve (FINANCE holds EXPENSE_APPROVE for other finance work, campaign-service also requires the admin role).
     * Documents (owner decision 2026-11-01): team members upload and see their own + team-shared documents; admins see
     * everything. Meetings: admins manage (GOVERNANCE_MANAGE); the team sees team meetings and their action items.
     */
    public static final Map<String, Set<String>> ROLE_TEMPLATES = Map.of(
            "PRIMARY_ADMIN", ALL,
            "SECONDARY_ADMIN", ALL,
            "STAFF", Set.of(DOCUMENT_VIEW, DOCUMENT_UPLOAD, GOVERNANCE_VIEW, DONOR_VIEW, DONOR_MANAGE, DONATION_VIEW, DONATION_RECORD, RECEIPT_VIEW, CAMPAIGN_VIEW, CAMPAIGN_MANAGE,
                    PROJECT_VIEW, VOLUNTEER_VIEW, BENEFICIARY_VIEW, BENEFICIARY_MANAGE, EXPENSE_VIEW, EXPENSE_RECORD,
                    HELPDESK_USE, STAFF_VIEW, REPORT_VIEW),
            "FINANCE", Set.of(DOCUMENT_VIEW, DOCUMENT_UPLOAD, GOVERNANCE_VIEW, DONOR_VIEW, DONATION_VIEW, DONATION_RECORD, REFUND_REQUEST, RECONCILIATION_MANAGE,
                    RECEIPT_VIEW, RECEIPT_MANAGE, EXPENSE_VIEW, EXPENSE_RECORD, EXPENSE_APPROVE, REPORT_VIEW,
                    REPORT_EXPORT, HELPDESK_USE, STAFF_VIEW, SUBSCRIPTION_VIEW, CAMPAIGN_VIEW, CAMPAIGN_MANAGE),
            "CAMPAIGN_MANAGER", Set.of(DOCUMENT_VIEW, DOCUMENT_UPLOAD, GOVERNANCE_VIEW, CAMPAIGN_VIEW, CAMPAIGN_MANAGE, PROJECT_VIEW, PROJECT_MANAGE, DONOR_VIEW,
                    DONATION_VIEW, REPORT_VIEW, HELPDESK_USE, STAFF_VIEW, EXPENSE_VIEW, EXPENSE_RECORD),
            "VOLUNTEER_COORDINATOR", Set.of(DOCUMENT_VIEW, DOCUMENT_UPLOAD, GOVERNANCE_VIEW, VOLUNTEER_VIEW, VOLUNTEER_MANAGE, PROJECT_VIEW, BENEFICIARY_VIEW, CAMPAIGN_VIEW, CAMPAIGN_MANAGE,
                    HELPDESK_USE, STAFF_VIEW, REPORT_VIEW, EXPENSE_VIEW, EXPENSE_RECORD),
            "VOLUNTEER", Set.of(PROJECT_VIEW, HELPDESK_USE));

    public static boolean isAdminRole(String role) {
        return "PRIMARY_ADMIN".equals(role) || "SECONDARY_ADMIN".equals(role);
    }
}
