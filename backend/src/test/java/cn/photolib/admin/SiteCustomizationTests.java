package cn.photolib.admin;

import cn.photolib.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class SiteCustomizationTests {
    @Autowired private BrandingController controller;
    @Autowired private PlaceholderImageMapper placeholderImageMapper;

    @Test
    void savesLoginCopyAndFooterAndReadsThemBack() {
        controller.updateSite(new BrandingController.SiteContentRequest(
                "  第一行\n第二行  ", " 副标题 ", List.of(" 项目协作 ", "", "素材管理"),
                "底部提示", "© 2026 摄影部",
                List.of(new BrandingController.FooterLinkRequest(" 使用指南 ", " https://example.com/guide "))));

        BrandingController.BrandingResponse response = controller.get().data();

        // 前后空白要去掉，但管理员写的换行是排版的一部分，必须留着。
        assertThat(response.loginHeadline()).isEqualTo("第一行\n第二行");
        assertThat(response.loginSubheadline()).isEqualTo("副标题");
        assertThat(response.loginHighlights()).containsExactly("项目协作", "素材管理");
        assertThat(response.loginNotice()).isEqualTo("底部提示");
        assertThat(response.footerText()).isEqualTo("© 2026 摄影部");
        assertThat(response.footerLinks()).containsExactly(
                new BrandingController.FooterLink("使用指南", "https://example.com/guide"));
    }

    @Test
    void siteContentDoesNotDisturbTheBrandIdentity() {
        BrandingController.BrandingResponse before = controller.get().data();

        controller.updateSite(new BrandingController.SiteContentRequest(
                "标题", "副标题", List.of(), "", "页脚", List.of()));

        BrandingController.BrandingResponse after = controller.get().data();
        assertThat(after.title()).isEqualTo(before.title());
        assertThat(after.slogan()).isEqualTo(before.slogan());
        assertThat(after.builtinIcon()).isEqualTo(before.builtinIcon());
    }

    @Test
    void rejectsFooterLinksWithAPseudoProtocol() {
        assertThatThrownBy(() -> controller.updateSite(new BrandingController.SiteContentRequest(
                "", "", List.of(), "", "",
                List.of(new BrandingController.FooterLinkRequest("点我", "javascript:alert(1)")))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只支持");

        // 校验失败不能留下半份配置。
        assertThat(controller.get().data().footerLinks()).isEmpty();
    }

    @Test
    void rejectsFooterLinkMissingItsLabelOrAddress() {
        assertThatThrownBy(() -> controller.updateSite(new BrandingController.SiteContentRequest(
                "", "", List.of(), "", "",
                List.of(new BrandingController.FooterLinkRequest("只有名称", "")))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void placeholderImagesAreListedOnTheBrandingResponse() throws IOException {
        controller.uploadPlaceholderImages(List.of(placeholder("one.png"), placeholder("two.png")));

        List<String> urls = controller.get().data().placeholderImageUrls();

        assertThat(urls).hasSize(2);
        assertThat(urls).allMatch(url -> url.startsWith("/api/v1/branding/placeholder-images/"));
        // 地址带版本参数，换图之后浏览器不会继续吃旧缓存。
        assertThat(urls).allMatch(url -> url.contains("?v="));
    }

    @Test
    void placeholderImagesAreReencodedAndCanBeDeleted() throws IOException {
        List<PlaceholderImageService.PlaceholderImageView> stored =
                controller.uploadPlaceholderImages(List.of(placeholder("keep.png"), placeholder("drop.png"))).data();
        assertThat(stored).hasSize(2);
        long id = Long.parseLong(stored.getLast().id());
        assertThat(placeholderImageMapper.selectById(id).getImage()).isNotEmpty();

        List<PlaceholderImageService.PlaceholderImageView> remaining = controller.deletePlaceholderImage(id).data();

        assertThat(remaining).hasSize(1);
        assertThat(remaining.getFirst().fileName()).isEqualTo("keep.png");
        assertThat(placeholderImageMapper.selectById(id)).isNull();
    }

    @Test
    void rejectsAPlaceholderThatIsNotAnImage() {
        assertThatThrownBy(() -> controller.uploadPlaceholderImages(List.of(new MockMultipartFile(
                "files", "note.txt", "text/plain", "not an image".getBytes()))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PNG");
    }

    @Test
    void rejectsAnEmptyUploadInsteadOfStoringNothingQuietly() {
        assertThatThrownBy(() -> controller.uploadPlaceholderImages(List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请选择");
    }

    @Test
    void oneBadFileInABatchLeavesNothingBehind() throws IOException {
        assertThatThrownBy(() -> controller.uploadPlaceholderImages(List.of(
                placeholder("good.png"),
                new MockMultipartFile("files", "bad.txt", "text/plain", "nope".getBytes()))))
                .isInstanceOf(BusinessException.class);

        assertThat(controller.get().data().placeholderImageUrls()).isEmpty();
    }

    @Test
    void placeholderAtItsCurrentVersionedUrlIsCachedLongTerm() throws IOException {
        var stored = controller.uploadPlaceholderImages(List.of(placeholder("cached.png"))).data().getFirst();
        long id = Long.parseLong(stored.id());
        String version = stored.imageUrl().substring(stored.imageUrl().indexOf("?v=") + 3);

        var response = controller.placeholderImage(id, version, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotEmpty();
        assertThat(response.getHeaders().getCacheControl())
                .contains("max-age=31536000").contains("public").contains("immutable");
        assertThat(response.getHeaders().getETag()).isEqualTo("\"" + id + "-" + version + "\"");
    }

    @Test
    void placeholderRequestedWithAStaleVersionIsNotPinnedInTheBrowserCache() throws IOException {
        var stored = controller.uploadPlaceholderImages(List.of(placeholder("stale.png"))).data().getFirst();

        var response = controller.placeholderImage(Long.parseLong(stored.id()), "not-the-version", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-cache");
    }

    @Test
    void conditionalPlaceholderRequestReturnsNotModifiedWithoutABody() throws IOException {
        var stored = controller.uploadPlaceholderImages(List.of(placeholder("etag.png"))).data().getFirst();
        long id = Long.parseLong(stored.id());
        String version = stored.imageUrl().substring(stored.imageUrl().indexOf("?v=") + 3);
        String etag = controller.placeholderImage(id, version, null).getHeaders().getETag();

        var notModified = controller.placeholderImage(id, version, "W/\"other\", " + etag);
        var changed = controller.placeholderImage(id, version, "\"" + id + "-old\"");

        assertThat(notModified.getStatusCode().value()).isEqualTo(304);
        assertThat(notModified.getBody()).isNull();
        assertThat(changed.getStatusCode().value()).isEqualTo(200);
        assertThat(controller.placeholderImage(Long.MAX_VALUE, version, null).getStatusCode().value())
                .isEqualTo(404);
    }

    private MockMultipartFile placeholder(String name) throws IOException {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(image, "png", encoded);
        return new MockMultipartFile("files", name, "image/png", encoded.toByteArray());
    }
}
