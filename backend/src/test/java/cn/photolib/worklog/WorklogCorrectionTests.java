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
 * 管理员更正已报上来的工时（{@link WorklogService#correct}）：直接改姓名、学号、日期和时长，
 * 状态不变、选题结束了也能改，只有管理员能改，草稿不归管理员改。
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
    private AuthenticatedUser admin;
    private AuthenticatedUser minister;

    @BeforeEach
    void setUp() {
        base = System.nanoTime() & Long.MAX_VALUE;
        filerId = base;
        long adminId = base + 1;
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
    void draftsBelongToTheFiler() {
        long id = insertWorklog("DRAFT");

        assertThatThrownBy(() -> worklogs.correct(id, command(), 1, admin))
                .isInstanceOf(BusinessException.class).hasMessageContaining("草稿");
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
