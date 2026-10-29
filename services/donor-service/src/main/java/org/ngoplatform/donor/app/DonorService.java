package org.ngoplatform.donor.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Predicate;
import java.io.BufferedReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.ngoplatform.commons.audit.AuditLog;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.donor.domain.Donor;
import org.ngoplatform.donor.domain.Records;
import org.ngoplatform.donor.domain.Repos;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DonorService {
    public static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");
    public static final Pattern PHONE = Pattern.compile("^[6-9]\\d{9}$");
    public static final Pattern PAN = Pattern.compile("^[A-Z]{5}\\d{4}[A-Z]$");
    public static final Pattern PIN = Pattern.compile("^[1-9]\\d{5}$");

    public record DonorInput(Donor.Type type, String name, String email, String phone, String pan, String addressLine,
                             String city, String state, String pinCode, String contactPerson, List<String> tags,
                             String notes, Boolean communicationOptIn) {}

    public record Filter(String q, String tag, String city, BigDecimal minTotal, Integer lapsedDays, Integer activeWithinDays,
                         Donor.Type type) {}

    private final Repos.Donors donors;
    private final Repos.Facts facts;
    private final Repos.Imports imports;
    private final AuditLog audit;
    private final ObjectMapper mapper;

    public DonorService(Repos.Donors donors, Repos.Facts facts, Repos.Imports imports, AuditLog audit, ObjectMapper mapper) {
        this.donors = donors; this.facts = facts; this.imports = imports; this.audit = audit; this.mapper = mapper;
    }

    static String norm(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    static String lower(String s) { return s == null || s.isBlank() ? null : s.trim().toLowerCase(); }
    static String upper(String s) { return s == null || s.isBlank() ? null : s.trim().toUpperCase(); }

    public Donor load(UUID ngoId, UUID donorId) {
        return donors.findByIdAndNgoId(donorId, ngoId).filter(d -> d.getStatus() != Donor.Status.MERGED)
                .orElseThrow(() -> ApiException.notFound("Donor"));
    }

    @Transactional
    public Donor create(UUID ngoId, UUID actor, DonorInput in) {
        Donor d = new Donor(ngoId, in.type(), in.name().trim(), "MANUAL", actor);
        apply(d, in);
        ensureUnique(d);
        donors.save(d);
        audit.record(ngoId, actor, "DONOR_CREATED", "DONOR", d.getId(), Map.of());
        return d;
    }

    @Transactional
    public Donor update(UUID ngoId, UUID actor, UUID donorId, DonorInput in, long expectedVersion) {
        Donor d = load(ngoId, donorId);
        if (d.getStatus() == Donor.Status.ERASED) throw ApiException.rule("DONOR_ERASED", "This donor's data was erased");
        if (d.getVersion() != expectedVersion) {
            throw ApiException.conflict("STALE_VERSION", "The donor was changed by someone else. Reload and retry.");
        }
        apply(d, in);
        ensureUnique(d);
        audit.record(ngoId, actor, "DONOR_UPDATED", "DONOR", donorId, Map.of());
        return d;
    }

    private void apply(Donor d, DonorInput in) {
        d.update(in.type(), in.name().trim(), lower(in.email()), norm(in.phone()), upper(in.pan()), norm(in.addressLine()),
                norm(in.city()), norm(in.state()), norm(in.pinCode()), norm(in.contactPerson()),
                in.tags() == null ? List.of() : in.tags().stream().map(String::trim).filter(t -> !t.isEmpty()).distinct().limit(20).toList(),
                norm(in.notes()), in.communicationOptIn());
    }

    private void ensureUnique(Donor d) {
        if (d.getEmail() != null) donors.findActiveByEmail(d.getNgoId(), d.getEmail()).filter(o -> !o.getId().equals(d.getId()))
                .ifPresent(o -> { throw ApiException.conflict("DUPLICATE_EMAIL", "Another donor already uses this email"); });
        if (d.getPhone() != null) donors.findActiveByPhone(d.getNgoId(), d.getPhone()).filter(o -> !o.getId().equals(d.getId()))
                .ifPresent(o -> { throw ApiException.conflict("DUPLICATE_PHONE", "Another donor already uses this mobile number"); });
    }

    public List<Donor> duplicatesOf(Donor d) {
        return donors.possibleDuplicates(d.getNgoId(), d.getId(), d.getName(), d.getPan() == null ? "" : d.getPan());
    }

    /**
     * Search & segmentation. BASIC: text/tag/city. STANDARD: + amount & recency. ADVANCED: + saved segments (controller).
     */
    @Transactional(readOnly = true)
    public Page<Donor> search(UUID ngoId, Filter f, String level, int page, int size) {
        if (!"STANDARD".equals(level) && !"ADVANCED".equals(level)
                && (f.minTotal() != null || f.lapsedDays() != null || f.activeWithinDays() != null)) {
            throw ApiException.entitlement("DONOR_SEGMENTATION_LEVEL", "Amount and recency filters need the Growth plan or higher");
        }
        Specification<Donor> spec = (root, q, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("ngoId"), ngoId));
            p.add(cb.equal(root.get("status"), Donor.Status.ACTIVE));
            if (f.q() != null && !f.q().isBlank()) {
                String like = "%" + f.q().trim().toLowerCase().replace("%", "\\%").replace("_", "\\_") + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("name")), like, '\\'), cb.like(cb.lower(root.get("email")), like, '\\'),
                        cb.like(root.get("phone"), like, '\\')));
            }
            if (f.city() != null && !f.city().isBlank()) p.add(cb.equal(cb.lower(root.get("city")), f.city().trim().toLowerCase()));
            if (f.type() != null) p.add(cb.equal(root.get("type"), f.type()));
            if (f.minTotal() != null) p.add(cb.greaterThanOrEqualTo(root.get("totalDonated"), f.minTotal()));
            Instant now = Instant.now();
            if (f.lapsedDays() != null) {
                p.add(cb.or(cb.isNull(root.get("lastDonationAt")),
                        cb.lessThan(root.get("lastDonationAt"), now.minus(f.lapsedDays(), ChronoUnit.DAYS))));
            }
            if (f.activeWithinDays() != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("lastDonationAt"), now.minus(f.activeWithinDays(), ChronoUnit.DAYS)));
            }
            if (f.tag() != null && !f.tag().isBlank()) {
                p.add(cb.isTrue(cb.function("jsonb_exists", Boolean.class, root.get("tags"), cb.literal(f.tag().trim()))));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        return donors.findAll(spec, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "lastDonationAt").and(Sort.by("name"))));
    }

    /** DONOR-014: merge 'source' into 'target'; donation facts move, the source keeps only a pointer. */
    @Transactional
    public Donor merge(UUID ngoId, UUID actor, UUID sourceId, UUID targetId) {
        if (sourceId.equals(targetId)) throw ApiException.badRequest("SAME_DONOR", "Choose two different donors");
        Donor source = load(ngoId, sourceId);
        Donor target = load(ngoId, targetId);
        if (source.getStatus() != Donor.Status.ACTIVE || target.getStatus() != Donor.Status.ACTIVE) {
            throw ApiException.rule("DONOR_NOT_ACTIVE", "Only active donors can be merged");
        }
        facts.findByDonorId(sourceId).forEach(f -> f.reassign(targetId));
        source.markMerged(targetId);
        donors.saveAndFlush(source); // releases unique email/phone before the target may take them
        target.absorb(source);
        audit.record(ngoId, actor, "DONOR_MERGED", "DONOR", targetId, Map.of("merged", sourceId.toString()));
        return target;
    }

    @Transactional
    public Donor erase(UUID ngoId, UUID actor, UUID donorId) {
        Donor d = load(ngoId, donorId);
        d.erase();
        audit.record(ngoId, actor, "DONOR_ERASED", "DONOR", donorId, Map.of());
        return d;
    }

    // ------------------------------------------------------------------ online donations

    /**
     * Finds or creates the NGO's donor record for an online donation (called synchronously by donation-payment-service
     * at checkout). Match order: linked account, email, phone.
     */
    @Transactional
    public Donor resolve(UUID ngoId, UUID userId, String name, String email, String phone, String pan, boolean guest) {
        String e = lower(email), ph = norm(phone), p = upper(pan);
        if (e != null && !EMAIL.matcher(e).matches()) throw ApiException.badRequest("INVALID_EMAIL", "Invalid email");
        if (ph != null && !PHONE.matcher(ph).matches()) throw ApiException.badRequest("INVALID_PHONE", "Invalid mobile number");
        if (p != null && !PAN.matcher(p).matches()) throw ApiException.badRequest("INVALID_PAN", "Invalid PAN");
        Optional<Donor> found = Optional.empty();
        if (userId != null) found = donors.findActiveByUser(ngoId, userId);
        if (found.isEmpty() && e != null) found = donors.findActiveByEmail(ngoId, e);
        if (found.isEmpty() && ph != null) found = donors.findActiveByPhone(ngoId, ph);
        if (found.isPresent()) {
            Donor d = found.get();
            d.enrich(e, ph, p, userId);
            return d;
        }
        Donor d = new Donor(ngoId, Donor.Type.INDIVIDUAL, name == null || name.isBlank() ? "Donor" : name.trim(),
                guest ? "GUEST" : "ONLINE", null);
        d.update(Donor.Type.INDIVIDUAL, d.getName(), e, ph, p, null, null, null, null, null, List.of(), null, true);
        d.enrich(null, null, null, userId);
        return donors.save(d);
    }

    @Transactional
    public boolean applyPaidDonation(UUID donationId, UUID ngoId, UUID donorId, BigDecimal amount, UUID campaignId, Instant paidAt) {
        if (facts.existsById(donationId)) return false;
        Donor d = donors.findByIdAndNgoId(donorId, ngoId).orElseThrow(() -> ApiException.notFound("Donor"));
        while (d.getStatus() == Donor.Status.MERGED && d.getMergedInto() != null) {
            d = donors.findById(d.getMergedInto()).orElseThrow();
        }
        facts.save(new Records.DonationFact(donationId, d.getId(), ngoId, amount, campaignId, paidAt));
        d.applyDonation(amount, paidAt);
        return true;
    }

    @Transactional
    public void applyRefund(UUID donationId, BigDecimal refundedTotal) {
        facts.findById(donationId).ifPresent(f -> {
            BigDecimal delta = refundedTotal.subtract(f.getRefunded());
            if (delta.signum() <= 0) return;
            f.refund(refundedTotal);
            donors.findById(f.getDonorId()).ifPresent(d -> d.applyRefund(delta));
        });
    }

    // ------------------------------------------------------------------ import (DONOR-010..012)

    public record ImportRow(int line, String name, String email, String phone, String pan, String city, String type,
                            String status, String error) {}

    @Transactional
    public Records.Import previewImport(UUID ngoId, UUID actor, byte[] csv) {
        List<ImportRow> rows = new ArrayList<>();
        Set<String> seenEmails = new HashSet<>(), seenPhones = new HashSet<>();
        int valid = 0, dup = 0, invalid = 0, line = 0;
        try (BufferedReader r = new BufferedReader(new StringReader(new String(csv, StandardCharsets.UTF_8)))) {
            String header = r.readLine();
            if (header == null) throw ApiException.badRequest("EMPTY_FILE", "The file is empty");
            List<String> cols = splitCsv(header.replace("﻿", "")).stream().map(c -> c.trim().toLowerCase()).toList();
            if (!cols.contains("name")) throw ApiException.badRequest("MISSING_NAME_COLUMN", "A 'name' column is required");
            String s;
            while ((s = r.readLine()) != null) {
                line++;
                if (s.isBlank()) continue;
                if (line > 5000) throw ApiException.badRequest("TOO_MANY_ROWS", "Import at most 5000 rows at a time");
                List<String> v = splitCsv(s);
                Map<String, String> m = new HashMap<>();
                for (int i = 0; i < cols.size() && i < v.size(); i++) m.put(cols.get(i), v.get(i).trim());
                String name = norm(m.get("name")), email = lower(m.get("email")), phone = norm(m.get("phone")),
                        pan = upper(m.get("pan")), city = norm(m.get("city")), type = upper(m.get("type"));
                String error = null;
                if (name == null || name.length() < 2 || name.length() > 160) error = "Name must be 2-160 characters";
                else if (email != null && !EMAIL.matcher(email).matches()) error = "Invalid email";
                else if (phone != null && !PHONE.matcher(phone).matches()) error = "Invalid mobile number";
                else if (pan != null && !PAN.matcher(pan).matches()) error = "Invalid PAN";
                else if (type != null && !type.equals("INDIVIDUAL") && !type.equals("ORGANIZATION")) error = "Type must be INDIVIDUAL or ORGANIZATION";
                String status;
                if (error != null) { status = "INVALID"; invalid++; }
                else if ((email != null && (!seenEmails.add(email) || donors.findActiveByEmail(ngoId, email).isPresent()))
                        || (phone != null && (!seenPhones.add(phone) || donors.findActiveByPhone(ngoId, phone).isPresent()))) {
                    status = "DUPLICATE"; dup++;
                } else { status = "VALID"; valid++; }
                rows.add(new ImportRow(line + 1, name, email, phone, pan, city, type == null ? "INDIVIDUAL" : type, status, error));
            }
        } catch (java.io.IOException e) {
            throw ApiException.badRequest("UNREADABLE_FILE", "Could not read the CSV file");
        }
        String payload;
        try { payload = mapper.writeValueAsString(rows); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
        return imports.save(new Records.Import(ngoId, rows.size(), valid, dup, invalid, payload, actor));
    }

    @Transactional
    public Records.Import commitImport(UUID ngoId, UUID actor, UUID importId) {
        Records.Import imp = imports.findByIdAndNgoId(importId, ngoId).orElseThrow(() -> ApiException.notFound("Import"));
        if (!"PREVIEWED".equals(imp.getStatus())) throw ApiException.rule("IMPORT_NOT_PENDING", "Import already " + imp.getStatus());
        List<ImportRow> rows;
        try { rows = mapper.readValue(imp.getPayload(), new TypeReference<>() {}); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
        int created = 0;
        for (ImportRow row : rows) {
            if (!"VALID".equals(row.status())) continue;
            // Re-check at commit time: another user may have added the same donor since the preview.
            if ((row.email() != null && donors.findActiveByEmail(ngoId, row.email()).isPresent())
                    || (row.phone() != null && donors.findActiveByPhone(ngoId, row.phone()).isPresent())) continue;
            Donor d = new Donor(ngoId, Donor.Type.valueOf(row.type()), row.name(), "IMPORT", actor);
            d.update(Donor.Type.valueOf(row.type()), row.name(), row.email(), row.phone(), row.pan(), null, row.city(), null,
                    null, null, List.of(), null, true);
            donors.save(d);
            created++;
        }
        imp.setStatus("COMMITTED");
        audit.record(ngoId, actor, "DONORS_IMPORTED", "DONOR_IMPORT", importId, Map.of("created", created));
        return imp;
    }

    static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else if (c == '"') quoted = false;
                else cur.append(c);
            } else if (c == '"') quoted = true;
            else if (c == ',') { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    /** CSV export with formula-injection protection (cells starting with = + - @ are prefixed). */
    public static String csvCell(Object v) {
        if (v == null) return "";
        String s = v.toString();
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) s = "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }
}
