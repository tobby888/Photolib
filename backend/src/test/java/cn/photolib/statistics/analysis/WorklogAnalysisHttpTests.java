package cn.photolib.statistics.analysis;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工时分析接口走完整过滤器链：要 {@code STATISTICS_DOWNLOAD}；JSON 里写了白名单之外的字段或
 * 运算符直接 400；这个只读的 POST 不写审计。
 */
@SpringBootTest
@Transactional
class WorklogAnalysisHttpTests {
    private static final long ADMIN_ID = 9_701L;
    private static final long SUBMITTER_ID = 9_702L;

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
                VALUES (:a, 'wa-http-admin', 'hash', '分析管理员', 'ADMIN', NULL, TRUE, FALSE),
                       (:s, 'wa-http-submitter', 'hash', '只填工时', 'MINISTER', NULL, TRUE, FALSE)
                """).param("a", ADMIN_ID).param("s", SUBMITTER_ID).update();
        when(authService.authenticate("admin")).thenReturn(new AuthService.SessionAuthentication(1L,
                new AuthenticatedUser(ADMIN_ID, "wa-http-admin", "分析管理员", UserRole.ADMIN, null, false)));
        when(authService.authenticate("submitter")).thenReturn(new AuthService.SessionAuthentication(2L,
                new AuthenticatedUser(SUBMITTER_ID, "wa-http-submitter", "只填工时", UserRole.MINISTER, null, false,
                        13L, "WORKLOG_ONLY", "只填工时组", DataScope.GLOBAL,
                        Set.of(PermissionCode.WORKLOG_SUBMIT), Set.of())));
    }

    @Test
    void analysisRequiresTheStatisticsPermissionAndIsNotAudited() throws Exception {
        String body = """
                {"from":"2022-01-01","to":"2022-01-31","groupBy":"CAMPUS",
                 "filter":{"logic":"AND","children":[
                   {"field":"STATUS","operator":"IN","values":["CONFIRMED","SUBMITTED"]},
                   {"logic":"OR","not":true,"children":[{"field":"MEMBER_NAME","operator":"CONTAINS","values":["'--"]}]}
                 ]}}
                """;
        long auditBefore = auditCount();

        mvc.perform(as("admin", post("/api/v1/statistics/worklog-analysis")
                        .contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groupBy").value("CAMPUS"))
                .andExpect(jsonPath("$.data.trend.length()").value(31));
        mvc.perform(as("submitter", post("/api/v1/statistics/worklog-analysis")
                        .contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isForbidden());
        mvc.perform(as("admin", get("/api/v1/statistics/worklog-analysis/options")
                        .param("from", "2022-01-01").param("to", "2022-01-31")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.campuses").isArray());

        assertThat(auditCount()).isEqualTo(auditBefore);
    }

    @Test
    void fieldsAndOperatorsOutsideTheWhitelistAreRejectedAsBadRequests() throws Exception {
        mvc.perform(as("admin", post("/api/v1/statistics/worklog-analysis")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"from":"2022-01-01","to":"2022-01-31",
                                 "filter":{"field":"1=1) OR (1","operator":"EQ","values":["x"]}}
                                """)))
                .andExpect(status().isBadRequest());
        mvc.perform(as("admin", post("/api/v1/statistics/worklog-analysis")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"from":"2022-01-01","to":"2022-01-31",
                                 "filter":{"field":"CAMPUS","operator":"EQ","values":["1 OR 1=1"]}}
                                """)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("编号格式不对")));
    }

    private long auditCount() {
        return jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE detail_json LIKE '%worklog-analysis%'")
                .query(Long.class).single();
    }

    private MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + token).with(request -> {
            request.setServletPath(request.getRequestURI());
            return request;
        });
    }
}
