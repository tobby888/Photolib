package cn.photolib.worklog;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.permission.DataScope;
import cn.photolib.permission.PermissionCode;
import cn.photolib.user.model.UserRole;
import cn.photolib.worklog.model.WorklogEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 选题详情页「本选题工时」用的 {@code GET /worklogs?projectId=}：
 * 站内填报的按需求所属选题、链接申报的按链接所属选题，两种都要出来；可见范围不因它放宽。
 */
@SpringBootTest
@Transactional
class WorklogProjectFilterTests {
    @Autowired
    JdbcClient jdbc;
    @Autowired
    WorklogService worklogs;

    private long base;
    private long reviewerId;
    private long submitterId;
    private long projectId;
    private long campusA;

    @BeforeEach
    void seed() {
        base = System.nanoTime() & Long.MAX_VALUE >> 4;
        reviewerId = base;
        submitterId = base + 1;
        campusA = base + 2;
        long campusB = base + 3;
        projectId = base + 4;
        long otherProject = base + 5;
        long requestA = base + 6;
        long requestB = base + 7;
        long otherRequest = base + 8;
        long link = base + 9;
        long revokedLink = base + 10;

        jdbc.sql("INSERT INTO campus(id, code, name) VALUES (:id, :code, '甲'), (:id2, :code2, '乙')")
                .param("id", campusA).param("code", "wpf-a-" + base)
                .param("id2", campusB).param("code2", "wpf-b-" + base).update();
        jdbc.sql("""
                INSERT INTO app_user(id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (:r, :ru, 'hash', '审核人', 'MINISTER', TRUE, FALSE),
                       (:s, :su, 'hash', '填报人', 'CAMPUS_MANAGER', TRUE, FALSE)
                """).param("r", reviewerId).param("ru", "wpf-r-" + base)
                .param("s", submitterId).param("su", "wpf-s-" + base).update();
        for (long id : new long[]{projectId, otherProject}) {
            jdbc.sql("INSERT INTO project(id, title, status, created_by) VALUES (:id, '选题', 'ACTIVE', :u)")
                    .param("id", id).param("u", reviewerId).update();
        }
        insertRequest(requestA, projectId, campusA);
        insertRequest(requestB, projectId, campusB);
        insertRequest(otherRequest, otherProject, campusA);
        insertLink(link, projectId, false);
        // 撤销了的链接：申报的工时仍然属于这个选题。
        insertLink(revokedLink, projectId, true);

        insertWorklog(base + 20, requestA, null, submitterId);
        insertWorklog(base + 21, requestB, null, reviewerId);
        insertWorklog(base + 22, null, link, reviewerId);
        insertWorklog(base + 23, null, revokedLink, reviewerId);
        insertWorklog(base + 24, otherRequest, null, submitterId);
    }

    @Test
    void bothInAppAndLinkDeclaredWorklogsOfTheProjectAreListedAndNothingElse() {
        AuthenticatedUser reviewer = new AuthenticatedUser(reviewerId, "r", "审核人", UserRole.MINISTER, null, false);

        assertThat(ids(worklogs.list(1, 100, null, projectId, null, null, null, null, reviewer).items()))
                .containsExactlyInAnyOrder(base + 20, base + 21, base + 22, base + 23);
    }

    @Test
    void theProjectFilterOnlyNarrowsTheExistingVisibility() {
        // 只有填报权限的人只看得到自己的；校区范围账号看不到别校区的，也看不到没有校区的链接申报工时。
        AuthenticatedUser submitter = new AuthenticatedUser(submitterId, "s", "填报人",
                UserRole.CAMPUS_MANAGER, campusA, false, 88L, "WPF", "填报组", DataScope.CAMPUS,
                Set.of(PermissionCode.WORKLOG_SUBMIT), Set.of(campusA));

        assertThat(ids(worklogs.list(1, 100, null, projectId, null, null, null, null, submitter).items()))
                .containsExactly(base + 20);
    }

    private List<Long> ids(List<WorklogEntity> items) {
        return items.stream().map(WorklogEntity::getId).toList();
    }

    private void insertRequest(long id, long project, long campusId) {
        jdbc.sql("""
                INSERT INTO photo_request(id, project_id, title, campus_id, deadline, status, created_by)
                VALUES (:id, :projectId, '需求', :campusId, :deadline, 'ACCEPTED', :userId)
                """).param("id", id).param("projectId", project).param("campusId", campusId)
                .param("deadline", LocalDateTime.of(2030, 1, 1, 0, 0)).param("userId", reviewerId).update();
    }

    private void insertLink(long id, long project, boolean deleted) {
        jdbc.sql("""
                INSERT INTO project_share_link(id, token, project_id, password_hash, created_by, purpose, deleted)
                VALUES (:id, :token, :projectId, 'hash', :userId, 'UPLOAD', :deleted)
                """).param("id", id).param("token", String.format("%026d", id)).param("projectId", project)
                .param("userId", reviewerId).param("deleted", deleted).update();
    }

    private void insertWorklog(long id, Long requestId, Long shareLinkId, long owner) {
        jdbc.sql("""
                INSERT INTO worklog(id, request_id, share_link_id, user_id, work_date, shooting_minutes,
                                    retouching_minutes, member_name, member_student_id, status)
                VALUES (:id, :requestId, :shareLinkId, :userId, :workDate, 60, 0, '成员', 'M1', 'SUBMITTED')
                """).param("id", id).param("requestId", requestId).param("shareLinkId", shareLinkId)
                .param("userId", owner).param("workDate", LocalDate.of(2022, 5, 1)).update();
    }
}
