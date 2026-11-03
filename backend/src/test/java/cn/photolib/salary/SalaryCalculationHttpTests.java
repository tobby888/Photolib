package cn.photolib.salary;

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
import org.springframework.test.web.servlet.MvcResult;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口权限：工资计算、查看和导出明细都要 {@code SALARY_CALCULATE}（默认管理员和部长有），只能看统计的账号 403。
 */
@SpringBootTest
@Transactional
class SalaryCalculationHttpTests {
    private static final long MINISTER_ID = 9_821L;
    private static final long MEMBER_ID = 9_822L;

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
                VALUES (:m, 'salary-http-minister', 'hash', '工资接口部长', 'MINISTER', NULL, TRUE, FALSE),
                       (:u, 'salary-http-member', 'hash', '工资接口成员', 'MINISTER', NULL, TRUE, FALSE)
                """).param("m", MINISTER_ID).param("u", MEMBER_ID).update();
        when(authService.authenticate("minister")).thenReturn(new AuthService.SessionAuthentication(2L,
                new AuthenticatedUser(MINISTER_ID, "salary-http-minister", "工资接口部长", UserRole.MINISTER, null, false,
                        2L, "MINISTER", "部长", DataScope.GLOBAL,
                        Set.of(PermissionCode.STATISTICS_DOWNLOAD, PermissionCode.SALARY_CALCULATE), Set.of())));
        when(authService.authenticate("member")).thenReturn(new AuthService.SessionAuthentication(3L,
                new AuthenticatedUser(MEMBER_ID, "salary-http-member", "工资接口成员", UserRole.MINISTER, null, false,
                        13L, "STATS_ONLY", "只看统计", DataScope.GLOBAL,
                        Set.of(PermissionCode.STATISTICS_DOWNLOAD), Set.of())));
    }

    @Test
    void calculatingViewingAndExportingNeedTheSalaryPermission() throws Exception {
        String body = "{\"from\":\"2026-10-01\",\"to\":\"2026-10-31\"}";
        mvc.perform(as("member", post("/api/v1/salary-calculations").contentType(MediaType.APPLICATION_JSON)
                .content(body))).andExpect(status().isForbidden());
        mvc.perform(as("member", get("/api/v1/salary-calculations"))).andExpect(status().isForbidden());

        MvcResult created = mvc.perform(as("minister", post("/api/v1/salary-calculations")
                        .contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.from").value("2026-10-01"))
                .andExpect(jsonPath("$.data.hourlyRate").value(20.0))
                .andReturn();
        String json = created.getResponse().getContentAsString();
        long id = Long.parseLong(json.replaceAll("(?s).*\"id\":(\\d+).*", "$1"));

        mvc.perform(as("member", get("/api/v1/salary-calculations/" + id))).andExpect(status().isForbidden());
        mvc.perform(as("member", get("/api/v1/salary-calculations/" + id + "/export"))).andExpect(status().isForbidden());
        mvc.perform(as("minister", get("/api/v1/salary-calculations/" + id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.calculation.id").value(id))
                .andExpect(jsonPath("$.data.projects").isArray())
                .andExpect(jsonPath("$.data.members").isArray());
        MvcResult file = mvc.perform(as("minister", get("/api/v1/salary-calculations/" + id + "/export")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("spreadsheetml")))
                .andExpect(header().string("Content-Disposition", containsString("filename*=UTF-8''")))
                .andReturn();
        // XLSX 是 zip：PK 开头。
        assertThat(file.getResponse().getContentAsByteArray()).startsWith(0x50, 0x4B);

        // 写操作留了操作日志，模块是「工资计算」。
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM audit_log WHERE resource_type='SALARY-CALCULATIONS' AND operator_id=:u
                """).param("u", MINISTER_ID).query(Long.class).single()).isGreaterThanOrEqualTo(2L);

        mvc.perform(as("member", delete("/api/v1/salary-calculations/" + id))).andExpect(status().isForbidden());
        mvc.perform(as("minister", delete("/api/v1/salary-calculations/" + id))).andExpect(status().isOk());
        mvc.perform(as("minister", get("/api/v1/salary-calculations/" + id))).andExpect(status().isNotFound());
    }

    private MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + token).with(request -> {
            request.setServletPath(request.getRequestURI());
            return request;
        });
    }
}
