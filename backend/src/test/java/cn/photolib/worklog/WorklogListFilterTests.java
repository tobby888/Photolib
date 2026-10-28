package cn.photolib.worklog;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.permission.DataScope;
import cn.photolib.permission.PermissionCode;
import cn.photolib.user.model.UserRole;
import cn.photolib.worklog.model.WorklogEntity;
import cn.photolib.worklog.model.WorklogSource;
import cn.photolib.worklog.model.WorklogStatus;
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

/** 工时记录面板的筛选：关键词、校区、来源、日期区间，以及它们只收窄、不放宽可见范围。 */
@SpringBootTest
@Transactional
class WorklogListFilterTests {
    @Autowired
    JdbcClient jdbc;
    @Autowired
    WorklogService worklogs;

    private long base;
    private long reviewerId;
    private long submitterId;
    private long projectId;
    private long campusA;
    private long campusB;
    private AuthenticatedUser reviewer;

    @BeforeEach
    void seed() {
        base = System.nanoTime() & Long.MAX_VALUE >> 4;
        reviewerId = base;
        submitterId = base + 1;
        campusA = base + 2;
        campusB = base + 3;
        projectId = base + 4;
        long requestA = base + 6;
        long requestB = base + 7;
        long link = base + 9;

        jdbc.sql("INSERT INTO campus(id, code, name) VALUES (:id, :code, '甲'), (:id2, :code2, '乙')")
                .param("id", campusA).param("code", "wlf-a-" + base)
                .param("id2", campusB).param("code2", "wlf-b-" + base).update();
        jdbc.sql("""
                INSERT INTO app_user(id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (:r, :ru, 'hash', '审核人', 'MINISTER', TRUE, FALSE),
                       (:s, :su, 'hash', '填报人', 'CAMPUS_MANAGER', TRUE, FALSE)
                """).param("r", reviewerId).param("ru", "wlf-r-" + base)
                .param("s", submitterId).param("su", "wlf-s-" + base).update();
        jdbc.sql("INSERT INTO project(id, title, status, created_by) VALUES (:id, '选题', 'ACTIVE', :u)")
                .param("id", projectId).param("u", reviewerId).update();
        insertRequest(requestA, campusA, "迎新晚会");
        insertRequest(requestB, campusB, "运动会_决赛");
        jdbc.sql("""
                INSERT INTO project_share_link(id, token, project_id, password_hash, created_by, purpose)
                VALUES (:id, :token, :projectId, 'hash', :userId, 'UPLOAD')
                """).param("id", link).param("token", String.format("%026d", link)).param("projectId", projectId)
                .param("userId", reviewerId).update();

        insertWorklog(base + 20, requestA, null, submitterId, "张三", "2023001", "主舞台", LocalDate.of(2022, 5, 1));
        insertWorklog(base + 21, requestB, null, reviewerId, "李四", "2023002", null, LocalDate.of(2022, 5, 2));
        insertWorklog(base + 22, null, link, reviewerId, "王五", "2023003", "补拍", LocalDate.of(2022, 5, 3));
        insertWorklog(base + 23, requestA, null, submitterId, "赵六", "2024100", "后台花絮", LocalDate.of(2022, 5, 4));

        reviewer = new AuthenticatedUser(reviewerId, "r", "审核人", UserRole.MINISTER, null, false);
    }

    @Test
    void keywordMatchesMemberNameStudentIdRemarkAndRequestTitle() {
        assertThat(ids(filter(keyword("张三")))).containsExactly(base + 20);
        assertThat(ids(filter(keyword("2023")))).containsExactly(base + 22, base + 21, base + 20);
        assertThat(ids(filter(keyword("花絮")))).containsExactly(base + 23);
        assertThat(ids(filter(keyword("迎新")))).containsExactly(base + 23, base + 20);
    }

    @Test
    void keywordWildcardsAreTakenLiterally() {
        // 下划线在 LIKE 里是任意单字符；不转义的话「会_决」也会命中别的标题。
        assertThat(ids(filter(keyword("会_决")))).containsExactly(base + 21);
        assertThat(ids(filter(keyword("迎_晚")))).isEmpty();
        assertThat(ids(filter(keyword("%")))).isEmpty();
    }

