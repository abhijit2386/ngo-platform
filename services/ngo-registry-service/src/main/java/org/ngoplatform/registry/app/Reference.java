package org.ngoplatform.registry.app;

import java.util.List;
import java.util.Set;

/** Reference data used for validation (states/UTs of India, cause areas, document types). */
public final class Reference {
    private Reference() {}

    public static final List<String> STATES = List.of("Andhra Pradesh", "Arunachal Pradesh", "Assam", "Bihar",
            "Chhattisgarh", "Goa", "Gujarat", "Haryana", "Himachal Pradesh", "Jharkhand", "Karnataka", "Kerala",
            "Madhya Pradesh", "Maharashtra", "Manipur", "Meghalaya", "Mizoram", "Nagaland", "Odisha", "Punjab",
            "Rajasthan", "Sikkim", "Tamil Nadu", "Telangana", "Tripura", "Uttar Pradesh", "Uttarakhand", "West Bengal",
            "Andaman and Nicobar Islands", "Chandigarh", "Dadra and Nagar Haveli and Daman and Diu", "Delhi",
            "Jammu and Kashmir", "Ladakh", "Lakshadweep", "Puducherry");

    public static final Set<String> CAUSES = Set.of("EDUCATION", "HEALTH", "WOMEN_EMPOWERMENT", "CHILD_WELFARE",
            "ELDERLY_CARE", "DISABILITY", "ENVIRONMENT", "ANIMAL_WELFARE", "RURAL_DEVELOPMENT", "DISASTER_RELIEF",
            "LIVELIHOOD", "HUNGER_NUTRITION", "WATER_SANITATION", "ARTS_CULTURE", "SPORTS", "OTHER");

    public static final Set<String> DOC_TYPES = Set.of("REGISTRATION_CERTIFICATE", "PAN_CARD", "TRUST_DEED_OR_MOA",
            "CERTIFICATE_12A", "CERTIFICATE_80G", "FCRA", "ADDRESS_PROOF", "SIGNATORY_ID", "LOGO", "OTHER");

    /** Documents that must be present (not rejected) before submission. */
    public static final Set<String> REQUIRED_DOCS = Set.of("REGISTRATION_CERTIFICATE", "ADDRESS_PROOF", "SIGNATORY_ID");
}
