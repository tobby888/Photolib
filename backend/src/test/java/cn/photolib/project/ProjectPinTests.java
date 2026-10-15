package cn.photolib.project;

import cn.photolib.auth.AuthService;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.permission.DataScope;
import cn.photolib.permission.PermissionCode;
import cn.photolib.project.model.ProjectEntity;
import cn.photolib.project.model.ProjectStatus;
import cn.photolib.project.model.ProjectType;
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

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 选题置顶（V65）：每个账号各置顶各的，置顶的排在列表最前，凭据就是「看得到这个选题」。
 */
@SpringBootTest
@Transactional
class ProjectPinTests {
    private static final long OWNER_ID = 9_710L;
    private static final long OTHER_ID = 9_711L;
    private static final long OUTSIDER_ID = 9_712L;

    @Autowired private WebApplicationContext context;
    @Autowired private ProjectService projectService;
    @Autowired private JdbcClient jdbc;
    /** 只为了给过滤器发一个"有效令牌"，不真的建会话。 */
    @MockitoBean private AuthService authService;

    private AuthenticatedUser owner;
    private AuthenticatedUser other;
    private AuthenticatedUser outsider;
    private ProjectEntity older;
    private ProjectEntity newer;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (9710, 'pin-owner', 'hash', '置顶部长', 'MINISTER', TRUE, FALSE),
                       (9711, 'pin-other', 'hash', '另一位部长', 'MINISTER', TRUE, FALSE),
                       (9712, 'pin-outsider', 'hash', '没参与的人', 'CAMPUS_MANAGER', TRUE, FALSE)
                """).update();
        owner = new AuthenticatedUser(OWNER_ID, "pin-owner", "置顶部长", UserRole.MINISTER, null, false);
        other = new AuthenticatedUser(OTHER_ID, "pin-other", "另一位部长", UserRole.MINISTER, null, false);
        // 只有 PROJECT_VIEW：只看得到自己参与过的选题。
        outsider = new AuthenticatedUser(OUTSIDER_ID, "pin-outsider", "没参与的人", UserRole.CAMPUS_MANAGER,
                null, false, -1L, "VIEW_ONLY", "只看参与", DataScope.GLOBAL,
                Set.of(PermissionCode.PROJECT_VIEW), Set.of());

        older = projectService.create("置顶测试·较早", "说明", ProjectStatus.ACTIVE, List.of(),
                ProjectType.CREATION, owner);
        newer = projectService.create("置顶测试·较新", "说明", ProjectStatus.ACTIVE, List.of(),
                ProjectType.EVENT, owner);
        // 同一事务里 created_at 可能相同，显式拉开，好让「不置顶时按创建时间倒序」确定。
        jdbc.sql("UPDATE project SET created_at = TIMESTAMP '2030-01-01 00:00:00' WHERE id = :id")
                .param("id", older.getId()).update();
        jdbc.sql("UPDATE project SET created_at = TIMESTAMP '2030-01-02 00:00:00' WHERE id = :id")
                .param("id", newer.getId()).update();
    }

    @Test
    void aPinnedProjectComesFirstOnlyForTheOneWhoPinnedIt() {
        assertThat(ourIds(owner)).containsExactly(newer.getId(), older.getId());

        projectService.pin(older.getId(), owner);

        assertThat(ourIds(owner)).containsExactly(older.getId(), newer.getId());
        assertThat(projectService.list(1, 100, "置顶测试", null, owner).items())
                .filteredOn(project -> project.getId().equals(older.getId()))
                .singleElement().satisfies(project -> assertThat(project.getPinned()).isTrue());
        assertThat(projectService.getDetail(older.getId(), owner).pinned()).isTrue();

        // 别人的列表不受影响：置顶是个人书签，不是公告。
        assertThat(ourIds(other)).containsExactly(newer.getId(), older.getId());
        assertThat(projectService.getDetail(older.getId(), other).pinned()).isFalse();

        projectService.unpin(older.getId(), owner);
        assertThat(ourIds(owner)).containsExactly(newer.getId(), older.getId());
    }

    @Test
    void pinningTwiceIsIdempotentAndUnpinningSomethingNeverPinnedIsHarmless() {
        projectService.pin(older.getId(), owner);
        projectService.pin(older.getId(), owner);
        assertThat(pinCount(OWNER_ID)).isEqualTo(1);

        projectService.unpin(newer.getId(), owner);
        assertThat(pinCount(OWNER_ID)).isEqualTo(1);
    }

    @Test
    void onlyProjectsYouCanSeeCanBePinned() {
        assertThatThrownBy(() -> projectService.pin(older.getId(), outsider))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权查看");
        assertThat(pinCount(OUTSIDER_ID)).isZero();
    }

    @Test
    void thereIsACapOnHowManyProjectsOneAccountPins() {
        for (int index = 0; index < ProjectService.MAX_PINS; index++) {
            ProjectEntity project = projectService.create("上限测试 " + index, "说明", ProjectStatus.ACTIVE, owner);
            projectService.pin(project.getId(), owner);
        }
        assertThatThrownBy(() -> projectService.pin(older.getId(), owner))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最多置顶");
        // 已经置顶的再点一次不算新的一个，不该撞上限。
        Long pinnedId = jdbc.sql("SELECT project_id FROM project_pin WHERE user_id = :id LIMIT 1")
                .param("id", OWNER_ID).query(Long.class).single();
        projectService.pin(pinnedId, owner);
    }

    @Test
    void thePinEndpointsWorkOverHttpAndTheListCarriesTheFlag() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(authService.authenticate(anyString()))
                .thenReturn(new AuthService.SessionAuthentication(9_710L, owner));

        mvc.perform(authenticated(put("/api/v1/projects/" + older.getId() + "/pin")))
                .andExpect(status().isOk());
        mvc.perform(authenticated(get("/api/v1/projects").param("keyword", "置顶测试")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(older.getId()))
                .andExpect(jsonPath("$.data.items[0].pinned").value(true))
                .andExpect(jsonPath("$.data.items[1].pinned").value(false))
                .andExpect(jsonPath("$.data.total").value(2));
        // 写操作照常进审计：资源类型和 id 从路径里取。
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM audit_log
                WHERE resource_type = 'PROJECTS' AND resource_id = :id AND detail_json LIKE '%/pin%'
                """).param("id", String.valueOf(older.getId())).query(Long.class).single()).isEqualTo(1);

        mvc.perform(authenticated(delete("/api/v1/projects/" + older.getId() + "/pin")))
                .andExpect(status().isOk());
        assertThat(pinCount(OWNER_ID)).isZero();
    }

    private List<Long> ourIds(AuthenticatedUser user) {
        return projectService.list(1, 100, "置顶测试", null, user).items().stream()
                .map(ProjectEntity::getId).toList();
    }

    private long pinCount(long userId) {
        return jdbc.sql("SELECT COUNT(*) FROM project_pin WHERE user_id = :id")
                .param("id", userId).query(Long.class).single();
    }

    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer token").with(request -> {
            request.setServletPath(request.getRequestURI());
            return request;
        });
    }
}
