package cn.photolib.project;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.project.model.ProjectStatus;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;

/**
 * 项目服务测试
 * 测试项目创建、状态流转、项目管理等功能
 */
@SpringBootTest
@Transactional
class ProjectServiceTests {
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcClient jdbc;

    private AuthenticatedUser adminUser;
    private AuthenticatedUser ministerUser;
    private AuthenticatedUser managerUser;

    @BeforeEach
    void setUp() {
        // 创建测试用户
        jdbc.sql("INSERT INTO campus (id, code, name, enabled) VALUES (901, 'PROJECT-TEST', '测试校区', true)")
                .update();
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, campus_id, enabled, must_change_password)
                VALUES
                    (100, 'test-admin', 'hash', '测试管理员', 'ADMIN', null, true, false),
                    (101, 'test-minister', 'hash', '测试部长', 'MINISTER', null, true, false),
                    (102, 'test-manager', 'hash', '测试负责人', 'CAMPUS_MANAGER', 901, true, false)
                """).update();

        adminUser = new AuthenticatedUser(
                100L, "test-admin", "测试管理员", UserRole.ADMIN, null, false);
        ministerUser = new AuthenticatedUser(
                101L, "test-minister", "测试部长", UserRole.MINISTER, null, false);
        managerUser = new AuthenticatedUser(
                102L, "test-manager", "测试负责人", UserRole.CAMPUS_MANAGER, 901L, false);
    }

    @Test
    void createProject_shouldReturnProjectWithActiveStatus() {
        // When: 创建新项目
        var project = projectService.create(
                "新项目", "项目描述", ProjectStatus.ACTIVE, adminUser);

        // Then: 项目应该被创建
        assertThat(project.getId()).isNotNull();
        assertThat(project.getTitle()).isEqualTo("新项目");
        assertThat(project.getDescription()).isEqualTo("项目描述");
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(project.getCreatedBy()).isEqualTo(adminUser.id());
    }

    @Test
    void updateProject_shouldModifyProjectInfo() {
        // Given: 已存在的项目
        var project = projectService.create(
                "原始标题", "原始描述", ProjectStatus.ACTIVE, adminUser);

        // When: 更新项目
        var updated = projectService.update(
                project.getId(),
                "新标题", "新描述", 1, adminUser);

        // Then: 信息应该被更新
        assertThat(updated.getTitle()).isEqualTo("新标题");
        assertThat(updated.getDescription()).isEqualTo("新描述");
    }

    @Test
    void updateProject_withWrongVersion_shouldThrowException() {
        // Given: 已存在的项目
        var project = projectService.create(
                "测试项目", "描述", ProjectStatus.ACTIVE, adminUser);

        // When & Then: 使用错误的版本号应该失败
        assertThatThrownBy(() -> projectService.update(
                project.getId(),
                "新标题", "新描述", 999, adminUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已被其他操作修改");
    }

    @Test
    void completeProject_shouldChangeStatusToCompleted() {
        // Given: 进行中的项目
        var project = projectService.create(
                "测试项目", "描述", ProjectStatus.ACTIVE, adminUser);

        // When: 完成项目
        var completed = projectService.changeStatus(
                project.getId(), ProjectStatus.COMPLETED, 1, adminUser);

        // Then: 状态应该变为已完成
        assertThat(completed.getStatus()).isEqualTo(ProjectStatus.COMPLETED);
    }

    @Test
    void completeProject_twice_shouldThrowException() {
        // Given: 已完成的项目
        var project = projectService.create(
                "测试项目", "描述", ProjectStatus.ACTIVE, adminUser);
        projectService.changeStatus(project.getId(), ProjectStatus.COMPLETED, 1, adminUser);

        // When & Then: 重复完成应该失败
        assertThatThrownBy(() -> projectService.changeStatus(
                project.getId(), ProjectStatus.COMPLETED, 1, adminUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不允许的项目状态流转");
    }

    @Test
    void reopenProject_shouldChangeStatusBackToActive() {
        // Given: 已完成的项目
        var project = projectService.create(
                "测试项目", "描述", ProjectStatus.ACTIVE, adminUser);
        projectService.changeStatus(project.getId(), ProjectStatus.COMPLETED, 1, adminUser);

        // When: 重新开放项目
        var reopened = projectService.reopen(project.getId(), 2);

        // Then: 状态应该变回进行中
        assertThat(reopened.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
    }

    @Test
    void deleteProject_shouldMarkAsDeleted() {
        // Given: 已存在的项目
        var project = projectService.create(
                "测试项目", "描述", ProjectStatus.ACTIVE, adminUser);

        // When: 删除项目
        projectService.delete(project.getId(), adminUser);

        // Then: 项目应该无法查询到（逻辑删除）
        assertThatThrownBy(() -> projectService.get(project.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("项目不存在");
    }

    @Test
    void listProjects_shouldReturnFilteredResults() {
        // Given: 创建多个项目
        projectService.create("项目A", "描述A", ProjectStatus.ACTIVE, adminUser);
        projectService.create("项目B", "描述B", ProjectStatus.ACTIVE, adminUser);
        var completedProject = projectService.create(
                "项目C", "描述C", ProjectStatus.ACTIVE, adminUser);
        projectService.changeStatus(completedProject.getId(), ProjectStatus.COMPLETED, 1, adminUser);

        // When: 查询进行中的项目
        var activeProjects = projectService.list(1, 20, null, ProjectStatus.ACTIVE, adminUser);

        // Then: 应该只返回进行中的项目
        assertThat(activeProjects.items())
                .allMatch(p -> p.getStatus() == ProjectStatus.ACTIVE);
        assertThat(activeProjects.items().size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void getProjectDetail_shouldIncludeRequestCount() {
        // Given: 创建项目
        var project = projectService.create(
                "测试项目", "描述", ProjectStatus.ACTIVE, adminUser);

        // When: 获取项目详情
        var detail = projectService.getDetail(project.getId(), adminUser);

        // Then: 应该包含请求统计信息
        assertThat(detail.id()).isEqualTo(project.getId());
        assertThat(detail.title()).isEqualTo("测试项目");
        assertThat(detail.requestCount()).isZero(); // 新项目没有需求
    }

    @Test
    void campusManager_shouldOnlySeeProjectsWithAssignedRequests() {
        var assigned = projectService.create("已指派项目", "描述", ProjectStatus.ACTIVE, adminUser);
        var hidden = projectService.create("未指派项目", "描述", ProjectStatus.ACTIVE, adminUser);
        jdbc.sql("""
                INSERT INTO photo_request
                    (id, project_id, title, campus_id, required_count, deadline, status, created_by)
                VALUES
                    (9101, :projectId, '已指派需求', 901, 1, DATEADD('DAY', 1, CURRENT_TIMESTAMP),
                     'ACCEPTED', :adminId)
                """)
                .param("projectId", assigned.getId())
                .param("adminId", adminUser.id())
                .update();
        jdbc.sql("""
                INSERT INTO request_participant (request_id, user_id, accepted_at)
                VALUES (9101, :userId, CURRENT_TIMESTAMP)
                """)
                .param("userId", managerUser.id())
                .update();

        var visible = projectService.list(1, 20, null, null, managerUser);

        assertThat(visible.items()).extracting(project -> project.getId())
                .contains(assigned.getId())
                .doesNotContain(hidden.getId());
        assertThat(projectService.getDetail(assigned.getId(), managerUser).id()).isEqualTo(assigned.getId());
        assertThatThrownBy(() -> projectService.getDetail(hidden.getId(), managerUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权查看");
    }
}
