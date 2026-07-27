package cn.photolib.user;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserAvatarValidatorTests {
    private final UserAvatarValidator validator = new UserAvatarValidator();

    @Test
    void acceptsAndNormalizesJpeg() throws Exception {
        MockMultipartFile file = image("avatar.jpg", "image/jpeg", 320, 240, "jpeg");

        UserAvatarValidator.ValidatedAvatar result = validator.validate(file);

        assertThat(result.contentType()).isEqualTo("image/jpeg");
        assertThat(result.extension()).isEqualTo("jpg");
        assertThat(result.width()).isEqualTo(320);
        assertThat(result.height()).isEqualTo(240);
        assertThat(result.bytes().length).isLessThanOrEqualTo((int) UserAvatarValidator.MAX_BYTES);
        assertThat(ImageIO.read(new ByteArrayInputStream(result.bytes()))).isNotNull();
    }

    @Test
    void acceptsPngAndPreservesTransparency() throws Exception {
        BufferedImage source = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(1, 1, new Color(20, 40, 60, 80).getRGB());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(source, "png", output);

        UserAvatarValidator.ValidatedAvatar result = validator.validate(new MockMultipartFile(
                "file", "avatar.png", "image/png", output.toByteArray()));

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result.bytes()));
        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(decoded.getColorModel().hasAlpha()).isTrue();
    }

    @Test
    void rejectsImageWhoseDimensionsExceedLimit() throws Exception {
        MockMultipartFile file = image("wide.png", "image/png", 1025, 1, "png");

        assertThatThrownBy(() -> validator.validate(file))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(exception.getMessage()).contains("1024");
                });
    }

    @Test
    void rejectsSpoofedAndUnsupportedImages() {
        MockMultipartFile spoofed = new MockMultipartFile("file", "fake.png", "image/png",
                "not an image".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile webp = new MockMultipartFile("file", "avatar.webp", "image/webp",
                new byte[]{'R', 'I', 'F', 'F'});

        assertThatThrownBy(() -> validator.validate(spoofed))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE));
        assertThatThrownBy(() -> validator.validate(webp))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.UNSUPPORTED_FILE_TYPE));
    }

    @Test
    void rejectsFilesLargerThanOneMegabyte() {
        MockMultipartFile file = new MockMultipartFile("file", "large.jpg", "image/jpeg",
                new byte[(int) UserAvatarValidator.MAX_BYTES + 1]);

        assertThatThrownBy(() -> validator.validate(file))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.FILE_TOO_LARGE));
    }

    private MockMultipartFile image(String name, String contentType,
                                    int width, int height, String format) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(38, 103, 178));
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return new MockMultipartFile("file", name, contentType, output.toByteArray());
    }
}
