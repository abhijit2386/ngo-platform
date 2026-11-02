package org.ngoplatform.campaign.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.ngoplatform.campaign.app.CampaignService;
import org.ngoplatform.campaign.app.UcRules;
import org.ngoplatform.campaign.app.UcService;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.security.AccessGuard;
import org.ngoplatform.commons.security.Entitlements;
import org.ngoplatform.commons.security.Membership;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Utilisation certificates (Enterprise: CSR_UTILISATION_CERT). Administrators prepare, sign and cancel; reading and
 * cancelling stay possible after a downgrade. The chartered accountant certifies through /api/v1/public/uc/{code}
 * (no login, link valid 14 days, one certification).
 */
@RestController
public class UcController {
    public record UcRequest(UUID campaignId, String funderName, String funderReference, String csr1Number, BigDecimal grantAmount,
                            LocalDate grantReceivedOn, LocalDate periodFrom, LocalDate periodTo, Long version) {
        UcService.Input input() {
            return new UcService.Input(campaignId, funderName, funderReference, csr1Number, grantAmount, grantReceivedOn, periodFrom, periodTo);
        }
    }
    public record SignRequest(String signatoryName, String signatoryRole, Long version) {}
    public record CancelRequest(String reason) {}

    private static final String BASE = "/api/v1/ngos/{ngoId}/ucs";
    private static final String NEEDS = "Utilisation certificates are part of the Enterprise plan";
    private static final MediaType HTML = MediaType.parseMediaType("text/html; charset=UTF-8");
    /** Inline styles and a form posting to this same address; nothing else. */
    static final String PAGE_CSP = "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH).withZone(ZoneId.of("Asia/Kolkata"));

    private final UcService ucs;
    private final CampaignService campaigns;
    private final AccessGuard guard;
    private final String publicApi;

    public UcController(UcService ucs, CampaignService campaigns, AccessGuard guard,
                        @Value("${ngo.public-web-url:http://localhost:8080}") String publicApi) {
        this.ucs = ucs; this.campaigns = campaigns; this.guard = guard; this.publicApi = publicApi.replaceAll("/+$", "");
    }

    // ------------------------------------------------------------------ administrators

