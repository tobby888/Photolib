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
        var secondCampus = campusService.create("D" + suffix, "批量发布测试校区二");
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
                    List.of(999999L, campus.getId(), secondCampus.getId()), null,
                    LocalDateTime.now().plusDays(7));

            var results = requestService.batchPublish(project.getId(), command, user);

            assertThat(results).hasSize(3);
            assertThat(results.get(0).success()).isFalse();
            assertThat(results.get(0).errorCode()).isEqualTo("RESOURCE_NOT_FOUND");
            assertThat(results.get(1).success()).isTrue();
            assertThat(results.get(1).request().getStatus()).isEqualTo(RequestStatus.PUBLISHED);
            assertThat(results.get(1).request().getBatchId()).isNotBlank();
            assertThat(results.get(2).success()).isTrue();
            assertThat(results.get(2).request().getBatchId())
                    .isEqualTo(results.get(1).request().getBatchId());
            assertThat(requestService.get(results.get(1).request().getId()).getDescription())
                    .contains("## 拍摄说明");
        } finally {
            jdbc.sql("DELETE FROM photo_request WHERE project_id=:projectId")
                    .param("projectId", project.getId()).update();
            jdbc.sql("DELETE FROM project WHERE id=:projectId").param("projectId", project.getId()).update();
            jdbc.sql("DELETE FROM app_user WHERE id=:userId").param("userId", userId).update();
            jdbc.sql("DELETE FROM campus WHERE id IN (:ids)")
                    .param("ids", List.of(campus.getId(), secondCampus.getId())).update();
        }
    }

    @Test
    void assignee_shouldJoinEachCampusItCanAccessAndFailTheRest() {
        long suffix = System.nanoTime();
        var campus = campusService.create("A" + suffix, "指派可达校区");
        var otherCampus = campusService.create("C" + suffix, "指派不可达校区");
        Long ministerId = -Math.abs(suffix);
        Long assigneeId = ministerId - 1;
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, campus_id, enabled, must_change_password)
                VALUES (:ministerId, :ministerName, 'hash', '指派测试部长', 'MINISTER', null, true, false),
                       (:assigneeId, :assigneeName, 'hash', '指派测试负责人', 'CAMPUS_MANAGER', :campusId, true, false)
                """).param("ministerId", ministerId).param("ministerName", "assign-minister-" + suffix)
                .param("assigneeId", assigneeId).param("assigneeName", "assign-manager-" + suffix)
                .param("campusId", campus.getId()).update();
        var user = new AuthenticatedUser(ministerId, "assign-minister-" + suffix,
                "指派测试部长", UserRole.MINISTER, null, false);
        var project = projectService.create("批量发布指派测试", "说明", ProjectStatus.ACTIVE, user);

        try {
            var command = new RequestService.BatchPublishCommand("指派拍摄", "说明",
                    List.of(campus.getId(), otherCampus.getId()), null,
                    LocalDateTime.now().plusDays(7), assigneeId);

            var results = requestService.batchPublish(project.getId(), command, user);

            assertThat(results).hasSize(2);
            assertThat(results.get(0).success()).isTrue();
            assertThat(results.get(0).request().getStatus()).isEqualTo(RequestStatus.ACCEPTED);
            assertThat(results.get(0).request().getAssigneeId()).isEqualTo(assigneeId);
            assertThat(results.get(0).request().getBatchId()).isNotBlank();
            assertThat(requestService.participants(results.get(0).request().getId()))
                    .extracting(p -> p.getUserId()).containsExactly(assigneeId);
            assertThat(results.get(1).success()).isFalse();
            assertThat(results.get(1).errorCode()).isEqualTo("VALIDATION_ERROR");
            assertThat(jdbc.sql("""
                    SELECT COUNT(*) FROM user_notification WHERE user_id=:userId AND event_type='REQUEST_ASSIGNED'
                    """).param("userId", assigneeId).query(Long.class).single()).isEqualTo(1);
        } finally {
            jdbc.sql("""
                    DELETE FROM request_participant
                    WHERE request_id IN (SELECT id FROM photo_request WHERE project_id=:projectId)
                    """).param("projectId", project.getId()).update();
            jdbc.sql("DELETE FROM user_notification WHERE user_id IN (:ids)")
                    .param("ids", List.of(ministerId, assigneeId)).update();
            jdbc.sql("DELETE FROM photo_request WHERE project_id=:projectId")
                    .param("projectId", project.getId()).update();
            jdbc.sql("DELETE FROM project WHERE id=:projectId").param("projectId", project.getId()).update();
            jdbc.sql("DELETE FROM app_user WHERE id IN (:ids)")
                    .param("ids", List.of(ministerId, assigneeId)).update();
            jdbc.sql("DELETE FROM campus WHERE id IN (:ids)")
                    .param("ids", List.of(campus.getId(), otherCampus.getId())).update();
        }
    }
}
