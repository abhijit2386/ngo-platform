package org.ngoplatform.campaign.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import org.ngoplatform.campaign.app.CampaignService;
import org.ngoplatform.campaign.domain.Campaign;
import org.ngoplatform.campaign.domain.Repos;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * CAMP-010 share link: a small public web page per live (or closed) campaign. Facebook, LinkedIn, WhatsApp and X read
 * its Open Graph / Twitter tags to show a rich preview (photo, title, summary). Everything printed is HTML-escaped.
 * The URL base is ngo.public-web-url (the product domain in production; the gateway locally).
 */
@RestController
public class SharePageController {
    /**
     * The API-wide policy (default-src 'none') would block this page's inline styles and photo. This page needs only
     * those: still no scripts, no frames, no forms.
     */
    static final String PAGE_CSP = "default-src 'none'; img-src 'self' https: data:; style-src 'unsafe-inline'; "
            + "base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final Repos.Campaigns campaigns;
    private final CampaignService svc;
    private final String base;

    public SharePageController(Repos.Campaigns campaigns, CampaignService svc,
                               @Value("${ngo.public-web-url:http://localhost:8080}") String base) {
        this.campaigns = campaigns; this.svc = svc; this.base = base.replaceAll("/+$", "");
    }

    @GetMapping(value = "/c/{id}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(@PathVariable String id) {
        UUID uuid;
        try { uuid = UUID.fromString(id); } catch (IllegalArgumentException e) { return notFound(); }
        Campaign c = campaigns.findById(uuid).filter(CampaignController::isPublic).orElse(null);
        if (c == null) return notFound();
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(10)).cachePublic())
                .header("Content-Security-Policy", PAGE_CSP)
                .contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8)).body(render(c));
    }

    String render(Campaign c) {
        String url = base + "/c/" + c.getId();
        String image = c.getImageKey() == null ? null : base + "/api/v1/public/campaigns/" + c.getId() + "/image?size=large&v=" + c.getImageVersion();
        String title = e(c.getTitle());
        String ngo = e(c.getNgoName() == null ? "" : c.getNgoName());
        String summary = e(c.getSummary());
        boolean ended = c.getStatus() != Campaign.Status.PUBLISHED || (c.getEndDate() != null && svc.today().isAfter(c.getEndDate()));
        int pct = c.getGoalAmount().signum() == 0 ? 0
                : c.getRaisedAmount().multiply(BigDecimal.valueOf(100)).divide(c.getGoalAmount(), 0, RoundingMode.DOWN).min(BigDecimal.valueOf(100)).intValue();
        StringBuilder story = new StringBuilder();
        if (c.getStory() != null) {
            for (String para : c.getStory().split("\\R\\s*\\R")) story.append("<p>").append(e(para).replaceAll("\\R", "<br>")).append("</p>");
        }
        String status = ended ? "This campaign has ended. Thank you to everyone who supported it."
                : (c.getEndDate() == null ? "" : "Open until " + DATE.format(c.getEndDate()) + ".");
        return """
                <!doctype html>
                <html lang="en"><head>
                <meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%1$s | Seva Setu</title>
                <meta name="description" content="%3$s">
                <link rel="canonical" href="%4$s">
                <meta property="og:type" content="website">
                <meta property="og:site_name" content="Seva Setu">
                <meta property="og:title" content="%1$s">
                <meta property="og:description" content="%3$s · by %2$s">
                <meta property="og:url" content="%4$s">
                %5$s
                <meta name="twitter:card" content="%6$s">
                <meta name="twitter:title" content="%1$s">
                <meta name="twitter:description" content="%3$s">
                <style>
                body{margin:0;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:#FAFBF7;color:#14213D}
                main{max-width:640px;margin:0 auto;padding:16px}
                img{width:100%%;aspect-ratio:16/9;object-fit:cover;border-radius:16px;background:#E6F3EC}
                h1{font-size:24px;margin:16px 0 4px}.ngo{color:#087A4B;font-weight:600;margin:0 0 12px}
                .bar{height:10px;border-radius:8px;background:#E3E7DF;overflow:hidden}.bar i{display:block;height:100%%;background:#087A4B}
                .num{display:flex;justify-content:space-between;margin:8px 0 2px;font-weight:700}.muted{color:#5B6478}
                .card{background:#fff;border:1px solid #E3E7DF;border-radius:16px;padding:16px;margin:16px 0}
                .cta{display:block;text-align:center;background:#087A4B;color:#fff;padding:14px;border-radius:12px;text-decoration:none;font-weight:600}
                footer{font-size:13px;margin:24px 0}
                </style></head>
                <body><main>
                %7$s
                <h1>%1$s</h1><p class="ngo">by %2$s</p>
                <div class="card"><div class="bar"><i style="width:%8$d%%"></i></div>
                <div class="num"><span>%9$s raised</span><span>%8$d%%</span></div>
                <div class="muted">of %10$s goal · %11$d donations</div></div>
                <p><strong>%3$s</strong></p>
                %12$s
                <p class="muted">%13$s</p>
                <p class="cta">%14$s</p>
                <footer class="muted">Seva Setu connects donors with verified NGOs in India.</footer>
                </main></body></html>
                """.formatted(title, ngo, summary, e(url),
                image == null ? "" : "<meta property=\"og:image\" content=\"" + e(image) + "\">\n<meta property=\"og:image:width\" content=\"1200\">\n<meta property=\"og:image:height\" content=\"675\">\n<meta name=\"twitter:image\" content=\"" + e(image) + "\">",
                image == null ? "summary" : "summary_large_image",
                image == null ? "" : "<img src=\"" + e(image) + "\" alt=\"" + title + "\">",
                pct, inr(c.getRaisedAmount()), inr(c.getGoalAmount()), c.getDonationCount(), story, e(status),
                ended ? "Find more causes on the Seva Setu app" : "Support this campaign on the Seva Setu app");
    }

    private static String inr(BigDecimal v) {
        NumberFormat f = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"));
        f.setMaximumFractionDigits(v.stripTrailingZeros().scale() > 0 ? 2 : 0);
        return f.format(v);
    }

    private static String e(String s) { return s == null ? "" : HtmlUtils.htmlEscape(s, "UTF-8"); }

    private static ResponseEntity<String> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
                .body("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">"
                        + "<title>Seva Setu</title></head><body style=\"font-family:sans-serif;padding:24px\">"
                        + "<h1>Campaign not available</h1><p>This campaign is not public or no longer exists.</p></body></html>");
    }
}
