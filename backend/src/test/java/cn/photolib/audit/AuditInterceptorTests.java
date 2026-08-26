package cn.photolib.audit;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AuditInterceptorTests {
    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void recordsPasswordChangesButDoesNotTreatActionWordAsResourceId() {
        AuditLogMapper mapper = mock(AuditLogMapper.class);
        AuditInterceptor interceptor = new AuditInterceptor(mapper);
        AuthenticatedUser user = new AuthenticatedUser(7L, "user", "用户", UserRole.MINISTER, null, false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/v1/auth/password");
        request.setRequestURI("/api/v1/auth/password");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getOperatorId()).isEqualTo(7L);
        assertThat(captor.getValue().getResourceType()).isEqualTo("AUTH");
        assertThat(captor.getValue().getResourceId()).isNull();
    }

    @Test
    void recordsFailedLoginWithTheTargetedAccountButNeverTheCredentials() {
        AuditLogMapper mapper = mock(AuditLogMapper.class);
        AuditInterceptor interceptor = new AuditInterceptor(mapper);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRequestURI("/api/v1/auth/login");
        request.setAttribute(AuditInterceptor.DETAIL_ATTRIBUTE, Map.of("identifier", "admin"));
        request.setParameter("password", "hunter2");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(401);

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(mapper).insert(captor.capture());
        AuditLogEntity entry = captor.getValue();
        assertThat(entry.getResourceType()).isEqualTo("AUTH");
        assertThat(entry.getOperatorId()).isNull();
        assertThat(entry.getDetailJson()).contains("\"status\":401").contains("\"identifier\":\"admin\"");
        assertThat(entry.getDetailJson()).doesNotContain("hunter2");
    }

    @Test
    void stillSkipsTheRefreshEndpoint() {
        // Refresh fires on a timer for every active session; auditing it would bury
        // the log without saying anything the paired login does not already say.
        AuditLogMapper mapper = mock(AuditLogMapper.class);
        AuditInterceptor interceptor = new AuditInterceptor(mapper);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/refresh");
        request.setRequestURI("/api/v1/auth/refresh");

        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);

        verifyNoInteractions(mapper);
    }

    @Test
    void resolvesCurrentUserAvatarWritesToPrincipalId() {
        AuditLogMapper mapper = mock(AuditLogMapper.class);
        AuditInterceptor interceptor = new AuditInterceptor(mapper);
        AuthenticatedUser user = new AuthenticatedUser(
                27L, "avatar-user", "头像用户", UserRole.MINISTER, null, false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "PUT", "/api/v1/users/me/avatar");
        request.setRequestURI("/api/v1/users/me/avatar");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getResourceType()).isEqualTo("USERS");
        assertThat(captor.getValue().getResourceId()).isEqualTo("27");
    }
}
