package cn.photolib.auth;

import cn.photolib.permission.DataScope;
import cn.photolib.permission.PermissionCode;
import cn.photolib.user.model.UserRole;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
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
    void restrictedSessionsStillReadTheAdministratorBranding() throws Exception {
        AuthService auth = mock(AuthService.class);
        AccessTokenFilter filter = new AccessTokenFilter(auth);
        var noAccess = noAccessUser();
        var mustChangePassword = new AuthenticatedUser(8L, "fresh", "新同学", UserRole.CAMPUS_MANAGER,
                null, true, 4L, "CAMPUS_MANAGER", "校区负责人",
                DataScope.CAMPUS, Set.of(PermissionCode.PHOTO_UPLOAD), Set.of(1L));

        for (AuthenticatedUser user : List.of(noAccess, mustChangePassword)) {
            when(auth.authenticate("token")).thenReturn(new AuthService.SessionAuthentication(91L, user));
            for (String path : List.of("/api/v1/branding", "/api/v1/branding/icon")) {
                MockHttpServletRequest request = request(path);
                MockHttpServletResponse response = new MockHttpServletResponse();
                FilterChain chain = mock(FilterChain.class);

                filter.doFilterInternal(request, response, chain);

                verify(chain).doFilter(request, response);
                assertThat(response.getStatus()).isEqualTo(200);
            }
        }
    }

    /**
     * 文档中心的阅读接口登出的人也能读，所以受限会话不能因为浏览器里存着令牌
     * 反而打不开——那会让它比一个登出的访客还差。但它也不该被当成正常成员：
     * 放行的方式是"不设置 principal 继续走"，于是 DocService 收到的
     * authenticated 是 false，只返回公开文档。
     */
    @Test
    void restrictedSessionsReadPublicDocumentationAsAnonymousVisitors() throws Exception {
        AuthService auth = mock(AuthService.class);
        AccessTokenFilter filter = new AccessTokenFilter(auth);
        var noAccess = noAccessUser();
        var mustChangePassword = new AuthenticatedUser(8L, "fresh", "新同学", UserRole.CAMPUS_MANAGER,
                null, true, 4L, "CAMPUS_MANAGER", "校区负责人",
                DataScope.CAMPUS, Set.of(PermissionCode.PHOTO_UPLOAD), Set.of(1L));

        for (AuthenticatedUser user : List.of(noAccess, mustChangePassword)) {
            when(auth.authenticate("token")).thenReturn(new AuthService.SessionAuthentication(92L, user));
            for (String path : List.of("/api/v1/public/docs",
                    "/api/v1/public/docs/XK54YN0XKN1E657AHQZZ3TQDQ9",
                    "/api/v1/public/docs/assets/XK54YN0XKN1E657AHQZZ3TQDQ9")) {
                // 同一线程上的其他用例可能留下过 principal；断言的是"这次没有设置"，
                // 所以必须先清干净，否则这条断言会变成一句空话。
                SecurityContextHolder.clearContext();
                MockHttpServletRequest request = request(path);
                MockHttpServletResponse response = new MockHttpServletResponse();
                FilterChain chain = mock(FilterChain.class);

                filter.doFilterInternal(request, response, chain);

                verify(chain).doFilter(request, response);
                assertThat(response.getStatus()).isEqualTo(200);
                assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            }
        }
        // 降级只针对 GET：文档的写接口在 /docs/** 下要 DOC_MANAGE，
        // 受限会话碰到它必须照旧被挡住。
        when(auth.authenticate("token"))
                .thenReturn(new AuthService.SessionAuthentication(93L, noAccessUser()));
        MockHttpServletRequest write = request("/api/v1/docs");
        write.setMethod("POST");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(write, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(write, response);
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
