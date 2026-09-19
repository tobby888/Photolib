package cn.photolib.mcp;

import cn.photolib.auth.AuthService;
import cn.photolib.auth.AuthenticatedUser;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MCP 配对接口走完整过滤器链的行为。
 *
 * <p>这里验的是服务层单测看不到的那一段：{@code SecurityConfig} 的 permitAll 清单
 * 是不是**恰好**覆盖了客户端那三条（发起、换令牌、续期），而查看与批准两条仍然要求
 * 登录身份。放宽一格，任何人都能替别人批准配对；收紧一格，客户端在拿到令牌之前就
 * 被 401 挡住，整条流程走不通。
 */
@SpringBootTest
@Transactional
class McpAuthorizationHttpTests {
    private static final long MEMBER_ID = 9_701L;

    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcClient jdbc;
    /** 只为了给过滤器发一个"有效令牌"，不真的建会话。 */
    @MockitoBean private AuthService authService;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (:id, 'mcp-http-member', 'hash', 'MCP 成员', 'MINISTER', TRUE, FALSE)
                """).param("id", MEMBER_ID).update();
        AuthenticatedUser member = new AuthenticatedUser(MEMBER_ID, "mcp-http-member",
                "MCP 成员", UserRole.MINISTER, null, false);
        when(authService.authenticate(anyString()))
                .thenReturn(new AuthService.SessionAuthentication(1L, member));
        // 会话签发本身由 McpAuthorizationServiceTests 用真实的 AuthService 验；
        // 这里只关心过滤器链，所以给一份固定的令牌对，免得被真实签发的随机值干扰。
        when(authService.issueForPairedClient(MEMBER_ID))
                .thenReturn(new AuthService.TokenPair("access-token", "refresh-token", 900, member));
    }

    @Test
    void clientEndpoints_areReachableWithoutAToken() throws Exception {
        JsonNode pairing = open();

        assertThat(pairing.get("requestId").asText()).isNotBlank();
        assertThat(pairing.get("deviceCode").asText()).isNotBlank();
        assertThat(pairing.get("userCode").asText()).matches("[A-Z2-9]{4}-[A-Z2-9]{4}");
        assertThat(pairing.get("verificationPath").asText()).isEqualTo("/mcp/authorize");

        // 换令牌同样匿名可调，凭据是客户端自己那份 deviceCode。
        mvc.perform(post("/api/v1/auth/mcp/token").contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode()
                                .put("requestId", pairing.get("requestId").asText())
                                .put("deviceCode", pairing.get("deviceCode").asText()).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    void viewingAndApproving_requireALoggedInSession() throws Exception {
        JsonNode pairing = open();
        String requestId = pairing.get("requestId").asText();

        mvc.perform(get("/api/v1/auth/mcp/authorizations/" + requestId))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/mcp/authorizations/" + requestId + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode()
                                .put("userCode", pairing.get("userCode").asText()).toString()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/mcp/authorizations/" + requestId + "/deny"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void approvalPage_getsTheClientDetailsButNeverTheUserCode() throws Exception {
        JsonNode pairing = open();
        String requestId = pairing.get("requestId").asText();

        String body = mvc.perform(get("/api/v1/auth/mcp/authorizations/" + requestId)
                        .header("Authorization", "Bearer any-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clientName").value("Claude Code"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(pairing.get("userCode").asText());
        assertThat(body).doesNotContain(pairing.get("deviceCode").asText());
    }

    /** 整条链路：批准页批准，客户端随后换到令牌。 */
    @Test
    void approvedInTheBrowser_thenRedeemedByTheClient() throws Exception {
        JsonNode pairing = open();
        String requestId = pairing.get("requestId").asText();

        mvc.perform(post("/api/v1/auth/mcp/authorizations/" + requestId + "/approve")
                        .header("Authorization", "Bearer any-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode()
                                .put("userCode", pairing.get("userCode").asText()).toString()))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/auth/mcp/token").contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode()
                                .put("requestId", requestId)
                                .put("deviceCode", pairing.get("deviceCode").asText()).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.data.user.id").value(MEMBER_ID));
    }

    /**
     * 批准这一步要进审计日志。事后要能回答的问题只有一个：谁把一个令牌交给了哪个客户端。
     * 轮询那条刻意不记（见 {@code AuditInterceptor}），否则一次配对能刷出几百条重复记录。
     */
    @Test
    void approval_isAudited_whilePollingIsNot() throws Exception {
        JsonNode pairing = open();
        String requestId = pairing.get("requestId").asText();

        mvc.perform(post("/api/v1/auth/mcp/authorizations/" + requestId + "/approve")
                        .header("Authorization", "Bearer any-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode()
                                .put("userCode", pairing.get("userCode").asText()).toString()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/mcp/token").contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode()
                                .put("requestId", requestId)
                                .put("deviceCode", pairing.get("deviceCode").asText()).toString()))
                .andExpect(status().isOk());

        assertThat(auditCount("%/auth/mcp/authorizations/" + requestId + "/approve%")).isEqualTo(1);
        assertThat(auditCount("%/auth/mcp/token%")).isZero();
    }

    private long auditCount(String pathPattern) {
        return jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE detail_json LIKE :pattern")
                .param("pattern", pathPattern).query(Long.class).single();
    }

    private JsonNode open() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/mcp/authorizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode()
                                .put("clientName", "Claude Code")
                                .put("deviceLabel", "测试机").toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }
}
