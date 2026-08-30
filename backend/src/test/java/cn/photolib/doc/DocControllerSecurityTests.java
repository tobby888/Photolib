package cn.photolib.doc;

import cn.photolib.auth.AuthenticatedUser;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 接口层的权限边界。
 *
 * <p>文档中心的授权模型只有两条，这里各验一条：编辑接口一律要
 * {@code DOC_MANAGE}；阅读接口谁都能调，能看到什么由 Service 按
 * "调用方是否已登录"决定（见 {@link DocServiceTests}）。</p>
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
                7L, new DocController.VisibilityRequest(DocVisibility.PUBLIC, 1), principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.move(
                7L, new DocController.MoveRequest(null, 0, 1), principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.delete(7L, 1, principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.uploadAsset(7L, null, principal))
                .isInstanceOf(AccessDeniedException.class);

        // 方法安全必须在解引用参数、调用 Service 之前就拒绝。
        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(authorities = "DOC_MANAGE")
    void docManageOpensTheWholeEditingSurface() {
        controller.tree();
        controller.get(7L);
        controller.setVisibility(7L,
                new DocController.VisibilityRequest(DocVisibility.MEMBERS, 3), principal);
        verify(service).tree();
        verify(service).get(7L);
        verify(service).setVisibility(7L, DocVisibility.MEMBERS, 3, principal);
    }

    @Test
    @WithAnonymousUser
    void readingNeedsNoLoginAndTellsTheServiceTheCallerIsAnonymous() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");

        readerController.tree(null, request);
        readerController.document("XK54YN0XKN1E657AHQZZ3TQDQ9", null, request);

        // principal 为 null 必须原样传成 authenticated=false——
        // 这个布尔值是"仅成员文档不外泄"的唯一依据。
        verify(service).readerTree(false);
        verify(service).readerDocument("XK54YN0XKN1E657AHQZZ3TQDQ9", false);
    }

    @Test
    @WithMockUser(authorities = "PHOTO_VIEW")
    void aLoggedInReaderNeedsNoDocPermissionButIsReportedAsAuthenticated() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");

        readerController.tree(principal, request);
        readerController.document("XK54YN0XKN1E657AHQZZ3TQDQ9", principal, request);

        verify(service).readerTree(true);
        verify(service).readerDocument("XK54YN0XKN1E657AHQZZ3TQDQ9", true);
    }
}
