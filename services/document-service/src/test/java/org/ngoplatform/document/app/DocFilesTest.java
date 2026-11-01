package org.ngoplatform.document.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class DocFilesTest {
    static byte[] zip(String entry) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("[Content_Types].xml"));
            z.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            z.putNextEntry(new ZipEntry(entry));
            z.write("<x/>".getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    @Test
    void recognisesTypesByContent() throws Exception {
        assertThat(DocFiles.check("%PDF-1.7 ...".getBytes(StandardCharsets.US_ASCII), "a.exe", 1 << 20).fileType()).isEqualTo("PDF");
        assertThat(DocFiles.check(zip("word/document.xml"), "minutes.docx", 1 << 20).fileType()).isEqualTo("WORD");
        assertThat(DocFiles.check(zip("xl/workbook.xml"), "budget.xlsx", 1 << 20).extension()).isEqualTo("xlsx");
        assertThat(DocFiles.check(zip("ppt/presentation.xml"), "deck.pptx", 1 << 20).fileType()).isEqualTo("SLIDES");
        byte[] ole = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0, 0};
        assertThat(DocFiles.check(ole, "old.xls", 1 << 20).fileType()).isEqualTo("EXCEL");
        assertThat(DocumentRulesTest.code(() -> DocFiles.check(ole, "old.bin", 1 << 20))).isEqualTo("FILE_TYPE_NOT_ALLOWED");
        byte[] exeZip = zip("payload.exe");
        assertThat(DocumentRulesTest.code(() -> DocFiles.check(exeZip, "x.docx", 1 << 20))).isEqualTo("FILE_TYPE_NOT_ALLOWED");
        assertThat(DocumentRulesTest.code(() -> DocFiles.check("MZ\u0090".getBytes(StandardCharsets.ISO_8859_1), "x.pdf", 1 << 20))).isEqualTo("FILE_TYPE_NOT_ALLOWED");
        assertThat(DocumentRulesTest.code(() -> DocFiles.check(new byte[2_000_000], "x.pdf", 1 << 20))).isEqualTo("FILE_TOO_LARGE");
    }

    @Test
    void photosAreReEncoded() throws Exception {
        var img = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpeg", out);
        var c = DocFiles.check(out.toByteArray(), "IMG_0001.JPG", 1 << 20);
        assertThat(c.fileType()).isEqualTo("IMAGE");
        assertThat(c.contentType()).isEqualTo("image/jpeg");
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(c.bytes())).getWidth()).isEqualTo(40);
    }

    @Test
    void downloadNamesAreSafe() {
        assertThat(DocFiles.downloadName("Board minutes: 2026/27 \"final\"", "ngos/x/library/2026/11/abc.pdf")).isEqualTo("Board minutes 2026 27 final.pdf");
    }
}