    @GetMapping(BASE)
    public Map<String, Object> list(@PathVariable UUID ngoId, @RequestParam(required = false) UUID campaignId) {
        guard.requireAdmin(ngoId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (UcService.Uc uc : ucs.list(ngoId, campaignId)) items.add(summary(uc));
        return Map.of("enabled", guard.entitlements(ngoId).flag(Entitlements.CSR_UTILISATION_CERT), "items", items);
    }

    @PostMapping(BASE)
    public Map<String, Object> create(@PathVariable UUID ngoId, @RequestBody UcRequest req) {
        Membership m = admin(ngoId);
        return detail(ucs.create(ngoId, m.userId(), req.input()));
    }

    @GetMapping(BASE + "/{id}")
    public Map<String, Object> get(@PathVariable UUID ngoId, @PathVariable UUID id) {
        guard.requireAdmin(ngoId);
        return detail(ucs.get(ngoId, id));
    }

    @PutMapping(BASE + "/{id}")
    public Map<String, Object> update(@PathVariable UUID ngoId, @PathVariable UUID id, @RequestBody UcRequest req) {
        Membership m = admin(ngoId);
        return detail(ucs.update(ngoId, m.userId(), id, req.input(), req.version() == null ? -1 : req.version()));
    }

    @PostMapping(BASE + "/{id}/sign")
    public Map<String, Object> sign(@PathVariable UUID ngoId, @PathVariable UUID id, @RequestBody SignRequest req) {
        Membership m = admin(ngoId);
        return detail(ucs.sign(ngoId, m.userId(), id, req.signatoryName(), req.signatoryRole(), req.version() == null ? -1 : req.version()));
    }

    @PostMapping(BASE + "/{id}/ca-link")
    public Map<String, Object> renew(@PathVariable UUID ngoId, @PathVariable UUID id) {
        Membership m = admin(ngoId);
        return detail(ucs.renewCaLink(ngoId, m.userId(), id));
    }

    /** Cancelling is always possible (also after a downgrade). */
    @PostMapping(BASE + "/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable UUID ngoId, @PathVariable UUID id, @RequestBody CancelRequest req) {
        Membership m = guard.requireAdmin(ngoId);
        return detail(ucs.cancel(ngoId, m.userId(), id, req.reason()));
    }

    @GetMapping(BASE + "/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID ngoId, @PathVariable UUID id) {
        guard.requireAdmin(ngoId);
        UcService.Uc uc = ucs.get(ngoId, id);
        return pdfResponse(ucs.pdf(uc), uc, false);
    }

    private Membership admin(UUID ngoId) {
        Membership m = guard.requireAdmin(ngoId);
        guard.requireFlag(ngoId, Entitlements.CSR_UTILISATION_CERT, NEEDS);
        return m;
    }

    private Map<String, Object> summary(UcService.Uc uc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", uc.id());
        m.put("number", uc.number());
        m.put("campaignId", uc.campaignId());
        m.put("campaignTitle", title(uc));
        m.put("funderName", uc.funderName());
        m.put("grantAmount", uc.grantAmount());
        m.put("periodFrom", uc.periodFrom());
        m.put("periodTo", uc.periodTo());
        m.put("status", status(uc));
        m.put("createdAt", uc.createdAt());
        return m;
    }

    /** AWAITING_CA while the CA's link is open; CA_LINK_EXPIRED once it ran out (renew it). */
    private static String status(UcService.Uc uc) {
        if (!"NGO_SIGNED".equals(uc.status())) return uc.status();
        return uc.caLinkExpiresAt() != null && Instant.now().isAfter(uc.caLinkExpiresAt()) ? "CA_LINK_EXPIRED" : "AWAITING_CA";
    }

    private String title(UcService.Uc uc) {
        try { return campaigns.load(uc.ngoId(), uc.campaignId()).getTitle(); } catch (ApiException e) { return ""; }
    }

    private Map<String, Object> detail(UcService.Uc uc) {
        Map<String, Object> m = summary(uc);
        m.put("funderReference", uc.funderReference());
        m.put("csr1Number", uc.csr1Number());
        m.put("grantReceivedOn", uc.grantReceivedOn());
        m.put("version", uc.version());
        UcRules.Figures f = ucs.figures(uc);
        Map<String, Object> fig = new LinkedHashMap<>();
        fig.put("grant", f.grant());
        fig.put("spent", f.spent());
        fig.put("utilised", f.utilised());
        fig.put("unspent", f.unspent());
        fig.put("ownFunds", f.ownFunds());
        fig.put("receivedInPeriod", f.receivedInPeriod());
        fig.put("withoutBill", f.withoutBill());
        fig.put("expenses", f.lines().size());
        fig.put("heads", f.heads());
        fig.put("lines", f.lines());
        fig.put("frozen", uc.figuresJson() != null);
        m.put("figures", fig);
        m.put("signatoryName", uc.signatoryName());
        m.put("signatoryRole", uc.signatoryRole());
        m.put("ngoSignedAt", uc.ngoSignedAt());
        if ("NGO_SIGNED".equals(uc.status())) {
            m.put("caLink", publicApi + "/api/v1/public/uc/" + uc.caCode());
            m.put("caLinkExpiresAt", uc.caLinkExpiresAt());
        }
        m.put("caName", uc.caName());
        m.put("caMembershipNo", uc.caMembershipNo());
        m.put("caFirm", uc.caFirm());
        m.put("caFirmRegNo", uc.caFirmRegNo());
        m.put("caUdin", uc.caUdin());
        m.put("caPlace", uc.caPlace());
        m.put("caCertifiedAt", uc.caCertifiedAt());
        m.put("pdfSha256", uc.pdfSha256());
        m.put("cancelReason", uc.cancelReason());
        m.put("cancelledAt", uc.cancelledAt());
        return m;
    }

    private static ResponseEntity<byte[]> pdfResponse(byte[] pdf, UcService.Uc uc, boolean attachment) {
        String name = uc.number().replace('/', '-') + (uc.pdfKey() == null ? "-draft" : "") + ".pdf";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, (attachment ? "attachment" : "inline") + "; filename=\"" + name + "\"")
                .header("X-Content-Type-Options", "nosniff").body(pdf);
    }

