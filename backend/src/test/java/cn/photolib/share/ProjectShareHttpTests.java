package cn.photolib.share;

import cn.photolib.auth.AuthService;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.permission.DataScope;
import cn.photolib.permission.PermissionCode;
import cn.photolib.project.ProjectService;
import cn.photolib.project.model.ProjectEntity;
import cn.photolib.project.model.ProjectStatus;
import cn.photolib.user.model.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分享链接走完整过滤器链的行为。
 *
 * <p>这里验的是 Service 单测看不到的那一段：{@code SecurityConfig} 的 permitAll 清单
 * 是否覆盖了访客通道、{@code AccessTokenFilter} 会不会把一个受限的登录会话在这条
 * 通道上顶掉，以及管理端是不是真的挂着 {@code PROJECT_SHARE}。</p>
 */
@SpringBootTest
@Transactional
class ProjectShareHttpTests {
    private static final long MINISTER_ID = 8_801L;
    private static final long PHOTO_ID = 88_001L;

    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired private WebApplicationContext context;
    @Autowired private ProjectShareService shareService;
    @Autowired private ProjectService projectService;
    @Autowired private JdbcClient jdbc;
    /** 只为了给过滤器发一个"有效令牌"，不真的建会话。 */
    @MockitoBean private AuthService authService;

    private String token;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (:id, 'share-http-minister', 'hash', '分享部长', 'MINISTER', TRUE, FALSE)
                """).param("id", MINISTER_ID).update();
        AuthenticatedUser minister = new AuthenticatedUser(MINISTER_ID, "share-http-minister",
                "分享部长", UserRole.MINISTER, null, false);

        ProjectEntity project = projectService.create("HTTP 分享项目", "说明",
                ProjectStatus.ACTIVE, minister);
        jdbc.sql("""
                INSERT INTO photo
                    (id, title, photographer_student_id, photographer_name, uploaded_by,
                     taken_at, size, content_type, object_key, stored_file_name, sha256, status)
                VALUES (:id, '合影', '20230001', '张三', :uploader, NOW(), 1000, 'image/jpeg',
                        'photos/http.jpg', 'http.jpg', :sha, 'AVAILABLE')
                """).param("id", PHOTO_ID).param("uploader", MINISTER_ID)
                .param("sha", "d".repeat(64)).update();
        projectService.addPhotos(project.getId(), List.of(PHOTO_ID), minister);

        token = shareService.create(project.getId(),
                new ProjectShareService.CreateCommand("HTTP 测试", "share-pass", true, true, null),
                minister).link().token();

        when(authService.authenticate(anyString()))
                .thenReturn(new AuthService.SessionAuthentication(4_242L, waitingForPermissions()));
    }

    @Test
    void anAnonymousVisitorGoesFromPasswordToPhotosWithoutEverLoggingIn() throws Exception {
        mvc.perform(anonymous(get("/api/v1/public/shares/" + token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiresPassword").value(true));

        String session = openSession("share-pass");
        mvc.perform(anonymous(get("/api/v1/public/shares/" + token + "/photos"))
                        .header(ProjectSharePublicController.SESSION_HEADER, session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(PHOTO_ID))
                // 学号是个人信息，访客视图里根本没有这个字段。
                .andExpect(jsonPath("$.data.items[0].photographerStudentId").doesNotExist());
    }

    /**
     * 缺会话头和会话作废走同一条出口：403 加一句"重新输入密码"。
     * 刻意不是 401——401 会撞上前端 api.ts 的令牌刷新拦截器，给一个从没登录过的
     * 访客发一次注定失败的 /auth/refresh，还会广播一次"会话过期"。
     */
    @Test
    void browsingWithoutASessionIsForbiddenRatherThanUnauthorized() throws Exception {
        mvc.perform(anonymous(get("/api/v1/public/shares/" + token + "/photos")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("重新输入密码")));
    }

    @Test
    void aWrongPasswordIsRefusedAndHandsOutNoSession() throws Exception {
        mvc.perform(anonymous(post("/api/v1/public/shares/" + token + "/sessions"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"wrong-password\"}"))
                .andExpect(status().isForbidden());
    }

    /**
     * 浏览器里恰好存着一份受限令牌（未改初始密码、或还没分配权限组）的人，点开
     * 同事发来的分享链接必须照常能看。{@code AccessTokenFilter} 对这条通道整条早退，
     * 不早退的话这类会话会在过滤器里被 403 顶掉，比一个登出的访客还差。
     */
    @Test
    void aRestrictedLoginSessionCanStillOpenAShareLink() throws Exception {
        String session = openSession("share-pass");
        mvc.perform(anonymous(get("/api/v1/public/shares/" + token + "/photos"))
                        .header(ProjectSharePublicController.SESSION_HEADER, session)
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(PHOTO_ID));
    }

    /** 管理端不在 permitAll 清单里，匿名一律 401。 */
    @Test
    void theManagementEndpointsStayBehindLogin() throws Exception {
        mvc.perform(anonymous(get("/api/v1/projects/1/share-links")))
                .andExpect(status().isUnauthorized());
    }

    /** 同一个受限令牌碰管理端仍然被挡住——早退只对访客通道生效。 */
    @Test
    void theSameRestrictedTokenCannotReachTheManagementEndpoints() throws Exception {
        mvc.perform(anonymous(get("/api/v1/projects/1/share-links"))
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isForbidden());
    }

    /** 有 PROJECT_VIEW 但没有 PROJECT_SHARE 的账号被方法级授权挡在门外。 */
    @Test
    void anAccountWithoutTheSharePermissionIsRefusedByMethodSecurity() throws Exception {
        when(authService.authenticate(anyString())).thenReturn(
                new AuthService.SessionAuthentication(4_243L, new AuthenticatedUser(MINISTER_ID,
                        "share-http-minister", "分享部长", UserRole.MINISTER, null, false, 6L,
                        "CUSTOM", "自定义组", DataScope.GLOBAL,
                        Set.of(PermissionCode.PROJECT_VIEW), Set.of())));

        mvc.perform(anonymous(get("/api/v1/projects/1/share-links"))
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isForbidden());
    }

    private String openSession(String password) throws Exception {
        String body = mvc.perform(anonymous(post("/api/v1/public/shares/" + token + "/sessions"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode session = json.readTree(body).path("data");
        assertThat(session.path("access").path("allowDownload").asBoolean()).isTrue();
        return session.path("sessionToken").asText();
    }

    /**
     * DispatcherServlet 映射在 `/` 上，线上 {@code getServletPath()} 就是完整路径；
     * MockMvc 不会替我们填，而 {@code AccessTokenFilter} 正是按它判断"这是不是访客通道"。
     */
    private MockHttpServletRequestBuilder anonymous(MockHttpServletRequestBuilder builder) {
        return builder.with(request -> {
            request.setServletPath(request.getRequestURI());
            return request;
        });
    }

    private AuthenticatedUser waitingForPermissions() {
        return new AuthenticatedUser(4_242L, "waiting", "待分配", UserRole.CAMPUS_MANAGER, null,
                false, 4L, "NO_ACCESS", "待分配权限", DataScope.NONE, Set.of(), Set.of());
    }
}
