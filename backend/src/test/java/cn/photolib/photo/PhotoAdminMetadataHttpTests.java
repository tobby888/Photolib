package cn.photolib.photo;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理员改图片信息的两个接口走完整过滤器链：只对管理员开放（有上传权限的全局账号也不行）、
 * 入参校验、返回形状，以及审计日志里留下改之前的值。
 */
@SpringBootTest
@Transactional
class PhotoAdminMetadataHttpTests {
    private static final long ADMIN_ID = 9_601L;
    private static final long EDITOR_ID = 9_602L;
    private static final long PHOTO_ID = 96_001L;

    private MockMvc mvc;

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcClient jdbc;
    @MockitoBean private AuthService authService;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, campus_id, enabled, must_change_password)
                VALUES (:adminId, 'meta-http-admin', 'hash', '信息管理员', 'ADMIN', NULL, TRUE, FALSE),
                       (:editorId, 'meta-http-editor', 'hash', '全局上传者', 'MINISTER', NULL, TRUE, FALSE)
                """).param("adminId", ADMIN_ID).param("editorId", EDITOR_ID).update();
        jdbc.sql("""
                INSERT INTO photo
                    (id, title, photographer_student_id, photographer_name, uploaded_by,
                     taken_at, tags_json, size, content_type, object_key, stored_file_name, sha256, status)
                VALUES (:id, '待更正', '20240001', '原拍摄者', :uploader, :takenAt, '[]', 1000,
                        'image/jpeg', 'photos/meta-http.jpg', 'meta-http.jpg', :sha, 'AVAILABLE')
                """).param("id", PHOTO_ID).param("uploader", EDITOR_ID)
                .param("takenAt", LocalDateTime.of(2024, 5, 1, 8, 0)).param("sha", "e".repeat(64)).update();
        when(authService.authenticate("admin")).thenReturn(new AuthService.SessionAuthentication(1L,
                new AuthenticatedUser(ADMIN_ID, "meta-http-admin", "信息管理员", UserRole.ADMIN, null, false)));
        // 能上传、能编辑自己上传的图（这张就是他传的），但不是管理员。
        when(authService.authenticate("editor")).thenReturn(new AuthService.SessionAuthentication(2L,
                new AuthenticatedUser(EDITOR_ID, "meta-http-editor", "全局上传者", UserRole.MINISTER, null, false,
                        21L, "UPLOADER", "上传组", DataScope.GLOBAL,
                        Set.of(PermissionCode.PHOTO_VIEW, PermissionCode.PHOTO_UPLOAD), Set.of())));
    }

    @Test
    void onlyAdministratorsCanEditAndTheOldValuesLandInTheAuditLog() throws Exception {
        String single = """
                {"title":"已更正","description":"","photographerName":"站外拍摄者","photographerStudentId":"20990001",
                 "takenAt":"2024-05-01T08:00:00","campusId":null,"tags":[],"version":1}
                """;
        String batch = """
                {"photoIds":[%d],"photographer":{"name":"批量拍摄者","studentId":"20990002"}}
                """.formatted(PHOTO_ID);

        mvc.perform(as("editor", put("/api/v1/photos/" + PHOTO_ID + "/admin-metadata")
                        .contentType(MediaType.APPLICATION_JSON).content(single)))
                .andExpect(status().isForbidden());
        mvc.perform(as("editor", post("/api/v1/photos/batch-metadata")
                        .contentType(MediaType.APPLICATION_JSON).content(batch)))
                .andExpect(status().isForbidden());

        mvc.perform(as("admin", put("/api/v1/photos/" + PHOTO_ID + "/admin-metadata")
                        .contentType(MediaType.APPLICATION_JSON).content(single)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.photographerName").value("站外拍摄者"))
                .andExpect(jsonPath("$.data.photographerStudentId").value("20990001"))
                .andExpect(jsonPath("$.data.version").value(2));
        String detail = latestAuditDetail("admin-metadata");
        // H2 会把 JSON 列再包一层字符串，所以不按引号断言。
        assertThat(detail).contains("previous").contains("原拍摄者").contains("20240001").contains("adoptionsUpdated");

        mvc.perform(as("admin", post("/api/v1/photos/batch-metadata")
                        .contentType(MediaType.APPLICATION_JSON).content(batch)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.photoCount").value(1))
                .andExpect(jsonPath("$.data.changedCount").value(1))
                .andExpect(jsonPath("$.data.previous").doesNotExist());
        assertThat(latestAuditDetail("batch-metadata")).contains("changedCount").contains("站外拍摄者");
        assertThat(jdbc.sql("SELECT photographer_name FROM photo WHERE id=:id").param("id", PHOTO_ID)
                .query(String.class).single()).isEqualTo("批量拍摄者");
    }

    @Test
    void blankPhotographersAndOversizedBatchesAreRejectedUpFront() throws Exception {
        mvc.perform(as("admin", put("/api/v1/photos/" + PHOTO_ID + "/admin-metadata")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"title":"标题","photographerName":"  ","takenAt":"2024-05-01T08:00:00","version":1}
                                """)))
                .andExpect(status().isBadRequest());
        mvc.perform(as("admin", post("/api/v1/photos/batch-metadata")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"photoIds":[%d],"photographer":{"name":"","studentId":"1"}}
                                """.formatted(PHOTO_ID))))
                .andExpect(status().isBadRequest());
        String tooMany = java.util.stream.LongStream.rangeClosed(1, 201)
                .mapToObj(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        mvc.perform(as("admin", post("/api/v1/photos/batch-metadata")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"photoIds":[%s],"title":"标题"}
                                """.formatted(tooMany))))
                .andExpect(status().isBadRequest());
    }

    private String latestAuditDetail(String pathPart) {
        return jdbc.sql("""
                SELECT detail_json FROM audit_log
                WHERE resource_type='PHOTOS' AND detail_json LIKE :path
                ORDER BY id DESC LIMIT 1
                """).param("path", "%" + pathPart + "%").query(String.class).single();
    }

    private MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + token).with(request -> {
            request.setServletPath(request.getRequestURI());
            return request;
        });
    }
}