    @Test
    void campusFilterKeepsOnlyThatCampusAndDropsLinkDeclaredWorklogs() {
        assertThat(ids(filter(new WorklogService.ListFilter(null, projectId, campusA, null, null,
                null, null, null, null)))).containsExactly(base + 23, base + 20);
    }

    @Test
    void sourceFilterSplitsInAppAndShareLinkWorklogs() {
        assertThat(ids(filter(source(WorklogSource.SHARE_LINK)))).containsExactly(base + 22);
        assertThat(ids(filter(source(WorklogSource.IN_APP)))).containsExactly(base + 23, base + 21, base + 20);
    }

    @Test
    void dateRangeIncludesBothEnds() {
        assertThat(ids(filter(new WorklogService.ListFilter(null, projectId, null, null, null,
                LocalDate.of(2022, 5, 2), LocalDate.of(2022, 5, 3), null, null))))
                .containsExactly(base + 22, base + 21);
    }

    @Test
    void filtersCombineWithStatus() {
        assertThat(ids(filter(new WorklogService.ListFilter(null, projectId, campusA, null, WorklogStatus.CONFIRMED,
                null, null, "迎新", WorklogSource.IN_APP)))).isEmpty();
        assertThat(ids(filter(new WorklogService.ListFilter(null, projectId, campusA, null, WorklogStatus.SUBMITTED,
                null, null, "迎新", WorklogSource.IN_APP)))).containsExactly(base + 23, base + 20);
    }

    @Test
    void filtersNeverWidenACampusScopedSubmittersView() {
        AuthenticatedUser submitter = new AuthenticatedUser(submitterId, "s", "填报人",
                UserRole.CAMPUS_MANAGER, campusA, false, 88L, "WLF", "填报组", DataScope.CAMPUS,
                Set.of(PermissionCode.WORKLOG_SUBMIT), Set.of(campusA));

        // 别人的工时、别校区的工时、链接申报的工时，换什么筛选都出不来。
        assertThat(ids(worklogs.list(1, 100, new WorklogService.ListFilter(null, projectId, campusB, reviewerId,
                null, null, null, null, null), submitter).items())).isEmpty();
        assertThat(ids(worklogs.list(1, 100, new WorklogService.ListFilter(null, projectId, null, reviewerId,
                null, null, null, "2023", null), submitter).items())).containsExactly(base + 20);
        assertThat(ids(worklogs.list(1, 100, new WorklogService.ListFilter(null, projectId, null, null,
                null, null, null, null, WorklogSource.SHARE_LINK), submitter).items())).isEmpty();
    }

    private WorklogService.ListFilter keyword(String keyword) {
        return new WorklogService.ListFilter(null, projectId, null, null, null, null, null, keyword, null);
    }

    private WorklogService.ListFilter source(WorklogSource source) {
        return new WorklogService.ListFilter(null, projectId, null, null, null, null, null, null, source);
    }

    private List<WorklogEntity> filter(WorklogService.ListFilter filter) {
        return worklogs.list(1, 100, filter, reviewer).items();
    }

    private List<Long> ids(List<WorklogEntity> items) {
        return items.stream().map(WorklogEntity::getId).toList();
    }

    private void insertRequest(long id, long campusId, String title) {
        jdbc.sql("""
                INSERT INTO photo_request(id, project_id, title, campus_id, deadline, status, created_by)
                VALUES (:id, :projectId, :title, :campusId, :deadline, 'ACCEPTED', :userId)
                """).param("id", id).param("projectId", projectId).param("title", title).param("campusId", campusId)
                .param("deadline", LocalDateTime.of(2030, 1, 1, 0, 0)).param("userId", reviewerId).update();
    }

    private void insertWorklog(long id, Long requestId, Long shareLinkId, long owner, String name, String studentId,
                               String remark, LocalDate workDate) {
        jdbc.sql("""
                INSERT INTO worklog(id, request_id, share_link_id, user_id, work_date, shooting_minutes,
                                    retouching_minutes, member_name, member_student_id, remark, status)
                VALUES (:id, :requestId, :shareLinkId, :userId, :workDate, 60, 0, :name, :studentId, :remark, 'SUBMITTED')
                """).param("id", id).param("requestId", requestId).param("shareLinkId", shareLinkId)
                .param("userId", owner).param("workDate", workDate).param("name", name)
                .param("studentId", studentId).param("remark", remark).update();
    }
}
