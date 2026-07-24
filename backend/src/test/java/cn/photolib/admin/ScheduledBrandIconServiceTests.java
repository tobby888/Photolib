package cn.photolib.admin;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class ScheduledBrandIconServiceTests {
    @Autowired private ScheduledBrandIconService service;
    @Autowired private ScheduledBrandIconMapper mapper;

    @Test
    void storesNonConflictingRulesAndResolvesTheMatchingDate() throws Exception {
        List<ScheduledBrandIconService.ScheduledIconView> saved = service.replace(List.of(
                new ScheduledBrandIconService.RuleInput(null, "0 0 0 1 10 *", 0),
                new ScheduledBrandIconService.RuleInput(null, "0 0 0 25 12 *", 1)
        ), List.of(icon("national-day.png", Color.RED), icon("christmas.png", Color.GREEN)));

        assertThat(saved).hasSize(2);
        assertThat(service.findActive(LocalDate.of(2032, 10, 1)).getId())
                .isEqualTo(Long.parseLong(saved.get(0).id()));
        assertThat(service.findActive(LocalDate.of(2032, 12, 25)).getId())
                .isEqualTo(Long.parseLong(saved.get(1).id()));
        assertThat(service.findActive(LocalDate.of(2032, 7, 23))).isNull();
    }

    @Test
    void rejectsRulesThatCanMatchOnTheSameCalendarDate() throws Exception {
        assertThatThrownBy(() -> service.replace(List.of(
                new ScheduledBrandIconService.RuleInput(null, "0 0 0 * * *", 0),
                new ScheduledBrandIconService.RuleInput(null, "0 30 12 1 10 *", 1)
        ), List.of(icon("daily.png", Color.BLUE), icon("holiday.png", Color.YELLOW))))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.RESOURCE_STATE_CONFLICT);
                    assertThat(exception.getMessage()).contains("规则 1、2", "设置失败");
                });

        assertThat(mapper.selectCount(null)).isZero();
    }

    @Test
    void rejectsInvalidCronBeforeWritingAnything() throws Exception {
        assertThatThrownBy(() -> service.replace(List.of(
                new ScheduledBrandIconService.RuleInput(null, "not-a-cron", 0)
        ), List.of(icon("invalid.png", Color.BLACK))))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(exception.getMessage()).contains("第 1 条 Cron 表达式无效");
                });

        assertThat(mapper.selectCount(null)).isZero();
    }

    @Test
    void keepsAnExistingImageWhenOnlyItsCronChanges() throws Exception {
        ScheduledBrandIconService.ScheduledIconView saved = service.replace(List.of(
                new ScheduledBrandIconService.RuleInput(null, "0 0 0 1 10 *", 0)
        ), List.of(icon("original.png", Color.MAGENTA))).getFirst();
        byte[] original = service.getIcon(Long.parseLong(saved.id())).getIcon();

        service.replace(List.of(
                new ScheduledBrandIconService.RuleInput(saved.id(), "0 0 0 2 10 *", null)
        ), List.of());

        ScheduledBrandIconEntity updated = service.getIcon(Long.parseLong(saved.id()));
        assertThat(updated.getCronExpression()).isEqualTo("0 0 0 2 10 *");
        assertThat(updated.getIcon()).isEqualTo(original);
    }

    private MockMultipartFile icon(String name, Color color) throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, color.getRGB());
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(image, "png", encoded);
        return new MockMultipartFile("files", name, "image/png", encoded.toByteArray());
    }
}
