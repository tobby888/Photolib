package cn.photolib.worklog;

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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code PUT /worklogs/{id}/correction} 的接口层：新加的状态、关联需求、自定义选题从 JSON 绑定进来，
 * 只有权限组 ADMIN 能调，改了什么写进审计详情。
 */
@SpringBootTest
@Transactional
class WorklogCorrectionHttpTests {
    private static final long ADMIN_ID = 9_871L;
    private static final long MINISTER_ID = 9_872L;
    private static final long WORKLOG_ID = 9_873L;
    private static final long REQUEST_ID = 9_874L;
    private static final long OTHER_REQUEST_ID = 9_875L;

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
                VALUES (:a, 'corr-http-admin', 'hash', '更正接口管理员', 'ADMIN', NULL, TRUE, FALSE),
                       (:m, 'corr-http-minister', 'hash', '更正接口部长', 'MINISTER', NULL, TRUE, FALSE)
                """).param("a", ADMIN_ID).param("m", MINISTER_ID).update();
        jdbc.sql("INSERT INTO campus(id, code, name) VALUES (9876, 'CORR-HTTP', '更正接口校区')").update();
        jdbc.sql("INSERT INTO project(id, title, status, created_by) VALUES (9877, '更正接口选题', 'ACTIVE', :u)")
                .param("u", ADMIN_ID).update();
        jdbc.sql("""
                INSERT INTO photo_request(id, project_id, title, campus_id, deadline, status, created_by)
                VALUES (:r1, 9877, '原需求', 9876, :deadline, 'ACCEPTED', :u),
                       (:r2, 9877, '新需求', 9876, :deadline, 'ACCEPTED', :u)
                """).param("r1", REQUEST_ID).param("r2", OTHER_REQUEST_ID)
                .param("deadline", LocalDateTime.now().plusDays(3)).param("u", ADMIN_ID).update();
        jdbc.sql("""
                INSERT INTO worklog(id, request_id, user_id, work_date, shooting_minutes, retouching_minutes,
                                    member_name, member_student_id, status)
                VALUES (:id, :r, :u, :d, 60, 0, '张三', 'S2026-0001', 'SUBMITTED')
                """).param("id", WORKLOG_ID).param("r", REQUEST_ID).param("u", MINISTER_ID)
                .param("d", LocalDate.now().minusDays(1)).update();
        when(authService.authenticate("admin")).thenReturn(new AuthService.SessionAuthentication(2L,
                new AuthenticatedUser(ADMIN_ID, "corr-http-admin", "更正接口管理员", UserRole.ADMIN, null, false,
                        1L, "ADMIN", "管理员", DataScope.GLOBAL, EnumSet.allOf(PermissionCode.class), Set.of())));
        when(authService.authenticate("minister")).thenReturn(new AuthService.SessionAuthentication(3L,
                new AuthenticatedUser(MINISTER_ID, "corr-http-minister", "更正接口部长", UserRole.MINISTER, null,
                        false, 2L, "MINISTER", "部长", DataScope.GLOBAL, EnumSet.allOf(PermissionCode.class),
                        Set.of())));
    }

    @Test
    void adminChangesStatusRequestAndCustomProjectOverHttp() throws Exception {
        String body = """
                {"memberName":"张三","memberStudentId":"S2026-0001","workDate":"%s",
                 "shootingMinutes":60,"retouchingMinutes":0,"remark":"","version":1,
                 "status":"CONFIRMED","requestId":%d,"customProjectTitle":"迎新晚会"}
                """.formatted(LocalDate.now().minusDays(1), OTHER_REQUEST_ID);

        mvc.perform(as("minister", put("/api/v1/worklogs/" + WORKLOG_ID + "/correction")
                .contentType(MediaType.APPLICATION_JSON).content(body))).andExpect(status().isForbidden());

        mvc.perform(as("admin", put("/api/v1/worklogs/" + WORKLOG_ID + "/correction")
                        .contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.requestId").value(OTHER_REQUEST_ID))
                .andExpect(jsonPath("$.data.customProjectTitle").value("迎新晚会"))
                .andExpect(jsonPath("$.data.confirmedBy").value(ADMIN_ID));

        String detail = jdbc.sql("""
                SELECT CAST(detail_json AS VARCHAR(4000)) FROM audit_log
                WHERE operator_id=:u AND resource_id=:id ORDER BY id DESC LIMIT 1
                """).param("u", ADMIN_ID).param("id", String.valueOf(WORKLOG_ID)).query(String.class).single();
        assertThat(detail).contains("status").contains("CONFIRMED").contains("customProjectTitle")
                .contains("requestId");
    }

    /** 填报接口同样收自定义选题（管理员有「向任意需求申报工时」，不用是参与人）。 */
    @Test
    void filingAcceptsACustomProject() throws Exception {
        jdbc.sql("""
                INSERT INTO campus_member(id, campus_id, student_id, name, enabled)
                VALUES (9878, 9876, 'S2026-0009', '李四', TRUE)
                """).update();
        String body = """
                {"workDate":"%s","memberContactId":9878,"shootingMinutes":30,"retouchingMinutes":0,
                 "remark":"","status":"SUBMITTED","customProjectTitle":" 迎新晚会 "}
                """.formatted(LocalDate.now().minusDays(1));

        mvc.perform(as("admin", post("/api/v1/requests/" + REQUEST_ID + "/worklogs")
                        .contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customProjectTitle").value("迎新晚会"))
                .andExpect(jsonPath("$.data.memberName").value("李四"));
        mvc.perform(as("admin", post("/api/v1/requests/" + REQUEST_ID + "/worklogs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace(" 迎新晚会 ", "选".repeat(201)))))
                .andExpect(status().isBadRequest());
    }

    private MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + token).with(request -> {
            request.setServletPath(request.getRequestURI());
            return request;
        });
    }
}
