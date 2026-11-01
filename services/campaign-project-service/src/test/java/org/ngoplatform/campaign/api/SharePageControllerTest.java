package org.ngoplatform.campaign.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.ngoplatform.campaign.app.CampaignService;
import org.ngoplatform.campaign.domain.Campaign;
import org.ngoplatform.campaign.domain.Repos;

class SharePageControllerTest {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 29);

    private final CampaignService svc = mock(CampaignService.class);
    private final SharePageController page = new SharePageController(mock(Repos.Campaigns.class), svc, "https://yogdaan.co.in/");

    private Campaign live(String title, String story) {
        Campaign c = new Campaign(UUID.randomUUID(), UUID.randomUUID());
        c.edit(title, "slug", "Books & bags for <200> kids", story, "EDUCATION", new BigDecimal("50000.00"),
                TODAY.minusDays(3), TODAY.plusDays(30), null);
        c.publish(UUID.randomUUID(), "Asha Seva", "Pune");
        c.addDonation(new BigDecimal("12500.00"));
        return c;
    }

    @Test
    void hasLinkPreviewTagsAndProgress() {
        when(svc.today()).thenReturn(TODAY);
        Campaign c = live("School books", null);
        c.setImageKey("ngos/x/campaigns/y/photo-1");
        String html = page.render(c);
        assertThat(html).contains("<meta property=\"og:title\" content=\"School books\">")
                .contains("<meta property=\"og:url\" content=\"https://yogdaan.co.in/c/" + c.getId() + "\">")
                .contains("og:image\" content=\"https://yogdaan.co.in/api/v1/public/campaigns/" + c.getId() + "/image?size=large")
                .contains("summary_large_image")
                .contains("by Asha Seva")
                .contains("25%")
                .contains("₹12,500");
    }

    @Test
    void escapesEverythingUsersWrote() {
        when(svc.today()).thenReturn(TODAY);
        String html = page.render(live("<script>alert(1)</script> \"x\"", "Line <b>one</b>\n\nSecond para"));
        assertThat(html).doesNotContain("<script>").doesNotContain("<b>one</b>").doesNotContain("<200>")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt; &quot;x&quot;")
                .contains("<p>Line &lt;b&gt;one&lt;/b&gt;</p><p>Second para</p>")
                .contains("Books &amp; bags for &lt;200&gt; kids");
    }

    @Test
    void withoutPhotoUsesSmallCardAndNoImageTag() {
        when(svc.today()).thenReturn(TODAY);
        String html = page.render(live("T", null));
        assertThat(html).doesNotContain("og:image").contains("content=\"summary\"");
    }

    @Test
    void closedCampaignSaysEnded() {
        when(svc.today()).thenReturn(TODAY);
        Campaign c = live("T", null);
        c.close();
        assertThat(page.render(c)).contains("This campaign has ended");
    }
}
