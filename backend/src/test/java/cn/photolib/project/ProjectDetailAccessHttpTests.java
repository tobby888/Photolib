package cn.photolib.project;

import cn.photolib.auth.AuthService;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.permission.DataScope;
import cn.photolib.permission.PermissionCode;
import cn.photolib.project.model.ProjectEntity;
import cn.photolib.project.model.ProjectStatus;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 只勾了「无条件查看全部选题」的权限组，能不能真的把选题详情页打开。
 *
 * <p>这条链路 Service 单测看不全：详情页会并发打四条接口，任何一条被方法级授权挡住，
 * 前端的 {@code Promise.all} 就整页失败。所以这里按详情页实际打的那几条逐一验，
 * 并且把「需求列表仍然要 REQUEST_VIEW」也钉住——前端必须自己绕开它，
 * 而不是指望后端顺带放行（见 {@code ProjectDetailPage.tsx} 的 canViewRequests）。</p>
 */
@SpringBootTest
@Transactional
class ProjectDetailAccessHttpTests {
    private static final long ADMIN_ID = 8_901L;
    private static final long VIEWER_ID = 8_902L;
    private static final long PHOTO_ID = 89_001L;

    private MockMvc mvc;
    private Long projectId;

    @Autowired private WebApplicationContext context;
    @Autowired private ProjectService projectService;
    @Autowired private JdbcClient jdbc;
    /** 只为了给过滤器发一个"有效令牌"，不真的建会话。 */
    @MockitoBean private AuthService authService;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (:adminId, 'detail-http-admin', 'hash', '详情管理员', 'ADMIN', TRUE, FALSE),
                       (:viewerId, 'detail-http-viewer', 'hash', '只看选题', 'MINISTER', TRUE, FALSE)
                """).param("adminId", ADMIN_ID).param("viewerId", VIEWER_ID).update();

        AuthenticatedUser admin = new AuthenticatedUser(ADMIN_ID, "detail-http-admin",
                "详情管理员", UserRole.ADMIN, null, false);
        ProjectEntity project = projectService.create("详情访问项目", "说明", ProjectStatus.ACTIVE, admin);
        projectId = project.getId();
        jdbc.sql("""
                INSERT INTO photo
                    (id, title, photographer_student_id, photographer_name, uploaded_by,
                     taken_at, size, content_type, object_key, stored_file_name, sha256, status)
                VALUES (:id, '合影', '20230001', '张三', :uploader, NOW(), 1000, 'image/jpeg',
                        'photos/detail.jpg', 'detail.jpg', :sha, 'AVAILABLE')
                """).param("id", PHOTO_ID).param("uploader", ADMIN_ID)
                .param("sha", "e".repeat(64)).update();
        projectService.addPhotos(projectId, List.of(PHOTO_ID), admin);

        // 正是用户在权限面板里勾出来的那一组：只有选题相关的权限，没有需求、没有图库。
        when(authService.authenticate(anyString())).thenReturn(
                new AuthService.SessionAuthentication(4_244L, new AuthenticatedUser(VIEWER_ID,
                        "detail-http-viewer", "只看选题", UserRole.MINISTER, null, false, 9L,
                        "PROJECT_ONLY", "只看选题组", DataScope.GLOBAL,
                        Set.of(PermissionCode.PROJECT_VIEW_ALL, PermissionCode.PROJECT_ADOPT,
                                PermissionCode.PROJECT_DOWNLOAD), Set.of())));
    }

    /** 详情页并发打的那几条，只有选题权限的账号必须全部拿到 200。 */
    @Test
    void everyRequestTheProjectDetailPageMakesIsAllowedWithOnlyTheProjectViewPermission() throws Exception {
        mvc.perform(authenticated(get("/api/v1/projects/" + projectId)))
                .andExpect(status().isOk());
        mvc.perform(authenticated(get("/api/v1/photos")
                        .param("projectId", String.valueOf(projectId))
                        .param("includeAllStatuses", "true")))
                .andExpect(status().isOk());
        mvc.perform(authenticated(get("/api/v1/projects/" + projectId + "/adoptions")))
                .andExpect(status().isOk());
        mvc.perform(authenticated(get("/api/v1/campuses").param("enabled", "true")))
                .andExpect(status().isOk());
        mvc.perform(authenticated(get("/api/v1/projects")))
                .andExpect(status().isOk());
    }

    /**
     * 需求列表刻意仍然只认 REQUEST_VIEW：能看选题不等于能看需求。
     * 前端因此必须自己把这条挡住，不能裸放进详情页的 Promise.all——那正是
     * 「勾了选题权限却打不开选题详情」的成因。
     */
    @Test
    void theRequestListStaysBehindItsOwnPermission() throws Exception {
        mvc.perform(authenticated(get("/api/v1/requests")
                        .param("projectId", String.valueOf(projectId))))
                .andExpect(status().isForbidden());
    }

    /**
     * DispatcherServlet 映射在 `/` 上，线上 {@code getServletPath()} 就是完整路径；
     * MockMvc 不会替我们填，而 {@code AccessTokenFilter} 正是按它判断路径的。
     */
    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer token").with(request -> {
            request.setServletPath(request.getRequestURI());
            return request;
        });
    }
}
