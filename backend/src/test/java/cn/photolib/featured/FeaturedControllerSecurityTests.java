package cn.photolib.featured;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 接口层的权限边界。
 *
 * <p>这里验证的是注解本身：部长动作必须有 {@code FEATURED_MANAGE}，而查看、下载和
 * 填报只要求登录——填报还要看指派与时间窗口，那部分由 Service 判定，见
 * {@link FeaturedCollectionServiceTests}。</p>
 */
@SpringBootTest
class FeaturedControllerSecurityTests {

    @Autowired
    private FeaturedCollectionController controller;
    @MockitoBean
    private FeaturedCollectionService service;

    private final AuthenticatedUser principal = new AuthenticatedUser(
            1L, "featured-security", "精选安全测试", UserRole.CAMPUS_MANAGER, 1L, false);

    @Test
    @WithMockUser(authorities = "PHOTO_VIEW")
    void managementEndpointsRejectCallersWithoutFeaturedManage() {
        assertThatThrownBy(() -> controller.create(null, principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.update(7L, null, principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.publish(7L, null, principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.close(7L, principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.regenerate(7L, principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.delete(7L, 1, principal))
                .isInstanceOf(AccessDeniedException.class);

        // 方法安全必须在参数解引用和调用 Service 之前就拒绝。
        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(authorities = "PHOTO_VIEW")
    void viewingDownloadingAndSubmittingOnlyRequireAnAuthenticatedSession() {
        // 查看和下载不设限：一个只有图库权限的账号也能读。
        controller.list(1, 20, null, null, principal);
        controller.get(7L, principal);
        controller.entries(7L, principal);
        controller.document(7L);
        verify(service).list(eq(1), eq(20), eq(null), eq(null), same(principal));
        verify(service).get(7L, principal);
        verify(service).entries(7L, principal);
        verify(service).document(7L);

        // 填报接口同样只要求登录，是否真的能写由 Service 按指派关系判断。
        var entry = new FeaturedCollectionController.EntryRequest(3L, "思路", "地点");
        controller.addEntry(7L, entry, principal);
        controller.deleteEntry(7L, 9L, principal);
        verify(service).addEntry(eq(7L), any(), same(principal));
        verify(service).deleteEntry(7L, 9L, principal);
    }

    @Test
    @WithMockUser(authorities = "FEATURED_MANAGE")
    void featuredManageAuthorityReachesTheManagementEndpoints() {
        var create = new FeaturedCollectionController.CollectionRequest(
                "标题", "<p>要求</p>", LocalDateTime.now(), LocalDateTime.now().plusDays(1),
                true, 10, List.of(), List.of());
        controller.create(create, principal);
        controller.publish(7L, new FeaturedCollectionController.VersionRequest(1), principal);
        controller.close(7L, principal);
        controller.regenerate(7L, principal);
        controller.delete(7L, 1, principal);

        verify(service).create(any(), same(principal));
        verify(service).publish(eq(7L), eq(1), same(principal));
        verify(service).close(7L, principal);
        verify(service).regenerateDocument(7L, principal);
        verify(service).delete(anyLong(), eq(1), same(principal));
    }
}
