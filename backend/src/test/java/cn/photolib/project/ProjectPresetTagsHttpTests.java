package cn.photolib.project;

import cn.photolib.auth.AuthService;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.permission.DataScope;
import cn.photolib.permission.PermissionCode;
import cn.photolib.user.model.UserRole;
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

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 预设标签走完整过滤器链后的接口形状与授权：前端读的是 {@code tags} 数组，不是库里的 JSON 字符串；
 * 批量改标签只对能编辑图片元数据的账号开放，并且进审计日志。
 */
@SpringBootTest
@Transactional
class ProjectPresetTagsHttpTests {
    private static final long ADMIN_ID = 9_501L;
    private static final long VIEWER_ID = 9_502L;
    private static final long UPLOADER_ID = 9_503L;
    private static final long CAMPUS_ID = 9_510L;
    private static final long PHOTO_ID = 95_001L;
    private static final long REQUEST_ID = 95_101L;

    private MockMvc mvc;

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcClient jdbc;
    @MockitoBean private AuthService authService;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("INSERT INTO campus (id, code, name, enabled, version, deleted) VALUES (:id, 'TAGHTTP', '标签接口校区', TRUE, 1, FALSE)")
                .param("id", CAMPUS_ID).update();
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, campus_id, enabled, must_change_password)
                VALUES (:adminId, 'tags-http-admin', 'hash', '标签管理员', 'ADMIN', NULL, TRUE, FALSE),
                       (:viewerId, 'tags-http-viewer', 'hash', '只看选题', 'MINISTER', NULL, TRUE, FALSE),
                       (:uploaderId, 'tags-http-uploader', 'hash', '需求上传者', 'CAMPUS_MANAGER', :campusId, TRUE, FALSE)
                """).param("adminId", ADMIN_ID).param("viewerId", VIEWER_ID)
                .param("uploaderId", UPLOADER_ID).param("campusId", CAMPUS_ID).update();
        when(authService.authenticate("admin")).thenReturn(new AuthService.SessionAuthentication(1L,
                new AuthenticatedUser(ADMIN_ID, "tags-http-admin", "标签管理员", UserRole.ADMIN, null, false)));
        when(authService.authenticate("viewer")).thenReturn(new AuthService.SessionAuthentication(2L,
                new AuthenticatedUser(VIEWER_ID, "tags-http-viewer", "只看选题", UserRole.MINISTER, null, false,
                        11L, "PROJECT_ONLY", "只看选题组", DataScope.GLOBAL,
                        Set.of(PermissionCode.PROJECT_VIEW_ALL), Set.of())));
        // 只有需求上传权限、没有需求查看和选题查看权限：上传表单仍然要拿得到预设标签。
        when(authService.authenticate("uploader")).thenReturn(new AuthService.SessionAuthentication(3L,
                new AuthenticatedUser(UPLOADER_ID, "tags-http-uploader", "需求上传者", UserRole.CAMPUS_MANAGER,
                        CAMPUS_ID, false, 12L, "UPLOAD_ONLY", "只上传组", DataScope.CAMPUS,
                        Set.of(PermissionCode.REQUEST_PHOTO_MANAGE), Set.of(CAMPUS_ID))));
    }

    @Test
    void presetTagsTravelAsAnArrayAndBatchTaggingIsAuditedAndGuarded() throws Exception {
        String created = mvc.perform(as("admin", post("/api/v1/projects").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"接口标签选题","description":"","status":"ACTIVE","tags":["合影"," 合影","毕业典礼"]}
                                """)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tags.length()").value(2))
                .andExpect(jsonPath("$.data.tags[0]").value("合影"))
                .andExpect(jsonPath("$.data.tagsJson").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        long projectId = Long.parseLong(created.replaceAll("(?s).*\"id\":\"?(\\d+).*", "$1"));

        jdbc.sql("""
                INSERT INTO photo
                    (id, project_id, title, photographer_student_id, photographer_name, uploaded_by,
                     taken_at, tags_json, size, content_type, object_key, stored_file_name, sha256, status)
                VALUES (:id, :projectId, '合影', '20230001', '张三', :uploader, :takenAt, '["旧标签"]', 1000,
                        'image/jpeg', 'photos/tags-http.jpg', 'tags-http.jpg', :sha, 'AVAILABLE')
                """).param("id", PHOTO_ID).param("projectId", projectId).param("uploader", ADMIN_ID)
                .param("takenAt", LocalDateTime.now().minusDays(1)).param("sha", "d".repeat(64)).update();
        jdbc.sql("INSERT INTO photo_project (photo_id, project_id) VALUES (:photoId, :projectId)")
                .param("photoId", PHOTO_ID).param("projectId", projectId).update();

        mvc.perform(as("admin", get("/api/v1/projects/" + projectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tags[1]").value("毕业典礼"));
        mvc.perform(as("admin", get("/api/v1/projects").param("keyword", "毕业典礼")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(String.valueOf(projectId)));
        mvc.perform(as("admin", get("/api/v1/photos").param("projectId", String.valueOf(projectId))
                        .param("includeAllStatuses", "true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].tags[0]").value("旧标签"))
                .andExpect(jsonPath("$.data.items[0].tagsJson").doesNotExist());

        String batch = """
                {"photoIds":[%d],"addTags":["合影"],"removeTags":["旧标签"],"projectId":%d}
                """.formatted(PHOTO_ID, projectId);
        // 只有选题查看权限、不能编辑图片的账号被方法级授权挡住。
        mvc.perform(as("viewer", post("/api/v1/photos/batch-tags")
                        .contentType(MediaType.APPLICATION_JSON).content(batch)))
                .andExpect(status().isForbidden());
        mvc.perform(as("admin", post("/api/v1/photos/batch-tags")
                        .contentType(MediaType.APPLICATION_JSON).content(batch)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].tags.length()").value(1))
                .andExpect(jsonPath("$.data[0].tags[0]").value("合影"));
        mvc.perform(as("admin", post("/api/v1/photos/batch-tags").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"photoIds":[%d],"addTags":["自定义"],"projectId":%d}
                                """.formatted(PHOTO_ID, projectId))))
                .andExpect(status().isBadRequest());

        String detail = jdbc.sql("""
                SELECT detail_json FROM audit_log
                WHERE resource_type='PHOTOS' AND detail_json LIKE '%batch-tags%'
                ORDER BY id DESC LIMIT 1
                """).query(String.class).single();
        // H2 会把 JSON 列再包一层字符串，所以不按引号断言。
        assertThat(detail).contains("projectId").contains(String.valueOf(projectId))
                .contains("addTags").contains("自定义");
    }

    @Test
    void requestUploadersWithoutViewPermissionsCanStillReadTheTagOptions() throws Exception {
        jdbc.sql("""
                INSERT INTO project (title, status, created_by, tags_json)
                VALUES ('需求标签选题', 'ACTIVE', :adminId, '["开学典礼"]')
                """).param("adminId", ADMIN_ID).update();
        long projectId = jdbc.sql("SELECT id FROM project WHERE title='需求标签选题'").query(Long.class).single();
        jdbc.sql("""
                INSERT INTO photo_request (id, project_id, title, campus_id, deadline, status, created_by)
                VALUES (:id, :projectId, '开学需求', :campusId, :deadline, 'ACCEPTED', :adminId)
                """).param("id", REQUEST_ID).param("projectId", projectId).param("campusId", CAMPUS_ID)
                .param("deadline", LocalDateTime.now().plusDays(2)).param("adminId", ADMIN_ID).update();
        jdbc.sql("INSERT INTO request_participant (request_id, user_id, accepted_at) VALUES (:id, :userId, :at)")
                .param("id", REQUEST_ID).param("userId", UPLOADER_ID).param("at", LocalDateTime.now()).update();

        mvc.perform(as("uploader", get("/api/v1/requests/" + REQUEST_ID + "/tag-options")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.restricted").value(true))
                .andExpect(jsonPath("$.data.tags[0]").value("开学典礼"));
        // 只看选题的账号既不是参与人也没有需求权限。
        mvc.perform(as("viewer", get("/api/v1/requests/" + REQUEST_ID + "/tag-options")))
                .andExpect(status().isForbidden());
    }

    private MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + token).with(request -> {
            request.setServletPath(request.getRequestURI());
            return request;
        });
    }
}
