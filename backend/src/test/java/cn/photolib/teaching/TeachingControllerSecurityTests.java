package cn.photolib.teaching;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 接口层的权限边界。
 *
 * <p>教学资料有两条授权规则，这里各验一条：管理面（{@code TeachingManageController}）
 * 一律要 {@code TEACHING_MANAGE}；读者面（{@code TeachingReaderController}）要
 * {@code PHOTO_VIEW}。少一条就是把内容从另一个门放了出去。</p>
 */
@SpringBootTest
class TeachingControllerSecurityTests {

    @Autowired private TeachingReaderController readerController;
    @Autowired private TeachingManageController manageController;
    @MockitoBean private TeachingService service;

    private final AuthenticatedUser principal = new AuthenticatedUser(
            1L, "teaching-security", "教学安全测试", UserRole.MINISTER, null, false);

    @Test
    @WithMockUser(authorities = "PHOTO_VIEW")
    void everyManagementEndpointRejectsCallersWithoutTeachingManage() {
        assertThatThrownBy(() -> manageController.authors())
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> manageController.create(
                "标题", null, "分类", null, null, principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> manageController.replaceFile(7L, 1, null, principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> manageController.update(7L,
                new TeachingManageController.UpdateRequest("标题", null, "分类", null, 1), principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> manageController.delete(7L, 1, principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> manageController.renameCategory(
                new TeachingManageController.CategoryRenameRequest("旧", "新")))
                .isInstanceOf(AccessDeniedException.class);

        // 方法安全必须在读参数、调 Service 之前就拒绝。
        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(authorities = "TEACHING_MANAGE")
    void teachingManageOpensTheWholeManagementSurface() {
        manageController.authors();
        manageController.delete(7L, 3, principal);
        manageController.renameCategory(new TeachingManageController.CategoryRenameRequest("旧", "新"));

        verify(service).authorOptions();
        verify(service).delete(7L, 3, principal);
        verify(service).renameCategory("旧", "新");
    }

    @Test
    @WithMockUser(authorities = "PHOTO_VIEW")
    void theReaderSurfaceNeedsOnlyGalleryAccess() {
        readerController.list(null, null);
        readerController.categories();
        readerController.get("XK54YN0XKN1E657AHQZZ3TQDQ9");

        verify(service).list(null, null);
        verify(service).categories();
        verify(service).get("XK54YN0XKN1E657AHQZZ3TQDQ9");
    }

    @Test
    @WithMockUser(authorities = "TEACHING_MANAGE")
    void teachingManageAloneDoesNotOpenTheReaderSurface() {
        // 管理权限不等于图库访问权限：只持 TEACHING_MANAGE 的账号读不到资料。
        assertThatThrownBy(() -> readerController.list(null, null))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(service);
    }

    @Test
    @WithAnonymousUser
    void anonymousCallersAreRejectedOnBothSurfaces() {
        assertThatThrownBy(() -> readerController.list(null, null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> manageController.delete(7L, 1, principal))
                .isInstanceOf(AccessDeniedException.class);
    }
}
