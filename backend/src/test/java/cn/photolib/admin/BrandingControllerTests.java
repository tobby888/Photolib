package cn.photolib.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.test.context.support.WithMockUser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class BrandingControllerTests {
    @Autowired private BrandingController controller;
    @Autowired private BrandingSettingMapper mapper;

    @Test
    void uploadReencodesImageAndStripsTrailingPayload() throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(image, "png", encoded);
        byte[] marker = "TRAILING-PAYLOAD".getBytes(StandardCharsets.UTF_8);
        encoded.write(marker);

        controller.uploadIcon(new MockMultipartFile(
                "file", "icon.png", "image/png", encoded.toByteArray()));

        byte[] stored = mapper.selectById(1).getCustomIcon();
        assertThat(ImageIO.read(new ByteArrayInputStream(stored))).isNotNull();
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("TRAILING-PAYLOAD");
        assertThat(stored.length).isLessThan(encoded.size());
    }
}
