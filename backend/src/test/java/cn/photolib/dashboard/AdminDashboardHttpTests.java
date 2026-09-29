package cn.photolib.dashboard;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 数据面板的接口走完整过滤器链：谁能看、流量计数器是不是真的挂在请求前面、布局的保存 /
 * 乐观锁 / 恢复默认，以及写操作进了审计。
 */
@SpringBootTest
@Transactional
class AdminDashboardHttpTests {
    private static final long ADMIN_ID = 97_301L;
    private static final long MINISTER_ID = 97_302L;
    private static final String LAYOUT = """
            {"layout":{"refreshSeconds":10,"widgets":[
              {"id":"users","type":"stat","title":"注册用户","size":"small","metric":"users.total"},
              {"id":"qps","type":"trend","size":"medium","metric":"traffic.qps","range":"1h"},
              {"id":"memo","type":"note","title":"值班","size":"small","text":"周五备份演练"}
            ]},"version":%s}
            """;

    private MockMvc mvc;

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcClient jdbc;
    @Autowired private RequestMetricsFilter metricsFilter;
    @MockitoBean private AuthService authService;

    @BeforeEach
    void setUp() {
        // 计数过滤器在真实部署里由 Spring Boot 注册成 Servlet 过滤器；MockMvc 不会自动带上，手动挂在最外层。
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(metricsFilter).apply(springSecurity()).build();
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, campus_id, enabled, must_change_password)
                VALUES (:adminId, 'dashboard-admin', 'hash', '面板管理员', 'ADMIN', NULL, TRUE, FALSE),
                       (:ministerId, 'dashboard-minister', 'hash', '部长', 'MINISTER', NULL, TRUE, FALSE)
                """).param("adminId", ADMIN_ID).param("ministerId", MINISTER_ID).update();
        when(authService.authenticate("admin")).thenReturn(new AuthService.SessionAuthentication(1L,
                new AuthenticatedUser(ADMIN_ID, "dashboard-admin", "面板管理员", UserRole.ADMIN, null, false)));
        when(authService.authenticate("minister")).thenReturn(new AuthService.SessionAuthentication(2L,
                new AuthenticatedUser(MINISTER_ID, "dashboard-minister", "部长", UserRole.MINISTER, null, false)));
    }

    @Test
    void onlyAdministratorsCanReadTheDashboard() throws Exception {
        mvc.perform(anonymous(get("/api/v1/admin-dashboard/metrics"))).andExpect(status().isUnauthorized());
        mvc.perform(as("minister", get("/api/v1/admin-dashboard/metrics"))).andExpect(status().isForbidden());
        mvc.perform(as("minister", get("/api/v1/admin-dashboard/layout"))).andExpect(status().isForbidden());
        mvc.perform(as("minister", put("/api/v1/admin-dashboard/layout")
                        .contentType(MediaType.APPLICATION_JSON).content(LAYOUT.formatted("null"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void metricsCoverUsersTrafficAndRuntime() throws Exception {
        // 前面被拒的请求也要算进流量：计数器排在安全过滤器之前。
        mvc.perform(anonymous(get("/api/v1/admin-dashboard/metrics"))).andExpect(status().isUnauthorized());
        mvc.perform(as("admin", get("/api/v1/admin-dashboard/metrics"))).andExpect(status().isOk());

        mvc.perform(as("admin", get("/api/v1/admin-dashboard/metrics")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.business.users.total").isNumber())
                .andExpect(jsonPath("$.data.business.dailyUsers.length()").value(30))
                .andExpect(jsonPath("$.data.traffic.lastMinute.clientErrors").isNumber())
                .andExpect(jsonPath("$.data.traffic.series['1h'].length()").value(60))
                .andExpect(jsonPath("$.data.traffic.topEndpoints[*].endpoint")
                        .value(hasItem("GET /api/v1/admin-dashboard/metrics")))
                .andExpect(jsonPath("$.data.activeUsers.last5Minutes").isNumber())
                .andExpect(jsonPath("$.data.runtime.heapUsed").isNumber())
                .andExpect(jsonPath("$.data.runtime.processors").isNumber());
    }

    @Test
    void layoutIsSavedPerAdministratorWithOptimisticLockingAndAudit() throws Exception {
        mvc.perform(as("admin", get("/api/v1/admin-dashboard/layout")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.layout").doesNotExist());

        mvc.perform(as("admin", put("/api/v1/admin-dashboard/layout")
                        .contentType(MediaType.APPLICATION_JSON).content(LAYOUT.formatted("null"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.layout.widgets.length()").value(3))
                .andExpect(jsonPath("$.data.layout.widgets[2].text").value("周五备份演练"));

        // 拿着旧版本号（或者不带版本号）再存一次：别处已经改过，拒绝而不是覆盖。
        mvc.perform(as("admin", put("/api/v1/admin-dashboard/layout")
                        .contentType(MediaType.APPLICATION_JSON).content(LAYOUT.formatted("null"))))
                .andExpect(status().isConflict());
        mvc.perform(as("admin", put("/api/v1/admin-dashboard/layout")
                        .contentType(MediaType.APPLICATION_JSON).content(LAYOUT.formatted("1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));
        mvc.perform(as("admin", put("/api/v1/admin-dashboard/layout")
                        .contentType(MediaType.APPLICATION_JSON).content(LAYOUT.formatted("1"))))
                .andExpect(status().isConflict());

        var details = jdbc.sql("""
                SELECT detail_json FROM audit_log
                WHERE resource_type = 'ADMIN-DASHBOARD' AND operator_id = :operator AND action = 'PUT'
                ORDER BY id
                """).param("operator", ADMIN_ID).query(String.class).list();
        assertThat(details).hasSize(4);
        // H2 把 JSON 列读回来是带引号转义的字符串，只比对关键字。
        assertThat(details.getFirst()).contains("widgets").contains("3").contains("200");
        assertThat(details.get(1)).contains("409").doesNotContain("widgets");

        mvc.perform(as("admin", delete("/api/v1/admin-dashboard/layout"))).andExpect(status().isOk());
        mvc.perform(as("admin", get("/api/v1/admin-dashboard/layout")))
                .andExpect(jsonPath("$.data.layout").doesNotExist())
                .andExpect(jsonPath("$.data.version").doesNotExist());
    }

    @Test
    void malformedLayoutsAreRejected() throws Exception {
        String[] invalid = {
                // 不认识的小面板类型
                LAYOUT.formatted("null").replace("\"type\":\"note\"", "\"type\":\"script\""),
                // 编号重复
                LAYOUT.formatted("null").replace("\"id\":\"qps\"", "\"id\":\"users\""),
                // 指标类小面板没选指标
                LAYOUT.formatted("null").replace(",\"metric\":\"users.total\"", ""),
                // 不在清单里的刷新间隔
                LAYOUT.formatted("null").replace("\"refreshSeconds\":10", "\"refreshSeconds\":1"),
                // 尺寸不在白名单
                LAYOUT.formatted("null").replace("\"size\":\"small\",\"metric\"", "\"size\":\"huge\",\"metric\""),
        };
        for (String body : invalid) {
            mvc.perform(as("admin", put("/api/v1/admin-dashboard/layout")
                            .contentType(MediaType.APPLICATION_JSON).content(body)))
                    .andExpect(status().isBadRequest());
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM admin_dashboard_layout WHERE user_id = :id")
                .param("id", ADMIN_ID).query(Long.class).single()).isZero();
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
