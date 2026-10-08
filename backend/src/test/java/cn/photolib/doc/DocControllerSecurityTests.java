package cn.photolib.doc;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.doc.model.DocNodeEntity;
import cn.photolib.doc.model.DocNodeType;
import cn.photolib.doc.model.DocVisibility;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 接口层的权限边界。
 *
 * <p>文档中心的授权模型，这里逐条验：写内容要 {@code DOC_MANAGE}，发布与读者范围要
 * {@code DOC_PUBLISH}（V62 从前者拆出），编辑视角的读取两者任一；阅读接口谁都能调，
 * 能看到什么由 Service 按调用方的读者身份决定（见 {@link DocServiceTests}）。</p>
 */
@SpringBootTest
class DocControllerSecurityTests {

    @Autowired private DocController controller;
    @Autowired private DocReaderController readerController;
    @MockitoBean private DocService service;

    private final AuthenticatedUser principal = new AuthenticatedUser(
            1L, "doc-security", "文档安全测试", UserRole.CAMPUS_MANAGER, 1L, false);

    @Test
    @WithMockUser(authorities = "PHOTO_VIEW")
    void everyEditingEndpointRejectsCallersWithoutDocManage() {
        assertThatThrownBy(() -> controller.tree()).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.get(7L)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.create(
                new DocController.CreateRequest(null, DocNodeType.DOCUMENT, "标题"), principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.rename(
                7L, new DocController.RenameRequest("新名字", 1), principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.saveContent(
                7L, new DocController.ContentRequest("正文", 1), principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.setPublished(
                7L, new DocController.PublicationRequest(true, 1), principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.setVisibility(
                7L, new DocController.VisibilityRequest(DocVisibility.PUBLIC, null, null, 1), principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.move(
                7L, new DocController.MoveRequest(null, 0, 1), principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.delete(7L, 1, principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.uploadAsset(7L, null, principal))
                .isInstanceOf(AccessDeniedException.class);
        // PDF 的三个端点和其余编辑接口同一条规则：整个控制器要 DOC_MANAGE。
        // 预览接口尤其不能漏——它连草稿的 PDF 都给。
        assertThatThrownBy(() -> controller.createPdf(null, "标题", null, principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.replacePdf(7L, 1, null, principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.file(7L)).isInstanceOf(AccessDeniedException.class);

        // 方法安全必须在解引用参数、调用 Service 之前就拒绝。
        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(authorities = "DOC_MANAGE")
    void docManageWritesContentButNoLongerDecidesWhoReadsIt() {
        controller.tree();
        controller.get(7L);
        controller.create(new DocController.CreateRequest(null, DocNodeType.DOCUMENT, "标题"), principal);
        verify(service).tree();
        verify(service).get(7L);

        // 发布和读者范围从 DOC_MANAGE 里拆了出去（V62）：只会写的人不能把文档放上公网。
        assertThatThrownBy(() -> controller.setPublished(
                7L, new DocController.PublicationRequest(true, 1), principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.setVisibility(
                7L, new DocController.VisibilityRequest(DocVisibility.PUBLIC, null, null, 1), principal))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(authorities = "DOC_PUBLISH")
    void docPublishDecidesWhoReadsButCannotWriteContent() {
        controller.tree();
        controller.get(7L);
        controller.setVisibility(7L, new DocController.VisibilityRequest(
                DocVisibility.RESTRICTED, Set.of(3L), Set.of(5L), 3), principal);
        controller.setPublished(7L, new DocController.PublicationRequest(true, 4), principal);
        verify(service).tree();
        verify(service).get(7L);
        verify(service).setVisibility(7L, DocVisibility.RESTRICTED, Set.of(3L), Set.of(5L), 3, principal);
        verify(service).setPublished(7L, true, 4, principal);

        assertThatThrownBy(() -> controller.saveContent(
                7L, new DocController.ContentRequest("正文", 1), principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.delete(7L, 1, principal))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithAnonymousUser
    void readingNeedsNoLoginAndTellsTheServiceTheCallerIsAnonymous() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");

        when(service.readerPdf(anyString(), any())).thenReturn(pdfNode());
        when(service.openNode(any())).thenReturn(new ByteArrayInputStream(new byte[0]));

        readerController.tree(null, request);
        readerController.document("XK54YN0XKN1E657AHQZZ3TQDQ9", null, request);
        readerController.file("XK54YN0XKN1E657AHQZZ3TQDQ9", null, request);

        // principal 为 null 必须原样传成匿名读者——
        // 这是"仅成员文档不外泄"的唯一依据，PDF 直链也不例外。
        verify(service).readerTree(DocReader.ANONYMOUS);
        verify(service).readerDocument("XK54YN0XKN1E657AHQZZ3TQDQ9", DocReader.ANONYMOUS);
        verify(service).readerPdf("XK54YN0XKN1E657AHQZZ3TQDQ9", DocReader.ANONYMOUS);
    }

    @Test
    @WithMockUser(authorities = "PHOTO_VIEW")
    void aLoggedInReaderNeedsNoDocPermissionButIsReportedAsAuthenticated() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");

        when(service.readerPdf(anyString(), any())).thenReturn(pdfNode());
        when(service.openNode(any())).thenReturn(new ByteArrayInputStream(new byte[0]));

        readerController.tree(principal, request);
        readerController.document("XK54YN0XKN1E657AHQZZ3TQDQ9", principal, request);
        readerController.file("XK54YN0XKN1E657AHQZZ3TQDQ9", principal, request);

        // 读者身份带着账号和权限组往下传：指定读者的文档要靠它们判定。
        DocReader reader = DocReader.of(principal);
        verify(service).readerTree(reader);
        verify(service).readerDocument("XK54YN0XKN1E657AHQZZ3TQDQ9", reader);
        verify(service).readerPdf("XK54YN0XKN1E657AHQZZ3TQDQ9", reader);
    }

    private DocNodeEntity pdfNode() {
        DocNodeEntity node = new DocNodeEntity();
        node.setPublicId("XK54YN0XKN1E657AHQZZ3TQDQ9");
        node.setNodeType(DocNodeType.PDF);
        node.setTitle("入部须知");
        node.setContentSize(12L);
        return node;
    }
}
