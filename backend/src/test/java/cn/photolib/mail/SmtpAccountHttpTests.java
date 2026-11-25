package cn.photolib.mail;

import cn.photolib.auth.AuthService;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.permission.DataScope;
import cn.photolib.permission.PermissionCode;
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

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理员发信邮箱管理接口权限、校验（含登录名与发件地址分离）、密码不回传及排序测试。
 */
@SpringBootTest
@Transactional
class SmtpAccountHttpTests {
    private static final long ADMIN_ID = 9_851L;
    private static final long MINISTER_ID = 9_852L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private MockMvc mvc;

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcClient jdbc;
    @MockitoBean private AuthService authService;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("DELETE FROM smtp_account").update();
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, campus_id, enabled, must_change_password, email)
                VALUES (:a, 'smtp-http-admin', 'hash', 'SMTP管理员', 'ADMIN', NULL, TRUE, FALSE, 'admin@studio.cn'),
                       (:m, 'smtp-http-minister', 'hash', 'SMTP部长', 'MINISTER', NULL, TRUE, FALSE, 'm@studio.cn')
                """).param("a", ADMIN_ID).param("m", MINISTER_ID).update();
        when(authService.authenticate("admin")).thenReturn(new AuthService.SessionAuthentication(101L,
                new AuthenticatedUser(ADMIN_ID, "smtp-http-admin", "SMTP管理员", UserRole.ADMIN, null, false)));
        when(authService.authenticate("minister")).thenReturn(new AuthService.SessionAuthentication(102L,
                new AuthenticatedUser(MINISTER_ID, "smtp-http-minister", "SMTP部长", UserRole.MINISTER, null, false,
                        2L, "MINISTER", "部长", DataScope.GLOBAL,
                        Set.of(PermissionCode.SETTLEMENT_MAIL_SEND), Set.of())));
    }

    @Test
    void onlyAdminsCanManageSmtpAccounts() throws Exception {
        mvc.perform(as("minister", get("/api/v1/smtp-accounts")))
                .andExpect(status().isForbidden());
        mvc.perform(as("minister", post("/api/v1/smtp-accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"主邮箱\",\"host\":\"smtp.qq.com\",\"port\":465,\"username\":\"a@qq.com\",\"password\":\"pw\",\"security\":\"SSL\"}")))
                .andExpect(status().isForbidden());
    }

    @Test
    void supportsCrudReorderSeparateFromAddressAndNeverExposesPassword() throws Exception {
        // 登录名不是邮箱且未填发件地址时拒绝。
        mvc.perform(as("admin", post("/api/v1/smtp-accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"SendGrid","host":"smtp.sendgrid.net","port":587,
                                 "username":"apikey","password":"SG.secret-password","security":"STARTTLS","enabled":true}
                                """)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("发件地址")));

        // 补上独立发件地址后创建成功，且响应不含密码。
        String created1 = mvc.perform(as("admin", post("/api/v1/smtp-accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"SendGrid","host":"smtp.sendgrid.net","port":587,
                                 "username":"apikey","password":"SG.secret-password",
                                 "fromAddress":"noreply@studio.cn","fromName":"摄影部","security":"STARTTLS","enabled":true}
                                """)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("SendGrid"))
                .andExpect(jsonPath("$.data.username").value("apikey"))
                .andExpect(jsonPath("$.data.fromAddress").value("noreply@studio.cn"))
                .andExpect(jsonPath("$.data.sender").value("noreply@studio.cn"))
                .andReturn().getResponse().getContentAsString();
        assertThat(created1).doesNotContain("SG.secret-password");
        long id1 = JSON.readTree(created1).path("data").path("id").asLong();

        // 创建第二个常规邮箱（登录名即发件地址）。
        String created2 = mvc.perform(as("admin", post("/api/v1/smtp-accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"QQ 备用","host":"smtp.qq.com","port":465,
                                 "username":"backup@qq.com","password":"auth-code","security":"SSL","enabled":true}
                                """)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sender").value("backup@qq.com"))
                .andReturn().getResponse().getContentAsString();
        long id2 = JSON.readTree(created2).path("data").path("id").asLong();

        // 修改时不传 password 保留原密码。
        mvc.perform(as("admin", put("/api/v1/smtp-accounts/" + id1).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"SendGrid 主通道","host":"smtp.sendgrid.net","port":587,
                                 "username":"apikey","fromAddress":"noreply@studio.cn","security":"STARTTLS","enabled":true}
                                """)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("SendGrid 主通道"));
        String storedSecret = jdbc.sql("SELECT password_secret FROM smtp_account WHERE id=:id")
                .param("id", id1).query(String.class).single();
        assertThat(storedSecret).contains("SG.secret-password");

        // 调整顺序：把 id2 移到前面。
        mvc.perform(as("admin", put("/api/v1/smtp-accounts/order").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + id2 + "," + id1 + "]}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(id2))
                .andExpect(jsonPath("$.data[1].id").value(id1));

        // 列表按新顺序返回且不含密码。
        String listJson = mvc.perform(as("admin", get("/api/v1/smtp-accounts")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accounts[0].id").value(id2))
                .andExpect(jsonPath("$.data.accounts[1].id").value(id1))
                .andReturn().getResponse().getContentAsString();
        assertThat(listJson).doesNotContain("SG.secret-password").doesNotContain("auth-code");

        // 使用本地假 SMTP 服务端验证 /test 接口。
        try (SmtpMailSenderTests.FakeSmtpServer smtp = SmtpMailSenderTests.FakeSmtpServer.start(
                SmtpMailSenderTests.FakeSmtpServer.Mode.ACCEPT)) {
            mvc.perform(as("admin", put("/api/v1/smtp-accounts/" + id2).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"本地测试\",\"host\":\"127.0.0.1\",\"port\":" + smtp.port()
                                    + ",\"username\":\"relay-id\",\"fromAddress\":\"test-sender@studio.cn\",\"security\":\"NONE\",\"enabled\":false}")))
                    .andExpect(status().isOk());

            mvc.perform(as("admin", post("/api/v1/smtp-accounts/" + id2 + "/test")
                            .contentType(MediaType.APPLICATION_JSON).content("{}")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.email").value("admin@studio.cn"))
                    .andExpect(jsonPath("$.data.fromAddress").value("test-sender@studio.cn"));

            assertThat(smtp.received()).hasSize(1);
            assertThat(smtp.received().getFirst().authUsername()).isEqualTo("relay-id");
            assertThat(smtp.received().getFirst().mailFrom()).contains("test-sender@studio.cn");
        }

        // 删除后只剩 1 个。
        mvc.perform(as("admin", delete("/api/v1/smtp-accounts/" + id2)))
                .andExpect(status().isOk());
        JsonNode remaining = JSON.readTree(mvc.perform(as("admin", get("/api/v1/smtp-accounts")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("accounts");
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).path("id").asLong()).isEqualTo(id1);
    }

    private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + token);
    }
}
