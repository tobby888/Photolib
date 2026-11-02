package cn.photolib.settlement;

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

import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口权限：发清算单要 {@code SETTLEMENT_MAIL_SEND}；改模板、用未保存的模板预览只给管理员。
 */
@SpringBootTest
@Transactional
class SettlementMailHttpTests {
    private static final long ADMIN_ID = 9_811L;
    private static final long MINISTER_ID = 9_812L;
    private static final long MEMBER_ID = 9_813L;

    private MockMvc mvc;

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcClient jdbc;
    @MockitoBean private AuthService authService;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, campus_id, enabled, must_change_password, email)
                VALUES (:a, 'sm-http-admin', 'hash', '清算接口管理员', 'ADMIN', NULL, TRUE, FALSE, 'a@example.cn'),
                       (:m, 'sm-http-minister', 'hash', '清算接口部长', 'MINISTER', NULL, TRUE, FALSE, 'm@example.cn'),
                       (:u, 'sm-http-member', 'hash', '清算接口成员', 'CAMPUS_MANAGER', NULL, TRUE, FALSE, 'u@example.cn')
                """).param("a", ADMIN_ID).param("m", MINISTER_ID).param("u", MEMBER_ID).update();
        when(authService.authenticate("admin")).thenReturn(new AuthService.SessionAuthentication(1L,
                new AuthenticatedUser(ADMIN_ID, "sm-http-admin", "清算接口管理员", UserRole.ADMIN, null, false)));
        when(authService.authenticate("minister")).thenReturn(new AuthService.SessionAuthentication(2L,
                new AuthenticatedUser(MINISTER_ID, "sm-http-minister", "清算接口部长", UserRole.MINISTER, null, false,
                        2L, "MINISTER", "部长", DataScope.GLOBAL,
                        Set.of(PermissionCode.STATISTICS_DOWNLOAD, PermissionCode.SETTLEMENT_MAIL_SEND), Set.of())));
        when(authService.authenticate("member")).thenReturn(new AuthService.SessionAuthentication(3L,
                new AuthenticatedUser(MEMBER_ID, "sm-http-member", "清算接口成员", UserRole.MINISTER, null, false,
                        13L, "STATS_ONLY", "只看统计", DataScope.GLOBAL,
                        Set.of(PermissionCode.STATISTICS_DOWNLOAD), Set.of())));
    }

    @Test
    void sendingNeedsTheSettlementPermission() throws Exception {
        mvc.perform(as("member", get("/api/v1/settlement-mails/recipients")
                        .param("from", "2026-10-01").param("to", "2026-10-31")))
                .andExpect(status().isForbidden());
        mvc.perform(as("member", post("/api/v1/settlement-mails/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":\"2026-10-01\",\"to\":\"2026-10-31\",\"userIds\":[" + MEMBER_ID + "]}")))
                .andExpect(status().isForbidden());
        mvc.perform(as("minister", get("/api/v1/settlement-mails/recipients")
                        .param("from", "2026-10-01").param("to", "2026-10-31")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.userId==" + MEMBER_ID + ")].email").value("u@example.cn"));
        mvc.perform(as("minister", get("/api/v1/settlement-mails/status")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").isBoolean());
    }

    @Test
    void ministersPreviewTheCurrentTemplateButOnlyAdminsEditIt() throws Exception {
        String preview = "{\"userId\":" + MEMBER_ID + ",\"from\":\"2026-10-01\",\"to\":\"2026-10-31\"}";
        mvc.perform(as("minister", post("/api/v1/settlement-mails/preview")
                        .contentType(MediaType.APPLICATION_JSON).content(preview)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subject").value(containsString("清算接口成员")))
                .andExpect(jsonPath("$.data.html").value(containsString("data:image/png;base64,")));
        String custom = "{\"userId\":" + MEMBER_ID + ",\"from\":\"2026-10-01\",\"to\":\"2026-10-31\","
                + "\"subject\":\"s\",\"html\":\"<p>{{recipientName}}</p>\"}";
        mvc.perform(as("minister", post("/api/v1/settlement-mails/preview")
                        .contentType(MediaType.APPLICATION_JSON).content(custom)))
                .andExpect(status().isForbidden());
        mvc.perform(as("admin", post("/api/v1/settlement-mails/preview")
                        .contentType(MediaType.APPLICATION_JSON).content(custom)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.html").value("<p>清算接口成员</p>"));
        mvc.perform(as("minister", get("/api/v1/settlement-mails/template")))
                .andExpect(status().isForbidden());
        mvc.perform(as("admin", get("/api/v1/settlement-mails/template")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultHtml").value(containsString("{{worklogTable}}")))
                .andExpect(jsonPath("$.data.variables.recipientName").exists());
    }

    @Test
    void aStatementInTheInboxIsVisibleToItsRecipientAndSendersOnly() throws Exception {
        jdbc.sql("""
                INSERT INTO settlement_mail_batch(status, from_date, to_date, created_by, created_by_name, total_count)
                VALUES ('COMPLETED', '2026-10-01', '2026-10-31', :m, 'minister', 1)
                """).param("m", MINISTER_ID).update();
        long batchId = jdbc.sql("SELECT MAX(id) FROM settlement_mail_batch WHERE created_by=:m")
                .param("m", MINISTER_ID).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO settlement_statement(batch_id, user_id, recipient_name, from_date, to_date, subject,
                                                 html_body, created_by_name)
                VALUES (:b, :u, 'admin', '2026-10-01', '2026-10-31', 'subject-x',
                        '<img src="cid:brand-logo"><p>body-x</p>', 'minister')
                """).param("b", batchId).param("u", ADMIN_ID).update();
        long id = jdbc.sql("SELECT id FROM settlement_statement WHERE batch_id=:b").param("b", batchId)
                .query(Long.class).single();

        // 收件人本人能看，图标换成了可显示的 data: URI；能发清算单的部长也能看；别人当作不存在。
        mvc.perform(as("admin", get("/api/v1/settlement-statements/" + id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subject").value("subject-x"))
                .andExpect(jsonPath("$.data.html").value(containsString("src=\"data:image/png;base64,")));
        mvc.perform(as("minister", get("/api/v1/settlement-statements/" + id))).andExpect(status().isOk());
        mvc.perform(as("member", get("/api/v1/settlement-statements/" + id))).andExpect(status().isNotFound());
        mvc.perform(as("admin", get("/api/v1/settlement-statements/" + (id + 1000)))).andExpect(status().isNotFound());
    }

    private MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + token).with(request -> {
            request.setServletPath(request.getRequestURI());
            return request;
        });
    }
}
