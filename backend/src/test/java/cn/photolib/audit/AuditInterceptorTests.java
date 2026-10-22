package cn.photolib.audit;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.GlobalExceptionHandler;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AuditInterceptorTests {
    private AuditLogMapper mapper;
    private AuditTargets targets;
    private AuditInterceptor interceptor;

    /** 拿来造 HandlerMethod 的假控制器：注解是拦截器唯一看的东西。 */
    static class Endpoints {
        @AuditAction("删除图片") void delete() { }
        @AuditSkip("轮询") void poll() { }
        @AuditAction("导出操作日志") void export() { }
        void list() { }
        void unlabeledWrite() { }
    }

    private static HandlerMethod handler(String name) throws NoSuchMethodException {
        return new HandlerMethod(new Endpoints(), Endpoints.class.getDeclaredMethod(name));
    }

    @BeforeEach
    void setUp() {
        mapper = mock(AuditLogMapper.class);
        targets = mock(AuditTargets.class);
        interceptor = new AuditInterceptor(mapper, targets);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private AuditLogEntity run(MockHttpServletRequest request, MockHttpServletResponse response, Object handler) {
        interceptor.preHandle(request, response, handler);
        interceptor.afterCompletion(request, response, handler, null);
        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(mapper).insert(captor.capture());
        return captor.getValue();
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }

    @Test
    void recordsPasswordChangesButDoesNotTreatActionWordAsResourceId() {
        AuthenticatedUser user = new AuthenticatedUser(7L, "user", "用户", UserRole.MINISTER, null, false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));

        AuditLogEntity entry = run(request("PUT", "/api/v1/auth/password"), new MockHttpServletResponse(), new Object());

        assertThat(entry.getOperatorId()).isEqualTo(7L);
        assertThat(entry.getResourceType()).isEqualTo("AUTH");
        assertThat(entry.getResourceId()).isNull();
    }

    @Test
    void recordsFailedLoginWithTheTargetedAccountButNeverTheCredentials() {
        MockHttpServletRequest request = request("POST", "/api/v1/auth/login");
        request.setAttribute(AuditInterceptor.DETAIL_ATTRIBUTE, Map.of("identifier", "admin"));
        request.setParameter("password", "hunter2");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(401);

        AuditLogEntity entry = run(request, response, new Object());

        assertThat(entry.getResourceType()).isEqualTo("AUTH");
        assertThat(entry.getOperatorId()).isNull();
        assertThat(entry.getDetailJson()).contains("\"status\":401").contains("\"identifier\":\"admin\"");
        assertThat(entry.getDetailJson()).doesNotContain("hunter2");
        assertThat(entry.getSuccess()).isFalse();
        assertThat(entry.getHttpStatus()).isEqualTo(401);
    }

    @Test
    void recordsTheVisitorIpForwardedByLocalNginx() {
        MockHttpServletRequest request = request("POST", "/api/v1/auth/login");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Real-IP", "203.0.113.7");
        request.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/130.0");

        AuditLogEntity entry = run(request, new MockHttpServletResponse(), new Object());

        assertThat(entry.getIpAddress()).isEqualTo("203.0.113.7");
        assertThat(entry.getUserAgent()).contains("Chrome/130.0");
        assertThat(entry.getDurationMs()).isNotNull().isNotNegative();
    }

    @Test
    void stillSkipsTheRefreshEndpoint() {
        // Refresh fires on a timer for every active session; auditing it would bury
        // the log without saying anything the paired login does not already say.
        MockHttpServletRequest request = request("POST", "/api/v1/auth/refresh");

        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);

        verifyNoInteractions(mapper);
    }

    @Test
    void resolvesCurrentUserAvatarWritesToPrincipalId() {
        AuthenticatedUser user = new AuthenticatedUser(
                27L, "avatar-user", "头像用户", UserRole.MINISTER, null, false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        when(targets.nameOf("users", "27")).thenReturn("头像用户 @avatar-user");

        AuditLogEntity entry = run(request("PUT", "/api/v1/users/me/avatar"), new MockHttpServletResponse(), new Object());

        assertThat(entry.getResourceType()).isEqualTo("USERS");
        assertThat(entry.getResourceId()).isEqualTo("27");
        assertThat(entry.getResourceName()).isEqualTo("头像用户 @avatar-user");
    }

    @Test
    void namesTheOperationFromTheEndpointAnnotation() throws Exception {
        AuditLogEntity entry = run(request("DELETE", "/api/v1/photos/12"), new MockHttpServletResponse(),
                handler("delete"));

        assertThat(entry.getActionName()).isEqualTo("删除图片");
        assertThat(entry.getAction()).isEqualTo("DELETE");
        assertThat(entry.getSuccess()).isTrue();
    }

    @Test
    void skippedWritesAndUnlabeledReadsLeaveNoTrace() throws Exception {
        MockHttpServletRequest poll = request("POST", "/api/v1/auth/mcp/token");
        interceptor.preHandle(poll, new MockHttpServletResponse(), handler("poll"));
        interceptor.afterCompletion(poll, new MockHttpServletResponse(), handler("poll"), null);
        MockHttpServletRequest read = request("GET", "/api/v1/photos");
        interceptor.preHandle(read, new MockHttpServletResponse(), handler("list"));
        interceptor.afterCompletion(read, new MockHttpServletResponse(), handler("list"), null);

        verifyNoInteractions(mapper);
    }

    @Test
    void labeledReadsAreRecorded() throws Exception {
        AuditLogEntity entry = run(request("GET", "/api/v1/audit-logs/export"), new MockHttpServletResponse(),
                handler("export"));

        assertThat(entry.getActionName()).isEqualTo("导出操作日志");
        assertThat(entry.getAction()).isEqualTo("GET");
    }

    @Test
    void looksUpTheTargetBeforeTheHandlerRunsSoDeletedObjectsKeepTheirName() throws Exception {
        MockHttpServletRequest request = request("DELETE", "/api/v1/projects/12");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/projects/{id}");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("id", "12"));
        when(targets.nameOf("projects", "12")).thenReturn("春季运动会");
        HandlerMethod handler = handler("delete");

        interceptor.preHandle(request, new MockHttpServletResponse(), handler);
        // 处理器删掉了它：之后再查已经查不到。
        when(targets.nameOf("projects", "12")).thenReturn(null);
        interceptor.afterCompletion(request, new MockHttpServletResponse(), handler, null);

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getResourceId()).isEqualTo("12");
        assertThat(captor.getValue().getResourceName()).isEqualTo("春季运动会");
    }

    @Test
    void nestedRoutesNameTheParentInFrontOfTheFirstVariable() {
        MockHttpServletRequest request = request("DELETE", "/api/v1/projects/12/adoptions/5");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/projects/{projectId}/adoptions/{id}");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("projectId", "12", "id", "5"));

        assertThat(AuditInterceptor.target(request)).isEqualTo(new AuditInterceptor.Target("projects", "12"));
    }

    record Created(Long id, String title) {
    }

    @Test
    void createdObjectsTakeTheirIdAndNameFromTheResponse() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/v1/projects");
        HandlerMethod handler = handler("unlabeledWrite");
        interceptor.preHandle(request, new MockHttpServletResponse(), handler);
        request.setAttribute(AuditResponseAdvice.RESPONSE_DATA_ATTRIBUTE, new Created(88L, "毕业季"));
        interceptor.afterCompletion(request, new MockHttpServletResponse(), handler, null);

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getResourceId()).isEqualTo("88");
        assertThat(captor.getValue().getResourceName()).isEqualTo("毕业季");
        assertThat(captor.getValue().getActionName()).isNull();
    }

    @Test
    void failuresCarryTheReasonTheUserSaw() {
        MockHttpServletRequest request = request("POST", "/api/v1/requests/3/accept");
        request.setAttribute(GlobalExceptionHandler.ERROR_CODE_ATTRIBUTE, "CONFLICT");
        request.setAttribute(GlobalExceptionHandler.ERROR_MESSAGE_ATTRIBUTE, "需求已被认领满");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(409);

        AuditLogEntity entry = run(request, response, new Object());

        assertThat(entry.getSuccess()).isFalse();
        assertThat(entry.getHttpStatus()).isEqualTo(409);
        assertThat(entry.getErrorCode()).isEqualTo("CONFLICT");
        assertThat(entry.getErrorMessage()).isEqualTo("需求已被认领满");
    }

    @Test
    void unhandledExceptionsCountAsServerErrors() {
        MockHttpServletRequest request = request("POST", "/api/v1/photos/batch-delete");
        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), new IllegalStateException("boom"));

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getHttpStatus()).isEqualTo(500);
        assertThat(captor.getValue().getErrorMessage()).contains("IllegalStateException");
    }

    @Test
    void recordsTheJsonBodyWithSecretsMasked() throws Exception {
        MockHttpServletRequest raw = request("PUT", "/api/v1/projects/12");
        raw.setContentType("application/json");
        raw.setContent("""
                {"title":"春季运动会","password":"hunter2","nested":{"accessToken":"t0ken"},"code":"123456"}
                """.getBytes(StandardCharsets.UTF_8));
        ContentCachingRequestWrapper request = new ContentCachingRequestWrapper(raw, 1024);
        request.getInputStream().readAllBytes(); // 控制器读请求体时副本才会填上

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(mapper).insert(captor.capture());
        String detail = captor.getValue().getDetailJson();
        assertThat(detail).contains("春季运动会").contains(AuditPayloads.MASK);
        assertThat(detail).doesNotContain("hunter2").doesNotContain("t0ken").doesNotContain("123456");
    }

    @Test
    void uploadsListFileNamesAndSizesNotContents() {
        MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/api/v1/doc-files");
        request.addFile(new MockMultipartFile("file", "期末总结.pdf", "application/pdf", new byte[2048]));
        request.addParameter("title", "期末总结");

        AuditLogEntity entry = run(request, new MockHttpServletResponse(), new Object());

        assertThat(entry.getDetailJson()).contains("期末总结.pdf").contains("\"size\":2048")
                .contains("\"title\":\"期末总结\"");
    }
}
