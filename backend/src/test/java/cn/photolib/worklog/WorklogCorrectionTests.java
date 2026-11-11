package cn.photolib.worklog;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.permission.DataScope;
import cn.photolib.permission.PermissionCode;
import cn.photolib.user.model.UserRole;
import cn.photolib.worklog.model.WorklogEntity;
import cn.photolib.worklog.model.WorklogStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 管理员直接修改工时（{@link WorklogService#correct}）：改姓名、学号、日期和时长，
 * 选题结束了也能改，只有管理员能改；状态可以任意改，关联需求可以换成任意一条，还能写自定义选题。
 */
@SpringBootTest
@Transactional
class WorklogCorrectionTests {
    @Autowired
    private WorklogService worklogs;
    @Autowired
    private JdbcClient jdbc;

    private long base;
    private long filerId;
    private long requestId;
    private long otherRequestId;
    private long adminId;
    private AuthenticatedUser admin;
    private AuthenticatedUser minister;

    @BeforeEach
    void setUp() {
        base = System.nanoTime() & Long.MAX_VALUE;
        filerId = base;
        adminId = base + 1;
        long projectId = base + 2;
        requestId = base + 3;
        long campusId = base + 4;
        insertUser(filerId, "填报人", "CAMPUS_MANAGER");
        insertUser(adminId, "管理员甲", "ADMIN");
        jdbc.sql("INSERT INTO campus(id, code, name) VALUES (:id, :code, '更正校区')")
                .param("id", campusId).param("code", "WL-CORR-" + base).update();
        // 选题已经结束：更正恰恰发生在这之后，不能被「选题已结束」挡住。
        jdbc.sql("""
                INSERT INTO project(id, title, status, created_by, completed_at)
                VALUES (:id, '更正选题', 'COMPLETED', :userId, :completedAt)
                """).param("id", projectId).param("userId", filerId)
                .param("completedAt", LocalDateTime.now().minusDays(1)).update();
        jdbc.sql("""
                INSERT INTO photo_request(id, project_id, title, campus_id, deadline, status, created_by)
                VALUES (:id, :projectId, '更正需求', :campusId, :deadline, 'COMPLETED', :userId)
                """).param("id", requestId).param("projectId", projectId).param("campusId", campusId)
                .param("deadline", LocalDateTime.now()).param("userId", filerId).update();
        // 另一个校区、另一个还在进行的选题下的需求：管理员改挂需求不受校区和参与人限制。
        long otherCampusId = base + 6;
        long otherProjectId = base + 7;
        otherRequestId = base + 8;
        jdbc.sql("INSERT INTO campus(id, code, name) VALUES (:id, :code, '别的校区')")
                .param("id", otherCampusId).param("code", "WL-CORR2-" + base).update();
        jdbc.sql("INSERT INTO project(id, title, status, created_by) VALUES (:id, '进行中选题', 'ACTIVE', :userId)")
                .param("id", otherProjectId).param("userId", filerId).update();
        jdbc.sql("""
                INSERT INTO photo_request(id, project_id, title, campus_id, deadline, status, created_by)
                VALUES (:id, :projectId, '别的需求', :campusId, :deadline, 'ACCEPTED', :userId)
                """).param("id", otherRequestId).param("projectId", otherProjectId).param("campusId", otherCampusId)
                .param("deadline", LocalDateTime.now()).param("userId", filerId).update();
        admin = new AuthenticatedUser(adminId, "corr-admin-" + base, "管理员甲", UserRole.ADMIN, null, false,
                1L, "ADMIN", "管理员", DataScope.GLOBAL, EnumSet.allOf(PermissionCode.class), Set.of());
        minister = new AuthenticatedUser(base + 5, "corr-minister-" + base, "部长", UserRole.MINISTER, null,
                false, 2L, "MINISTER", "部长", DataScope.GLOBAL, EnumSet.allOf(PermissionCode.class), Set.of());
    }

    @Test
    void adminCorrectsAConfirmedWorklogWithoutChangingItsStatus() {
        long id = insertWorklog("CONFIRMED");

        WorklogService.Correction correction = worklogs.correct(id, new WorklogService.CorrectionCommand(
                " 李四 ", "ｓ2021-0002 ", LocalDate.now().minusDays(3), 90, 15, "补拍"), 1, admin);

        WorklogEntity saved = correction.worklog();
        assertThat(saved.getMemberName()).isEqualTo("李四");
        // 全角转半角、去首尾空白，和链接申报同一套学号格式。
        assertThat(saved.getMemberStudentId()).isEqualTo("s2021-0002");
        assertThat(saved.getWorkDate()).isEqualTo(LocalDate.now().minusDays(3));
        assertThat(saved.getShootingMinutes()).isEqualTo(90);
        assertThat(saved.getRetouchingMinutes()).isEqualTo(15);
        assertThat(saved.getRemark()).isEqualTo("补拍");
        assertThat(saved.getStatus()).isEqualTo(WorklogStatus.CONFIRMED);
        assertThat(saved.getVersion()).isEqualTo(2);
        assertThat(correction.changes()).containsOnlyKeys("memberName", "memberStudentId", "workDate",
                "shootingMinutes", "retouchingMinutes", "remark");
        assertThat(correction.changes().get("shootingMinutes"))
                .isEqualTo(Map.of("from", 60, "to", 90));
        // 填报人会收到通知，写明谁改了什么。
        String content = jdbc.sql("""
                SELECT content FROM user_notification WHERE user_id=:userId AND event_type='WORKLOG_CORRECTED'
                """).param("userId", filerId).query(String.class).single();
        assertThat(content).contains("管理员甲").contains("拍摄（分钟） 60 → 90").contains("姓名 张三 → 李四");
    }

    @Test
    void unchangedValuesDoNotWriteOrNotify() {
        long id = insertWorklog("SUBMITTED");

        WorklogService.Correction correction = worklogs.correct(id, new WorklogService.CorrectionCommand(
                "张三", "S2021-0001", LocalDate.now().minusDays(1), 60, 30, null), 1, admin);

        assertThat(correction.changes()).isEmpty();
        assertThat(correction.worklog().getVersion()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM user_notification WHERE user_id=:userId")
                .param("userId", filerId).query(Long.class).single()).isZero();
    }

    @Test
    void onlyAdministratorsMayCorrect() {
        long id = insertWorklog("CONFIRMED");

        // 部长有全部工时权限也不行：这是管理员的更正入口。
        assertThatThrownBy(() -> worklogs.correct(id, command(), 1, minister))
                .isInstanceOf(BusinessException.class).hasMessageContaining("只有管理员");
    }

    @Test
    void adminMayAlsoEditDrafts() {
        long id = insertWorklog("DRAFT");

        WorklogEntity saved = worklogs.correct(id, command(), 1, admin).worklog();

        assertThat(saved.getMemberName()).isEqualTo("李四");
        assertThat(saved.getStatus()).isEqualTo(WorklogStatus.DRAFT);
    }

    @Test
    void adminSetsAnyStatus() {
        long id = insertWorklog("SUBMITTED");

        // 待确认 → 已退回：审核人记成这位管理员，没写原因时给一个默认原因，审核记录里补一行。
        WorklogEntity rejected = worklogs.correct(id, withStatus(WorklogStatus.REJECTED, null), 1, admin).worklog();
        assertThat(rejected.getStatus()).isEqualTo(WorklogStatus.REJECTED);
        assertThat(rejected.getConfirmedBy()).isEqualTo(adminId);
        assertThat(rejected.getConfirmedAt()).isNotNull();
        assertThat(rejected.getRejectReason()).isEqualTo("管理员修改为已退回");

        // 已退回 → 已确认：退回原因清掉。
        WorklogService.Correction confirmed = worklogs.correct(id, withStatus(WorklogStatus.CONFIRMED, null), 2,
                admin);
        assertThat(confirmed.worklog().getStatus()).isEqualTo(WorklogStatus.CONFIRMED);
        assertThat(confirmed.worklog().getRejectReason()).isNull();
        assertThat(confirmed.changes().get("status")).isEqualTo(Map.of("from", "REJECTED", "to", "CONFIRMED"));

        // 已确认 → 草稿：审核人、审核时间都清空。
        WorklogEntity draft = worklogs.correct(id, withStatus(WorklogStatus.DRAFT, null), 3, admin).worklog();
        assertThat(draft.getStatus()).isEqualTo(WorklogStatus.DRAFT);
        assertThat(draft.getConfirmedBy()).isNull();
        assertThat(draft.getConfirmedAt()).isNull();

        // 草稿 → 已退回，并写明原因。
        WorklogEntity again = worklogs.correct(id, withStatus(WorklogStatus.REJECTED, " 时长对不上 "), 4, admin)
                .worklog();
        assertThat(again.getRejectReason()).isEqualTo("时长对不上");

        assertThat(jdbc.sql("SELECT action FROM worklog_review_log WHERE worklog_id=:id ORDER BY id")
                .param("id", id).query(String.class).list())
                .containsExactly("REJECTED", "CONFIRMED", "REJECTED");
        String content = jdbc.sql("""
                SELECT content FROM user_notification WHERE user_id=:userId AND event_type='WORKLOG_CORRECTED'
                ORDER BY id LIMIT 1
                """).param("userId", filerId).query(String.class).single();
        assertThat(content).contains("状态 待确认 → 已退回");
    }

    @Test
    void adminMovesTheWorklogToAnyRequestAndWritesACustomProject() {
        long id = insertWorklog("CONFIRMED");

        WorklogService.Correction moved = worklogs.correct(id, new WorklogService.CorrectionCommand(
                "张三", "S2021-0001", LocalDate.now().minusDays(1), 60, 30, null,
                null, otherRequestId, false, "  迎新晚会  ", null), 1, admin);

        assertThat(moved.worklog().getRequestId()).isEqualTo(otherRequestId);
        assertThat(moved.worklog().getCustomProjectTitle()).isEqualTo("迎新晚会");
        // 状态没传就不动，审核信息照旧。
        assertThat(moved.worklog().getStatus()).isEqualTo(WorklogStatus.CONFIRMED);
        assertThat(moved.changes()).containsOnlyKeys("requestId", "customProjectTitle");
        String content = jdbc.sql("""
                SELECT content FROM user_notification WHERE user_id=:userId AND event_type='WORKLOG_CORRECTED'
                """).param("userId", filerId).query(String.class).single();
        assertThat(content).contains("关联需求 更正需求 → 别的需求").contains("自定义选题 （空） → 迎新晚会");

        // 空串清掉自定义选题；null 表示不改。
        WorklogEntity kept = worklogs.correct(id, new WorklogService.CorrectionCommand(
                "张三", "S2021-0001", LocalDate.now().minusDays(1), 45, 30, null), 2, admin).worklog();
        assertThat(kept.getCustomProjectTitle()).isEqualTo("迎新晚会");
        assertThat(kept.getRequestId()).isEqualTo(otherRequestId);
        WorklogEntity cleared = worklogs.correct(id, new WorklogService.CorrectionCommand(
                "张三", "S2021-0001", LocalDate.now().minusDays(1), 45, 30, null,
                null, null, false, "", null), 3, admin).worklog();
        assertThat(cleared.getCustomProjectTitle()).isNull();
    }

    @Test
    void inAppWorklogsKeepARequestUnlessTheyHaveACustomProject() {
        long id = insertWorklog("CONFIRMED");

        // 不填自定义选题就不能清掉需求；填了可以（见 WorklogWithoutRequestTests）。
        assertThatThrownBy(() -> worklogs.correct(id, new WorklogService.CorrectionCommand(
                "张三", "S2021-0001", LocalDate.now().minusDays(1), 60, 30, null,
                null, null, true, null, null), 1, admin))
                .isInstanceOf(BusinessException.class).hasMessageContaining("要么关联一条需求");
        assertThatThrownBy(() -> worklogs.correct(id, new WorklogService.CorrectionCommand(
                "张三", "S2021-0001", LocalDate.now().minusDays(1), 60, 30, null,
                null, base + 999, false, null, null), 1, admin))
                .isInstanceOf(BusinessException.class).hasMessageContaining("需求不存在");
    }

    @Test
    void rejectsInvalidValuesAndStaleVersions() {
        long id = insertWorklog("REJECTED");

        assertThatThrownBy(() -> worklogs.correct(id, new WorklogService.CorrectionCommand(
                "李四", "学号 有#号", LocalDate.now().minusDays(1), 60, 0, null), 1, admin))
                .isInstanceOf(BusinessException.class).hasMessageContaining("学号");
        assertThatThrownBy(() -> worklogs.correct(id, new WorklogService.CorrectionCommand(
                "李四", "S2021-0002", LocalDate.now().minusDays(1), 0, 0, null), 1, admin))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不能同时为0");
        assertThatThrownBy(() -> worklogs.correct(id, new WorklogService.CorrectionCommand(
                "李四", "S2021-0002", LocalDate.now().plusDays(1), 60, 0, null), 1, admin))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不能晚于今天");
        assertThatThrownBy(() -> worklogs.correct(id, new WorklogService.CorrectionCommand(
                "  ", "S2021-0002", LocalDate.now().minusDays(1), 60, 0, null), 1, admin))
                .isInstanceOf(BusinessException.class).hasMessageContaining("姓名");
        assertThatThrownBy(() -> worklogs.correct(id, command(), 7, admin))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已被其他操作修改");
        assertThat(jdbc.sql("SELECT member_name FROM worklog WHERE id=:id").param("id", id)
                .query(String.class).single()).isEqualTo("张三");
    }

    private WorklogService.CorrectionCommand withStatus(WorklogStatus status, String reason) {
        return new WorklogService.CorrectionCommand("张三", "S2021-0001", LocalDate.now().minusDays(1),
                60, 30, null, status, null, false, null, reason);
    }

    private WorklogService.CorrectionCommand command() {
        return new WorklogService.CorrectionCommand("李四", "S2021-0002", LocalDate.now().minusDays(1),
                45, 0, null);
    }

    private long insertWorklog(String status) {
        long id = base + 100;
        jdbc.sql("""
                INSERT INTO worklog(id, request_id, user_id, work_date, shooting_minutes, retouching_minutes,
                                    member_name, member_student_id, status)
                VALUES (:id, :requestId, :userId, :workDate, 60, 30, '张三', 'S2021-0001', :status)
                """).param("id", id).param("requestId", requestId).param("userId", filerId)
                .param("workDate", LocalDate.now().minusDays(1)).param("status", status).update();
        return id;
    }

    private void insertUser(long id, String name, String role) {
        jdbc.sql("""
                INSERT INTO app_user(id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (:id, :username, 'hash', :name, :role, TRUE, FALSE)
                """).param("id", id).param("username", "worklog-corr-" + id).param("name", name)
                .param("role", role).update();
    }
}
