package cn.photolib.uploadlimit;

import cn.photolib.auth.AuthService;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.photo.batch.BatchUploadService;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 上传限额的读写接口：谁能读、谁能改、改完审计里记了什么、业务上传是否立刻按新值拦截。
 *
 * <p>测试事务回滚后，{@link UploadLimitService} 的快照也会在事务结束时失效，不会把这里
 * 改的限额带进共享上下文里的其他测试。</p>
 */
@SpringBootTest
@Transactional
class UploadLimitHttpTests {
    private static final long ADMIN_ID = 8_971L;
    private static final long MINISTER_ID = 8_972L;
    private static final long MIB = 1024L * 1024;

    private MockMvc mvc;

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcClient jdbc;
    @Autowired private UploadLimitService uploadLimits;
    @Autowired private BatchUploadService batchUploads;
    @MockitoBean private AuthService authService;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, campus_id, enabled, must_change_password)
                VALUES (:adminId, 'upload-limit-admin', 'hash', '限额管理员', 'ADMIN', NULL, TRUE, FALSE),
                       (:ministerId, 'upload-limit-minister', 'hash', '部长', 'MINISTER', NULL, TRUE, FALSE)
                """).param("adminId", ADMIN_ID).param("ministerId", MINISTER_ID).update();
        when(authService.authenticate("admin")).thenReturn(new AuthService.SessionAuthentication(1L,
                new AuthenticatedUser(ADMIN_ID, "upload-limit-admin", "限额管理员", UserRole.ADMIN, null, false)));
        when(authService.authenticate("minister")).thenReturn(new AuthService.SessionAuthentication(2L,
                new AuthenticatedUser(MINISTER_ID, "upload-limit-minister", "部长", UserRole.MINISTER, null, false)));
    }

    @Test
    void anyoneCanReadTheCurrentLimitsButOnlyAdministratorsSeeTheSettings() throws Exception {
        // 选题上传链接和公开招募的访客没有登录，也要拿到限额来提示和预检。
        mvc.perform(anonymous(get("/api/v1/upload-limits")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.PHOTO_ZIP_MAX_IMAGES").value(100))
                .andExpect(jsonPath("$.data.PHOTO_IMAGE_MAX_BYTES").value(100 * MIB));

        mvc.perform(anonymous(get("/api/v1/upload-limits/settings"))).andExpect(status().isUnauthorized());
        mvc.perform(as("minister", get("/api/v1/upload-limits/settings"))).andExpect(status().isForbidden());
        mvc.perform(as("admin", get("/api/v1/upload-limits/settings")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].key").value("PHOTO_IMAGE_MAX_BYTES"))
                .andExpect(jsonPath("$.data[0].groupLabel").isNotEmpty())
                .andExpect(jsonPath("$.data[0].max").isNumber());
    }

    @Test
    void onlyAdministratorsCanChangeLimits() throws Exception {
        String body = "{\"values\":{\"PHOTO_ZIP_MAX_IMAGES\":300}}";

        mvc.perform(anonymous(put("/api/v1/upload-limits").contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isUnauthorized());
        mvc.perform(as("minister", put("/api/v1/upload-limits")
                        .contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isForbidden());

        assertThat(uploadLimits.count(UploadLimit.PHOTO_ZIP_MAX_IMAGES)).isEqualTo(100);
    }

    @Test
    void anAdministratorChangeIsAuditedAndEnforcedRightAway() throws Exception {
        mvc.perform(as("admin", put("/api/v1/upload-limits").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"PHOTO_ZIP_MAX_IMAGES\":300,\"PHOTO_ZIP_MAX_BYTES\":10485760}}")))
                .andExpect(status().isOk());

        mvc.perform(anonymous(get("/api/v1/upload-limits")))
                .andExpect(jsonPath("$.data.PHOTO_ZIP_MAX_IMAGES").value(300))
                .andExpect(jsonPath("$.data.PHOTO_ZIP_MAX_BYTES").value(10 * MIB));
        mvc.perform(as("admin", get("/api/v1/metadata/options")))
                .andExpect(jsonPath("$.data.batchImageMaxCount").value(300))
                .andExpect(jsonPath("$.data.zipMaxBytes").value(10 * MIB));

        String detail = jdbc.sql("""
                SELECT detail_json FROM audit_log
                WHERE resource_type = 'UPLOAD-LIMITS' AND operator_id = :operator
                ORDER BY id DESC LIMIT 1
                """).param("operator", ADMIN_ID).query(String.class).single();
        assertThat(detail).contains("PHOTO_ZIP_MAX_IMAGES").contains("300").contains("PHOTO_ZIP_MAX_BYTES");

        // 业务侧立刻按新值拦截：20 MiB 的压缩包已经超过刚设的 10 MiB。
        assertThatThrownBy(() -> batchUploads.createZipBatch(new BatchUploadService.ZipBatch(
                null, null, ADMIN_ID, null, "活动.zip", 20 * MIB)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("10 MiB");
    }

    @Test
    void outOfRangeValuesAreRejectedWithoutChangingAnything() throws Exception {
        mvc.perform(as("admin", put("/api/v1/upload-limits").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"PHOTO_ZIP_MAX_IMAGES\":200,\"PHOTO_IMAGE_MAX_BYTES\":1}}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(as("admin", put("/api/v1/upload-limits").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{}}")))
                .andExpect(status().isBadRequest());

        assertThat(uploadLimits.currentValues()).containsEntry("PHOTO_ZIP_MAX_IMAGES", 100L);
        assertThat(uploadLimits.settings()).noneMatch(UploadLimitService.LimitView::customized);
        assertThat(Map.copyOf(uploadLimits.currentValues())).hasSize(UploadLimit.values().length);
    }

    private static MockHttpServletRequestBuilder anonymous(MockHttpServletRequestBuilder builder) {
        return builder.with(request -> {
            request.setServletPath(request.getRequestURI());
            return request;
        });
    }

    private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder builder) {
        return anonymous(builder.header("Authorization", "Bearer " + token));
    }
}
