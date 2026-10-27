package cn.photolib.auth;

import cn.photolib.auth.mapper.AuthSessionMapper;
import cn.photolib.user.mapper.UserMapper;
import cn.photolib.user.model.UserRole;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 会话空闲期续期节流：上次续期不到 1 分钟就不再写 auth_session。 */
class SessionRenewalThrottleTests {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 27, 12, 0);

    @Test
    void renewsOnlyWhenTheLastRenewalIsAtLeastOneMinuteOld() {
        AuthService service = service(Duration.ofMinutes(30));

        assertThat(service.renewalInterval()).isEqualTo(Duration.ofMinutes(1));
        // 30 秒前刚续过：空闲过期时间还在"现在 + 29 分 30 秒"。
        assertThat(service.renewalDue(NOW.plusMinutes(30).minusSeconds(30), NOW)).isFalse();
        // 正好 1 分钟前续过：还不用写。
        assertThat(service.renewalDue(NOW.plusMinutes(29), NOW)).isFalse();
        assertThat(service.renewalDue(NOW.plusMinutes(29).minusSeconds(1), NOW)).isTrue();
        assertThat(service.renewalDue(null, NOW)).isTrue();
    }

    @Test
    void aVeryShortIdleTtlStillRenewsWellBeforeExpiry() {
        AuthService service = service(Duration.ofSeconds(3));

        // 节流间隔不能比空闲期还长，否则会话会在两次续期之间过期。
        assertThat(service.renewalInterval()).isEqualTo(Duration.ofMillis(100));
        assertThat(service.renewalDue(NOW.plusSeconds(2), NOW)).isTrue();
    }

    @Test
    void filterSkipsTheSessionWriteWhenRenewalIsNotDue() throws Exception {
        AuthService auth = mock(AuthService.class);
        when(auth.authenticate("token"))
                .thenReturn(new AuthService.SessionAuthentication(70L, member(), false));

        new AccessTokenFilter(auth).doFilterInternal(request(), new MockHttpServletResponse(),
                mock(FilterChain.class));

        verify(auth, never()).touch(70L);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        SecurityContextHolder.clearContext();
    }

    @Test
    void filterRenewsTheSessionWhenDue() throws Exception {
        AuthService auth = mock(AuthService.class);
        when(auth.authenticate("token"))
                .thenReturn(new AuthService.SessionAuthentication(71L, member(), true));

        new AccessTokenFilter(auth).doFilterInternal(request(), new MockHttpServletResponse(),
                mock(FilterChain.class));

        verify(auth).touch(71L);
        SecurityContextHolder.clearContext();
    }

    private static AuthService service(Duration idleTtl) {
        return new AuthService(mock(UserMapper.class), mock(AuthSessionMapper.class),
                mock(PasswordEncoder.class),
                new AuthProperties(Duration.ofMinutes(15), idleTtl, true, null));
    }

    private static AuthenticatedUser member() {
        return new AuthenticatedUser(5L, "member", "成员", UserRole.ADMIN, null, false, null);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
        request.setServletPath("/api/v1/projects");
        request.addHeader("Authorization", "Bearer token");
        return request;
    }
}
