package org.ngoplatform.registry.app;

import java.util.LinkedHashMap;
import java.util.Map;
import org.ngoplatform.registry.domain.Parts;

/**
 * Registration documents as entries of the NGO's document vault (document-service): payload of
 * document.file.registered / removed, also served to the vault's one-time backfill. Administrators only (RESTRICTED).
 */
public final class VaultFiles {
    private VaultFiles() {}

    static final String SOURCE = "REGISTRATION";

    public static Map<String, Object> registered(Parts.Document d) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("ngoId", d.getNgoId().toString());
        p.put("source", SOURCE);
        p.put("sourceRef", d.getId().toString());
        p.put("storageKey", d.getObjectKey());
        p.put("title", title(d.getType()));
        p.put("category", category(d.getType()));
        p.put("contentType", d.getContentType());
        p.put("sizeBytes", d.getSizeBytes());
        p.put("originalName", d.getFileName());
        if (d.getUploadedBy() != null) p.put("uploadedBy", d.getUploadedBy().toString());
        p.put("visibility", "RESTRICTED");
        p.put("linkType", "NGO");
        p.put("linkId", d.getNgoId().toString());
        p.put("linkLabel", "NGO registration");
        if (d.getExpiresOn() != null) p.put("expiresOn", d.getExpiresOn().toString());
        p.put("createdAt", d.getCreatedAt().toString());
        return p;
    }

    public static Map<String, Object> removed(Parts.Document d) {
        return Map.of("source", SOURCE, "sourceRef", d.getId().toString());
    }

    static String category(String type) {
        return switch (type) {
            case "PAN_CARD", "CERTIFICATE_12A", "CERTIFICATE_80G", "FCRA" -> "TAX_COMPLIANCE";
            default -> "LEGAL_REGISTRATION";
        };
    }

    static String title(String type) {
        return switch (type) {
            case "REGISTRATION_CERTIFICATE" -> "Registration certificate";
            case "PAN_CARD" -> "PAN card of the NGO";
            case "TRUST_DEED_OR_MOA" -> "Trust deed / MoA";
            case "CERTIFICATE_12A" -> "12A certificate";
            case "CERTIFICATE_80G" -> "80G certificate";
            case "FCRA" -> "FCRA certificate";
            case "ADDRESS_PROOF" -> "Address proof";
            case "SIGNATORY_ID" -> "Authorised signatory ID";
            default -> "Registration document";
        };
    }
}
