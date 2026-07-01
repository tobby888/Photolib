package cn.photolib.project;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.campus.CampusService;
import cn.photolib.project.model.ProjectEntity;
import cn.photolib.project.model.ProjectStatus;
import cn.photolib.request.RequestService;
import cn.photolib.request.model.PhotoRequestEntity;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ProjectRequestIntegrationTests {
    @Autowired
    private ProjectService projects;
    @Autowired
    private RequestService requests;
    @Autowired
    private CampusService campuses;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void createsRequestWithExactSnowflakeProjectIdAndUpdatesDetailSummary() {
        AuthenticatedUser admin = new AuthenticatedUser(
                1L, "admin", "管理员", UserRole.ADMIN, null, false);
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES
                    (:id, 'integration-admin', 'not-used', '集成测试管理员', 'ADMIN', true, false)
                """).param("id", admin.id()).update();
        ProjectEntity project = projects.create(
                "集成测试项目", "验证长整型项目 ID", ProjectStatus.ACTIVE, admin);
        var campus = campuses.create("TEST", "测试校区");

        PhotoRequestEntity request = requests.create(project.getId(),
                new RequestService.CreateCommand("测试图片需求", "需要现场照片", campus.getId(),
                        5, LocalDateTime.now().plusDays(1)), admin);
        ProjectService.ProjectDetail detail = projects.getDetail(project.getId());

        assertThat(request.getProjectId()).isEqualTo(project.getId());
        assertThat(detail.id()).isEqualTo(project.getId());
        assertThat(detail.requestCount()).isEqualTo(1);
    }
}
