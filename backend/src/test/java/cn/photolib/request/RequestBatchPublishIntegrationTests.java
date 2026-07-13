package cn.photolib.request;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.campus.CampusService;
import cn.photolib.project.ProjectService;
import cn.photolib.project.model.ProjectStatus;
import cn.photolib.request.model.RequestStatus;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RequestBatchPublishIntegrationTests {
    @Autowired
    private RequestService requestService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private CampusService campusService;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void failedCampus_shouldNotPreventLaterCampusFromCommitting() {
        long suffix = System.nanoTime();
        var campus = campusService.create("B" + suffix, "批量发布测试校区");
        Long userId = -Math.abs(suffix);
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (:id, :username, 'hash', '批量发布测试部长', 'MINISTER', true, false)
                """).param("id", userId).param("username", "batch-minister-" + suffix).update();
        var user = new AuthenticatedUser(userId, "batch-minister-" + suffix,
                "批量发布测试部长", UserRole.MINISTER, null, false);
        var project = projectService.create("批量发布事务测试", "说明", ProjectStatus.ACTIVE, user);

        try {
            var command = new RequestService.BatchPublishCommand(
                    "多校区毕业季拍摄", "## 拍摄说明\n\n请拍摄校园地标。",
                    List.of(999999L, campus.getId()), null, LocalDateTime.now().plusDays(7));

            var results = requestService.batchPublish(project.getId(), command, user);

            assertThat(results).hasSize(2);
            assertThat(results.get(0).success()).isFalse();
            assertThat(results.get(0).errorCode()).isEqualTo("RESOURCE_NOT_FOUND");
            assertThat(results.get(1).success()).isTrue();
            assertThat(results.get(1).request().getStatus()).isEqualTo(RequestStatus.PUBLISHED);
            assertThat(requestService.get(results.get(1).request().getId()).getDescription())
                    .contains("## 拍摄说明");
        } finally {
            jdbc.sql("DELETE FROM photo_request WHERE project_id=:projectId")
                    .param("projectId", project.getId()).update();
            jdbc.sql("DELETE FROM project WHERE id=:projectId").param("projectId", project.getId()).update();
            jdbc.sql("DELETE FROM app_user WHERE id=:userId").param("userId", userId).update();
            jdbc.sql("DELETE FROM campus WHERE id=:campusId").param("campusId", campus.getId()).update();
        }
    }
}
