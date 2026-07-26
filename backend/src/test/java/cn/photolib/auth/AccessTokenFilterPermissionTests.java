package cn.photolib.auth;

import cn.photolib.permission.DataScope;
import cn.photolib.permission.PermissionCode;
import cn.photolib.user.model.UserRole;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccessTokenFilterPermissionTests {
    @Test
    void noAccessAccountIsBlockedFromSystemApisWithAdministratorGuidance() throws Exception {
        AuthService auth = mock(AuthService.class);
        AccessTokenFilter filter = new AccessTokenFilter(auth);
        var user = noAccessUser();
        when(auth.authenticate("token")).thenReturn(new AuthService.SessionAuthentication(88L, user));
        MockHttpServletRequest request = request("/api/v1/projects");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("尚未分配可用权限组");
        verify(chain, never()).doFilter(request, response);
        verify(auth, never()).touch(88L);
    }

    @Test
    void noAccessAccountCanStillLoadIdentityAndChangeInitialPassword() throws Exception {
        AuthService auth = mock(AuthService.class);
        AccessTokenFilter filter = new AccessTokenFilter(auth);
        var user = noAccessUser();
        when(auth.authenticate("token")).thenReturn(new AuthService.SessionAuthentication(89L, user));

        for (String path : Set.of("/api/v1/auth/me", "/api/v1/auth/initial-password",
                "/api/v1/auth/password", "/api/v1/auth/logout")) {
            MockHttpServletRequest request = request(path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilterInternal(request, response, chain);

            verify(chain).doFilter(request, response);
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void campusScopedAccountWithoutAnyCampusFailsClosed() throws Exception {
        AuthService auth = mock(AuthService.class);
        AccessTokenFilter filter = new AccessTokenFilter(auth);
        var user = new AuthenticatedUser(9L, "empty-campus", "未分配校区", UserRole.CAMPUS_MANAGER,
                null, false, 9L, "EMPTY_CAMPUS", "未分配校区",
                DataScope.CAMPUS, Set.of(PermissionCode.PHOTO_UPLOAD), Set.of());
        when(auth.authenticate("token")).thenReturn(new AuthService.SessionAuthentication(90L, user));
        MockHttpServletRequest request = request("/api/v1/photos/upload-tickets");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("尚未分配可用权限组");
        verify(chain, never()).doFilter(request, response);
        verify(auth, never()).touch(90L);
    }

    private MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        request.addHeader("Authorization", "Bearer token");
        return request;
    }

    private AuthenticatedUser noAccessUser() {
        return new AuthenticatedUser(7L, "demoted", "待重新授权", UserRole.CAMPUS_MANAGER,
                null, false, 4L, "NO_ACCESS", "待分配权限", DataScope.NONE, Set.of(), Set.of());
    }
}
