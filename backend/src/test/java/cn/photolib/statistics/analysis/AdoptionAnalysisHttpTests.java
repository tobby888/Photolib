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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.Set;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 被引分析接口走完整过滤器链：要 {@code STATISTICS_DOWNLOAD}，日期范围不对给 400。 */
@SpringBootTest
@Transactional
class AdoptionAnalysisHttpTests {
    private static final long ADMIN_ID = 9_801L;
    private static final long SUBMITTER_ID = 9_802L;

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
                VALUES (:a, 'aa-http-admin', 'hash', '分析管理员', 'ADMIN', NULL, TRUE, FALSE),
                       (:s, 'aa-http-submitter', 'hash', '只填工时', 'MINISTER', NULL, TRUE, FALSE)
                """).param("a", ADMIN_ID).param("s", SUBMITTER_ID).update();
        when(authService.authenticate("admin")).thenReturn(new AuthService.SessionAuthentication(1L,
                new AuthenticatedUser(ADMIN_ID, "aa-http-admin", "分析管理员", UserRole.ADMIN, null, false)));
        when(authService.authenticate("submitter")).thenReturn(new AuthService.SessionAuthentication(2L,
                new AuthenticatedUser(SUBMITTER_ID, "aa-http-submitter", "只填工时", UserRole.MINISTER, null, false,
                        13L, "WORKLOG_ONLY", "只填工时组", DataScope.GLOBAL,
                        Set.of(PermissionCode.WORKLOG_SUBMIT), Set.of())));
    }

    @Test
    void requiresTheStatisticsPermission() throws Exception {
        mvc.perform(as("admin", get("/api/v1/statistics/adoption-analysis")
                        .param("from", "2005-01-01").param("to", "2005-01-31")
                        .param("basis", "PROJECT_COMPLETED").param("granularity", "DAY")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.basis").value("PROJECT_COMPLETED"))
                .andExpect(jsonPath("$.data.weekdays.length()").value(7))
                .andExpect(jsonPath("$.data.summary.photoCount").value(0));
        mvc.perform(as("admin", get("/api/v1/statistics/adoption-analysis/options")
                        .param("from", "2005-01-01").param("to", "2005-01-31")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.photographers").isArray());
        mvc.perform(as("submitter", get("/api/v1/statistics/adoption-analysis")
                        .param("from", "2005-01-01").param("to", "2005-01-31")))
                .andExpect(status().isForbidden());
        mvc.perform(as("admin", get("/api/v1/statistics/adoption-analysis")
                        .param("from", "2005-02-01").param("to", "2005-01-31")))
                .andExpect(status().isBadRequest());
    }

    private MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + token).with(request -> {
            request.setServletPath(request.getRequestURI());
            return request;
        });
    }
}
