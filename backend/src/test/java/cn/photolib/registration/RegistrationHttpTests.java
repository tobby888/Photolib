package cn.photolib.registration;

import cn.photolib.permission.PermissionGroupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 注册接口走完整过滤器链：匿名能不能提交（SecurityConfig 的放行）、请求体校验、
 * 审计里记了什么，以及管理端接口对匿名访客关着。
 */
@SpringBootTest
@Transactional
class RegistrationHttpTests {
    private static final long ADMIN_ID = 96_101L;

    /** 手动装 MockMvc，理由同 {@code DocHttpTests}。 */
    private MockMvc mvc;

    @Autowired private WebApplicationContext context;
    @Autowired private RegistrationService service;
    @Autowired private PermissionGroupService permissionGroups;
    @Autowired private JdbcClient jdbc;
    @Autowired private Clock clock;

    private String code;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, permission_group_id, enabled, must_change_password)
                VALUES (:id, 'reg-http-admin', 'hash', '管理员', 'ADMIN', :groupId, TRUE, FALSE)
                """).param("id", ADMIN_ID).param("groupId", permissionGroups.requireByCode("ADMIN").getId()).update();
        code = service.createCode(new RegistrationService.CreateCode("reg-http-招新",
                permissionGroups.requireByCode("MINISTER").getId(), Set.of(), 5, null,
                LocalDateTime.now(clock).plusDays(1)), ADMIN_ID).code();
    }

    @Test
    void anonymousVisitorsCanSubmitAndTheAuditKeepsTheAccountButNotThePassword() throws Exception {
        mvc.perform(postJson("/api/v1/public/registrations", body(code, "reg-http-amy", "Secret2026abc", "Secret2026abc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("reg-http-amy"))
                .andExpect(jsonPath("$.data.displayName").value("王小美"));

        String detail = jdbc.sql("""
                SELECT detail_json FROM audit_log WHERE resource_type = 'REGISTRATIONS' ORDER BY id DESC LIMIT 1
                """).query(String.class).single();
        assertThat(detail).contains("reg-http-amy").doesNotContain("Secret2026abc").doesNotContain(code);
    }

    @Test
    void mismatchedOrWeakPasswordsAreRejected() throws Exception {
        mvc.perform(postJson("/api/v1/public/registrations", body(code, "reg-http-ben", "Secret2026abc", "Secret2026abd")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("两次输入的密码不一致"));
        mvc.perform(postJson("/api/v1/public/registrations", body(code, "reg-http-ben", "short1", "short1")))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM registration_application WHERE username = 'reg-http-ben'")
                .query(Long.class).single()).isZero();
    }

    @Test
    void managementEndpointsStayClosedToAnonymousVisitors() throws Exception {
        mvc.perform(anonymous(get("/api/v1/registration-applications"), "/api/v1/registration-applications"))
                .andExpect(status().isUnauthorized());
        mvc.perform(anonymous(get("/api/v1/registration-codes"), "/api/v1/registration-codes"))
                .andExpect(status().isUnauthorized());
        mvc.perform(postJson("/api/v1/registration-applications/approve", "{\"ids\":[1]}"))
                .andExpect(status().isUnauthorized());
    }

    private static String body(String code, String username, String password, String confirm) {
        return """
                {"code":"%s","username":"%s","displayName":"王小美","email":"%s@example.com",
                 "password":"%s","confirmPassword":"%s"}
                """.formatted(code, username, username, password, confirm);
    }

    private static MockHttpServletRequestBuilder postJson(String path, String content) {
        return anonymous(post(path), path).contentType(MediaType.APPLICATION_JSON).content(content);
    }

    /** servletPath 要手动填，理由同 {@code DocHttpTests.anonymous}。 */
    private static MockHttpServletRequestBuilder anonymous(MockHttpServletRequestBuilder builder, String path) {
        return builder.with(request -> {
            request.setServletPath(path);
            return request;
        });
    }
}