    // ------------------------------------------------------------------ the chartered accountant (public, no login)

    @GetMapping("/api/v1/public/uc/{code}")
    public ResponseEntity<String> caPage(@PathVariable String code) {
        try {
            UcService.Uc uc = ucs.byCaCode(code);
            return page(200, caHtml(uc, code, null, Map.of()));
        } catch (ApiException e) {
            return page(e.status().value(), message(e.status().value() == 410 ? "Link no longer available" : "Link not found", e.getMessage()));
        }
    }

    @GetMapping("/api/v1/public/uc/{code}/pdf")
    public ResponseEntity<byte[]> caPdf(@PathVariable String code) {
        UcService.Uc uc = ucs.byCaCode(code);
        return pdfResponse(ucs.pdf(uc), uc, true);
    }

    @PostMapping(value = "/api/v1/public/uc/{code}/certify", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> certify(@PathVariable String code, @RequestBody MultiValueMap<String, String> form) {
        UcService.Uc uc;
        try {
            uc = ucs.byCaCode(code);
        } catch (ApiException e) {
            return page(e.status().value(), message("Link no longer available", e.getMessage()));
        }
        try {
            UcRules.Ca ca = UcRules.ca(form.getFirst("name"), form.getFirst("membershipNo"), form.getFirst("firm"), form.getFirst("firmRegNo"),
                    form.getFirst("udin"), form.getFirst("place"), campaigns.today());
            UcService.Uc done = ucs.certify(code, ca, "yes".equals(form.getFirst("declare")));
            return page(200, caHtml(done, code, null, Map.of()));
        } catch (ApiException e) {
            return page(e.status().value() == 409 ? 409 : 400, caHtml(uc, code, e.getMessage(), form.toSingleValueMap()));
        }
    }

    private static ResponseEntity<String> page(int status, String html) {
        return ResponseEntity.status(status).contentType(HTML).cacheControl(CacheControl.noStore()).header("Content-Security-Policy", PAGE_CSP)
                .header("X-Robots-Tag", "noindex, nofollow").header("Referrer-Policy", "no-referrer").body(html);
    }

    static String esc(Object o) {
        if (o == null) return "";
        return o.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static final String STYLE = "<style>body{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;margin:0;background:#f6f7f9;color:#14213d}"
            + "main{max-width:820px;margin:0 auto;padding:24px 16px}header{background:#087a4b;color:#fff;border-radius:14px;padding:20px 22px}"
            + "h1{margin:0 0 4px;font-size:21px}h2{font-size:16px;margin:0 0 10px}header p{margin:0;opacity:.92}.card{background:#fff;border-radius:14px;"
            + "padding:18px 20px;margin-top:16px;box-shadow:0 1px 3px rgba(0,0,0,.06)}table{width:100%;border-collapse:collapse;font-size:14px}"
            + "th,td{text-align:left;padding:7px 6px;border-bottom:1px solid #eceef2;vertical-align:top}th{color:#5b6478;font-weight:600;font-size:12px}"
            + "td.n,th.n{text-align:right;white-space:nowrap}tr.t td{font-weight:700}.kv{display:grid;grid-template-columns:200px 1fr;gap:6px 12px;font-size:14px}"
            + ".kv div:nth-child(odd){color:#5b6478}.stmt{line-height:1.55;font-size:14px}label{display:block;font-size:13px;color:#5b6478;margin:10px 0 4px}"
            + "input[type=text]{width:100%;box-sizing:border-box;padding:10px;border:1px solid #c9ced8;border-radius:8px;font-size:15px}"
            + ".row{display:grid;grid-template-columns:1fr 1fr;gap:0 14px}.decl{display:flex;gap:10px;align-items:flex-start;margin-top:14px;font-size:14px}"
            + "button{margin-top:16px;background:#087a4b;color:#fff;border:0;border-radius:8px;padding:12px 18px;font-size:15px;font-weight:600}"
            + "a.btn{display:inline-block;background:#087a4b;color:#fff;text-decoration:none;padding:9px 14px;border-radius:8px;font-weight:600}"
            + ".err{background:#fdecec;color:#b42318;border-radius:8px;padding:10px 12px;margin-bottom:8px}.ok{background:#e6f3ec;color:#087a4b;"
            + "border-radius:8px;padding:12px 14px;font-weight:600}.warn{color:#b45309;font-size:13px}footer{font-size:12px;color:#5b6478;margin-top:18px}"
            + "@media(max-width:600px){.kv{grid-template-columns:1fr}.row{grid-template-columns:1fr}}</style>";

    private String caHtml(UcService.Uc uc, String code, String error, Map<String, String> v) {
        UcRules.Figures f = ucs.figures(uc);
        String project = title(uc);
        StringBuilder heads = new StringBuilder();
        for (UcRules.Head h : f.heads()) {
            heads.append("<tr><td>").append(esc(h.head())).append("</td><td class=n>").append(h.budget() == null ? "-" : esc(UcRules.inr(h.budget())))
                    .append("</td><td class=n>").append(esc(UcRules.inr(h.spent()))).append("</td><td class=n>").append(h.count()).append("</td></tr>");
        }
        StringBuilder lines = new StringBuilder();
        for (UcRules.Line l : f.lines()) {
            lines.append("<tr><td>").append(esc(l.voucherNo())).append("</td><td>").append(esc(DAY.format(l.date()))).append("</td><td>")
                    .append(esc(l.head())).append("</td><td>").append(esc(l.description())).append(l.vendor() == null ? "" : "<br><small>" + esc(l.vendor()) + "</small>")
                    .append("</td><td class=n>").append(esc(UcRules.inr(l.amount()))).append("</td><td>").append(l.bills() > 0 ? "Yes" : "<b>No</b>").append("</td></tr>");
        }
        boolean certified = "CA_CERTIFIED".equals(uc.status());
        String base = publicApi + "/api/v1/public/uc/" + code;
        StringBuilder h = new StringBuilder("<!doctype html><html lang=en><head><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">"
                + "<meta name=robots content=\"noindex,nofollow\"><title>" + esc(uc.number()) + " - Utilisation certificate - Yogdaan</title>" + STYLE + "</head><body><main>");
        h.append("<header><h1>Utilisation certificate ").append(esc(uc.number())).append("</h1><p>").append(esc(project)).append(" · CSR grant from ")
                .append(esc(uc.funderName())).append("</p></header>");
        if (certified) {
            h.append("<div class=card><div class=ok>Certified on ").append(esc(STAMP.format(uc.caCertifiedAt()))).append(" by CA ").append(esc(uc.caName()))
                    .append(" (M. No. ").append(esc(uc.caMembershipNo())).append(", UDIN ").append(esc(uc.caUdin())).append(").</div>")
                    .append("<p><a class=btn href=\"").append(esc(base + "/pdf")).append("\">Download the certified PDF</a></p></div>");
        }
        h.append("<div class=card><h2>Grant</h2><div class=kv><div>CSR funder</div><div>").append(esc(uc.funderName())).append("</div>")
                .append("<div>Sanction / agreement</div><div>").append(esc(uc.funderReference() == null ? "-" : uc.funderReference())).append("</div>")
                .append("<div>Grant received</div><div>").append(esc(UcRules.inr(f.grant()))).append(" on ").append(esc(DAY.format(uc.grantReceivedOn()))).append("</div>")
                .append("<div>Period</div><div>").append(esc(DAY.format(uc.periodFrom()))).append(" to ").append(esc(DAY.format(uc.periodTo()))).append("</div>")
                .append("<div>Signed for the organisation</div><div>").append(esc(uc.signatoryName())).append(", ").append(esc(uc.signatoryRole()))
                .append(" (").append(esc(uc.ngoSignedAt() == null ? "" : STAMP.format(uc.ngoSignedAt()))).append(")</div></div></div>");
        h.append("<div class=card><h2>Statement of utilisation</h2><table><tr><th>Budget head</th><th class=n>Budget</th><th class=n>Utilised</th><th class=n>Entries</th></tr>")
                .append(heads).append("<tr class=t><td>Total expenditure in the period</td><td></td><td class=n>").append(esc(UcRules.inr(f.spent())))
                .append("</td><td class=n>").append(f.lines().size()).append("</td></tr></table><table style=\"margin-top:10px\">")
                .append("<tr><td>Utilised from the grant</td><td class=n>").append(esc(UcRules.inr(f.utilised()))).append("</td></tr>")
                .append("<tr><td>Unspent balance at the end of the period</td><td class=n>").append(esc(UcRules.inr(f.unspent()))).append("</td></tr>")
                .append(f.ownFunds().signum() > 0 ? "<tr><td>Met from the organisation's own funds</td><td class=n>" + esc(UcRules.inr(f.ownFunds())) + "</td></tr>" : "")
                .append("</table>").append(f.withoutBill() > 0 ? "<p class=warn>" + f.withoutBill() + " expense(s) have no bill attached in the books.</p>" : "")
                .append("<p class=stmt>").append(esc(UcRules.statement(ucs.ngoName(uc.ngoId()), uc.funderName(), uc.funderReference(), project,
                        uc.periodFrom(), uc.periodTo(), uc.grantReceivedOn(), f)))
                .append("</p><p><a class=btn href=\"").append(esc(base + "/pdf")).append("\">").append(certified ? "Download the certified PDF" : "Download the draft PDF")
                .append("</a></p></div>");
        h.append("<div class=card><h2>Expenses in the period (").append(f.lines().size()).append(")</h2><table><tr><th>Voucher</th><th>Date</th><th>Head</th>"
                + "<th>Description</th><th class=n>Amount</th><th>Bill</th></tr>").append(lines).append("</table></div>");
        if (!certified) {
            h.append("<div class=card><h2>Certify as chartered accountant</h2>");
            if (error != null) h.append("<div class=err>").append(esc(error)).append("</div>");
            h.append("<form method=post action=\"").append(esc(base + "/certify")).append("\">")
                    .append("<label for=name>Your name (as registered with ICAI)</label><input type=text id=name name=name required maxlength=120 value=\"").append(esc(v.get("name"))).append("\">")
                    .append("<div class=row><div><label for=m>ICAI membership number</label><input type=text id=m name=membershipNo required inputmode=numeric maxlength=6 value=\"").append(esc(v.get("membershipNo"))).append("\"></div>")
                    .append("<div><label for=u>UDIN</label><input type=text id=u name=udin required maxlength=18 value=\"").append(esc(v.get("udin"))).append("\"></div></div>")
                    .append("<div class=row><div><label for=f>Firm (optional)</label><input type=text id=f name=firm maxlength=160 value=\"").append(esc(v.get("firm"))).append("\"></div>")
                    .append("<div><label for=r>Firm registration no. (optional)</label><input type=text id=r name=firmRegNo maxlength=7 value=\"").append(esc(v.get("firmRegNo"))).append("\"></div></div>")
                    .append("<label for=p>Place</label><input type=text id=p name=place maxlength=80 value=\"").append(esc(v.get("place"))).append("\">")
                    .append("<div class=decl><input type=checkbox id=d name=declare value=yes required><label for=d style=\"margin:0;color:#14213d\">")
                    .append(esc(UcRules.CA_DECLARATION)).append("</label></div><button type=submit>Certify</button></form>")
                    .append("<p class=warn>Generate the UDIN for this certificate on udin.icai.org before certifying. This link works until ")
                    .append(esc(STAMP.format(uc.caLinkExpiresAt()))).append(" and only once.</p></div>");
        }
        h.append("<footer>Shared with you by the organisation through Yogdaan (yogdaan.co.in) - donations and accounts app for NGOs. "
                + "Please do not forward this link.</footer></main></body></html>");
        return h.toString();
    }

    private static String message(String title, String text) {
        return "<!doctype html><html lang=en><head><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">"
                + "<meta name=robots content=\"noindex,nofollow\"><title>" + esc(title) + " - Yogdaan</title>" + STYLE + "</head><body><main>"
                + "<div class=card><h1>" + esc(title) + "</h1><p>" + esc(text) + "</p></div>"
                + "<footer>Yogdaan (yogdaan.co.in) - donations and accounts app for NGOs.</footer></main></body></html>";
    }
}
