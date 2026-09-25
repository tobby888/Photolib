package cn.photolib.project;

import cn.photolib.auth.AuthService;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.photo.PhotoTags;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 选题结束时按筛选条件清理图片，走完整过滤器链后的接口形状：多选条件按 {@code tags=a&tags=b}
 * 绑定、预演的指纹原样带回、不带请求体的旧调用仍然只删「不可用」，并且删了什么进审计日志。
 */
@SpringBootTest
@Transactional
class EventProjectCleanupHttpTests {
    private static final long ADMIN_ID = 9_701L;
    private static final long PROJECT_ID = 97_001L;

    private MockMvc mvc;
    private long seed;

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcClient jdbc;
    @MockitoBean private AuthService authService;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, campus_id, enabled, must_change_password)
                VALUES (:id, 'cleanup-http-admin', 'hash', '清理管理员', 'ADMIN', NULL, TRUE, FALSE)
                """).param("id", ADMIN_ID).update();
        jdbc.sql("""
                INSERT INTO project (id, title, status, created_by, tags_json, type)
                VALUES (:id, '清理接口选题', 'COMPLETED', :adminId, '["开幕","合影"]', 'EVENT')
                """).param("id", PROJECT_ID).param("adminId", ADMIN_ID).update();
        when(authService.authenticate("admin")).thenReturn(new AuthService.SessionAuthentication(1L,
                new AuthenticatedUser(ADMIN_ID, "cleanup-http-admin", "清理管理员", UserRole.ADMIN, null, false)));
    }

    @Test
    void cleanupByFilterBindsMultiValueParamsAndIsAudited() throws Exception {
        long zhangOpening = insertAlbumPhoto(List.of("开幕", "合影"), "张三");
        long liOpening = insertAlbumPhoto(List.of("开幕", "合影"), "李四");
        long zhangGroupOnly = insertAlbumPhoto(List.of("合影"), "张三");

        String plan = mvc.perform(as(get("/api/v1/projects/" + PROJECT_ID + "/selection/cleanup")
                        .param("tags", "开幕", "合影")
                        .param("photographers", "张三", "王五")
                        .param("takenFrom", "2026-05-01")
                        .param("adoption", "NOT_ADOPTED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ready").value(true))
                .andExpect(jsonPath("$.data.deletableCount").value(1))
                .andExpect(jsonPath("$.data.adoptedSkippedCount").value(0))
                .andExpect(jsonPath("$.data.samples.length()").value(1))
                .andExpect(jsonPath("$.data.samples[0].id").value(String.valueOf(zhangOpening)))
                .andExpect(jsonPath("$.data.samples[0].thumbnailUrl").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String token = plan.replaceAll("(?s).*\"planToken\":\"([0-9a-f]+)\".*", "$1");

        String body = """
                {"tags":["开幕","合影"],"photographers":["张三","王五"],"takenFrom":"2026-05-01",
                 "adoption":"NOT_ADOPTED","planToken":"%s"}
                """;
        mvc.perform(as(post("/api/v1/projects/" + PROJECT_ID + "/selection/cleanup")
                        .contentType(MediaType.APPLICATION_JSON).content(body.formatted("0".repeat(32)))))
                .andExpect(status().isConflict());
        assertThat(deleted(zhangOpening)).isFalse();

        mvc.perform(as(post("/api/v1/projects/" + PROJECT_ID + "/selection/cleanup")
                        .contentType(MediaType.APPLICATION_JSON).content(body.formatted(token))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deletedCount").value(1))
                .andExpect(jsonPath("$.data.skippedAdoptedCount").value(0));
        assertThat(deleted(zhangOpening)).isTrue();
        assertThat(deleted(liOpening)).isFalse();
        assertThat(deleted(zhangGroupOnly)).isFalse();

        String detail = jdbc.sql("""
                SELECT detail_json FROM audit_log
                WHERE resource_type = 'PROJECTS' AND resource_id = :id
                ORDER BY id DESC LIMIT 1
                """).param("id", String.valueOf(PROJECT_ID)).query(String.class).single();
        // H2 会把 JSON 列再包一层字符串，所以不按引号断言。
        assertThat(detail).contains("selection/cleanup").contains("开幕").contains("张三")
                .contains("NOT_ADOPTED").contains("deletedCount");
    }

    @Test
    void aCallWithoutABodyStillOnlyRemovesDeprecatedPhotos() throws Exception {
        long dropped = insertAlbumPhoto(List.of(PhotoTags.DEPRECATED), "张三");
        long kept = insertAlbumPhoto(List.of("合影"), "张三");

        mvc.perform(as(get("/api/v1/projects/" + PROJECT_ID + "/selection/cleanup")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deletableCount").value(1));
        mvc.perform(as(post("/api/v1/projects/" + PROJECT_ID + "/selection/cleanup")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deletedCount").value(1));
        assertThat(deleted(dropped)).isTrue();
        assertThat(deleted(kept)).isFalse();
    }

    @Test
    void anInvertedDateRangeIsABadRequest() throws Exception {
        mvc.perform(as(get("/api/v1/projects/" + PROJECT_ID + "/selection/cleanup")
                        .param("takenFrom", "2026-05-03").param("takenTo", "2026-05-02")))
                .andExpect(status().isBadRequest());
    }

    private long insertAlbumPhoto(List<String> tags, String photographer) {
        seed++;
        long id = 97_100L + seed;
        jdbc.sql("""
                INSERT INTO photo
                    (id, project_id, title, photographer_student_id, photographer_name, uploaded_by,
                     taken_at, tags_json, size, content_type, object_key, stored_file_name, sha256, status)
                VALUES (:id, :projectId, '清理图片', '20230001', :photographer, :uploader, :takenAt, :tags, 1000,
                        'image/jpeg', :objectKey, 'cleanup.jpg', :sha, 'AVAILABLE')
                """).param("id", id).param("projectId", PROJECT_ID).param("photographer", photographer)
                .param("uploader", ADMIN_ID).param("takenAt", LocalDateTime.of(2026, 5, 2, 10, 0))
                .param("tags", PhotoTags.toJson(tags)).param("objectKey", "photos/cleanup-http-" + id + ".jpg")
                .param("sha", String.format("%064d", id)).update();
        jdbc.sql("INSERT INTO photo_project (photo_id, project_id) VALUES (:photoId, :projectId)")
                .param("photoId", id).param("projectId", PROJECT_ID).update();
        return id;
    }

    private boolean deleted(long photoId) {
        return jdbc.sql("SELECT deleted FROM photo WHERE id = :id")
                .param("id", photoId).query(Boolean.class).single();
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer admin").with(request -> {
            request.setServletPath(request.getRequestURI());
            return request;
        });
    }
}
