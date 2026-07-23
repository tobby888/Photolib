package cn.photolib.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.test.context.support.WithMockUser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

    @Test
    void savesScheduledIconRules() throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(image, "png", encoded);
        MockMultipartFile icon = new MockMultipartFile(
                "files", "icon.png", "image/png", encoded.toByteArray());
        MockMultipartFile rules = new MockMultipartFile(
                "rules", "rules.json", "application/json",
                "[{\"cronExpression\":\"0 0 0 1 10 *\",\"fileIndex\":0}]"
                        .getBytes(StandardCharsets.UTF_8));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        // Standalone MockMvc does not install WebRoutingConfig's /api/v1 prefix.
        mockMvc.perform(multipart("/branding/scheduled-icons")
                        .file(rules).file(icon).with(request -> {
                            request.setMethod("PUT");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].cronExpression").value("0 0 0 1 10 *"))
                .andExpect(jsonPath("$.data[0].iconUrl").isNotEmpty());
    }

    @Test
    void matchingScheduleOverridesOnlyTheDisplayedIcon() throws Exception {
        LocalDate today = LocalDate.now(ScheduledBrandIconService.SYSTEM_ZONE);
        String cron = "0 0 0 " + today.getDayOfMonth() + " " + today.getMonthValue() + " *";
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(image, "png", encoded);
        MockMultipartFile icon = new MockMultipartFile(
                "files", "today.png", "image/png", encoded.toByteArray());
        controller.replaceScheduledIcons(List.of(
                new BrandingController.ScheduledIconRuleRequest(null, cron, 0)
        ), List.of(icon));

        BrandingController.BrandingResponse response = controller.get().data();

        assertThat(response.iconType()).isEqualTo("builtin");
        assertThat(response.displayIconType()).isEqualTo("custom");
        assertThat(response.displayIconUrl()).contains("/branding/scheduled-icons/");
        assertThat(response.nextIconRefreshAt().toLocalDate()).isEqualTo(today.plusDays(1));
    }
}
