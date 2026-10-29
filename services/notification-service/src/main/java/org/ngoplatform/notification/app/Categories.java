package org.ngoplatform.notification.app;

import java.util.List;
import java.util.Set;

/** Communication categories users can tune (NOTIF-003). ACCOUNT messages are mandatory (legal/security). */
public final class Categories {
    private Categories() {}
    public static final String ACCOUNT = "ACCOUNT";
    public static final String TEAM = "TEAM";
    public static final String BILLING = "BILLING";
    public static final String DONATIONS = "DONATIONS";
    public static final String CAMPAIGNS = "CAMPAIGNS";
    public static final String HELPDESK = "HELPDESK";

    public static final List<String> ALL = List.of(ACCOUNT, TEAM, BILLING, DONATIONS, CAMPAIGNS, HELPDESK);
    /** Cannot be switched off: verification decisions, suspensions, invitations addressed to you. */
    public static final Set<String> MANDATORY = Set.of(ACCOUNT);

    public static boolean isKnown(String c) { return ALL.contains(c); }
}
