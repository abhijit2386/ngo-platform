package org.ngoplatform.campaign.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class CoverImageTest {
    static byte[] png(int w, int h) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), "png", out);
        return out.toByteArray();
    }

    static BufferedImage read(byte[] b) throws Exception { return ImageIO.read(new ByteArrayInputStream(b)); }

    @Test
    void portraitPhotoIsCroppedTo16by9() throws Exception {
        var r = CoverImage.process(png(800, 1200));
        assertThat(read(r.large()).getWidth()).isEqualTo(1200);
        assertThat(read(r.large()).getHeight()).isEqualTo(675);
        assertThat(read(r.small()).getWidth()).isEqualTo(480);
        assertThat(read(r.small()).getHeight()).isEqualTo(270);
    }

    @Test
    void outputIsJpegWithoutMetadataMarkers() throws Exception {
        byte[] large = CoverImage.process(png(1600, 900)).large();
        assertThat(large[0] & 0xff).isEqualTo(0xFF);
        assertThat(large[1] & 0xff).isEqualTo(0xD8);
        assertThat(new String(large, StandardCharsets.ISO_8859_1)).doesNotContain("Exif");
    }

    @Test
    void tooSmallIsRejected() throws Exception {
        byte[] small = png(300, 200);
        assertThat(CampaignRulesTest.code(() -> CoverImage.process(small))).isEqualTo("IMAGE_TOO_SMALL");
    }

    @Test
    void garbageIsRejected() {
        assertThat(CampaignRulesTest.code(() -> CoverImage.process("not an image".getBytes()))).isEqualTo("IMAGE_UNREADABLE");
    }
}
