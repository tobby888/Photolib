package cn.photolib.photo;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PhotoControllerSecurityTests {

    @Autowired
    private PhotoController controller;

    @Test
    @WithMockUser(authorities = {"PROJECT_VIEW", "REQUEST_VIEW"})
    void favoriteEndpointsRequirePhotoViewAuthority() {
        AuthenticatedUser principal = new AuthenticatedUser(
                1L, "project-viewer", "项目查看者", UserRole.MINISTER, null, false);

        assertThatThrownBy(() -> controller.favorite(1L, principal))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.unfavorite(1L, principal))
                .isInstanceOf(AccessDeniedException.class);
    }
}
