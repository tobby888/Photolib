package cn.photolib.audit;

import cn.photolib.auth.AuthService;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 操作日志走完整链路：请求体缓存过滤器、拦截器、返回值通知、全局异常处理四件东西
 * 只有在真实的 MVC 里才凑得齐。
 */
@SpringBootTest
@Transactional
class AuditHttpTests {
    private static final long ADMIN_ID = 9_801L;

    private MockMvc mvc;

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcClient jdbc;
    @Autowired private AuditBodyCachingFilter bodyCachingFilter;
    @MockitoBean private AuthService authService;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(bodyCachingFilter)
                .apply(springSecurity()).build();
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (:id, 'audit-http-admin', 'hash', '日志管理员', 'ADMIN', TRUE, FALSE)
                """).param("id", ADMIN_ID).update();
        AuthenticatedUser admin = new AuthenticatedUser(ADMIN_ID, "audit-http-admin", "日志管理员",
                UserRole.ADMIN, null, false);
        when(authService.authenticate(anyString())).thenReturn(new AuthService.SessionAuthentication(1L, admin));
    }

    private Map<String, Object> lastEntry() {
        return jdbc.sql("SELECT * FROM audit_log WHERE operator_id = :id ORDER BY id DESC LIMIT 1")
                .param("id", ADMIN_ID).query().singleRow();
    }

    /** H2 把 JSON 列读成字节数组。 */
    private static String text(Object value) {
        return value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(value);
    }

    @Test
    void aCreateIsRecordedWithItsNameBodyAndNewId() throws Exception {
        mvc.perform(post("/api/v1/campuses").header("Authorization", "Bearer token")
                        .header("User-Agent", "Mozilla/5.0 Chrome/130.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"AUDIT_EAST\",\"name\":\"东区审计校区\"}"))
                .andExpect(status().isOk());

        Map<String, Object> entry = lastEntry();
        Long campusId = jdbc.sql("SELECT id FROM campus WHERE code = 'AUDIT_EAST'").query(Long.class).single();
        assertThat(entry.get("action_name")).isEqualTo("新建校区");
        assertThat(entry.get("resource_type")).isEqualTo("CAMPUSES");
        assertThat(entry.get("resource_id")).isEqualTo(campusId.toString());
        assertThat(entry.get("resource_name")).isEqualTo("东区审计校区");
        assertThat(entry.get("success")).isEqualTo(true);
        assertThat(entry.get("user_agent")).isEqualTo("Mozilla/5.0 Chrome/130.0");
        // 校区编码是公开的编号，不打码，看得出新建的是哪个。
        assertThat(text(entry.get("detail_json"))).contains("AUDIT_EAST").contains("东区审计校区");
    }

    @Test
    void aFailureKeepsTheReasonTheUserWasShown() throws Exception {
        mvc.perform(put("/api/v1/campuses/987654").header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"不存在\",\"enabled\":true,\"version\":1}"))
                .andExpect(status().is4xxClientError());

        Map<String, Object> entry = lastEntry();
        assertThat(entry.get("action_name")).isEqualTo("修改校区");
        assertThat(entry.get("resource_id")).isEqualTo("987654");
        assertThat(entry.get("success")).isEqualTo(false);
        assertThat(entry.get("error_code")).isNotNull();
        assertThat(entry.get("error_message")).isNotNull();
    }

    @Test
    void validationFailuresListTheOffendingFields() throws Exception {
        mvc.perform(post("/api/v1/campuses").header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"\",\"name\":\"\"}"))
                .andExpect(status().isBadRequest());

        Map<String, Object> entry = lastEntry();
        assertThat(entry.get("error_code")).isEqualTo("VALIDATION_ERROR");
        assertThat(text(entry.get("error_message"))).contains("code").contains("name");
    }
}
