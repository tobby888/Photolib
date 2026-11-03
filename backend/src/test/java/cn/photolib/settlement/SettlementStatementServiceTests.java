package cn.photolib.settlement;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.permission.DataScope;
import cn.photolib.permission.PermissionCode;
import cn.photolib.settlement.SettlementStatementService.ProjectLine;
import cn.photolib.settlement.SettlementStatementService.Recipient;
import cn.photolib.settlement.SettlementStatementService.Statement;
import cn.photolib.settlement.SettlementStatementService.UploadChannel;
import cn.photolib.settlement.SettlementStatementService.UploadSource;
import cn.photolib.user.model.UserRole;
import cn.photolib.worklog.WorklogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 清算单的取数：按账号姓名匹配工时和被引、按选题结束时间清算、参与的选题与上传方式、审核记录。只读，用事务回滚。
 */
@SpringBootTest
@Transactional
class SettlementStatementServiceTests {
    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 31);

    @Autowired
    JdbcClient jdbc;
    @Autowired
    SettlementStatementService statements;
    @Autowired
    WorklogService worklogs;

    private SettlementFixtures fixtures;
    private String name;

    @BeforeEach
    void setUp() {
        fixtures = new SettlementFixtures(jdbc);
        name = "清算甲" + fixtures.base;
    }

    @Test
    void matchesRecordsByTrimmedDisplayNameInProjectsCompletedWithinTheRange() {
        long userId = fixtures.user(" " + name + " ", "jia@example.cn");
        // 默认的清算选题 10 月 25 日结束：工作日期在期外也照样计入，按的是选题结束时间。
        long confirmed = fixtures.worklog(name, LocalDate.of(2026, 9, 20), 60, 30, "CONFIRMED", null);
        fixtures.worklog(name + " ", LocalDate.of(2026, 10, 31), 45, 0, "SUBMITTED", null);
        fixtures.worklog(name, LocalDate.of(2026, 10, 15), 20, 10, "REJECTED", "日期填错了");
        // 上传链接申报的工时，选题从链接上取。
        long link = fixtures.shareLink(fixtures.projectId, "活动当天", fixtures.ownerId);
        fixtures.worklog(null, link, name, LocalDate.of(2026, 10, 2), 15, 0, "CONFIRMED", null);
        // 草稿、别人的工时不算。
        fixtures.worklog(name, LocalDate.of(2026, 10, 16), 500, 0, "DRAFT", null);
        fixtures.worklog(name + "乙", LocalDate.of(2026, 10, 2), 500, 0, "CONFIRMED", null);
        // 选题还没结束、期末之后才结束、期初之前就结束、已取消的：工时和被引都不出现。
        long ongoing = fixtures.project("进行中选题");
        long endsLater = fixtures.project("下月结束选题", "COMPLETED", LocalDateTime.of(2026, 11, 1, 0, 0));
        long endedBefore = fixtures.project("上月结束选题", "COMPLETED", LocalDateTime.of(2026, 9, 30, 23, 59));
        long cancelled = fixtures.project("取消的选题", "CANCELLED", null);
        for (long project : List.of(ongoing, endsLater, endedBefore, cancelled)) {
            fixtures.worklog(fixtures.request(project), null, name, LocalDate.of(2026, 10, 10), 500, 0, "CONFIRMED", null);
            fixtures.adoption(name, project, LocalDateTime.of(2026, 10, 10, 10, 0));
        }
        // 被引同样按选题结束时间：被引时间在期外也算。
        long photo = fixtures.adoption(name, LocalDateTime.of(2026, 9, 28, 9, 0));
        fixtures.reviewEvent(confirmed, "SUBMITTED", null, LocalDateTime.of(2026, 9, 20, 20, 0));
        fixtures.reviewEvent(confirmed, "CONFIRMED", null, LocalDateTime.of(2026, 9, 21, 9, 0));

        Recipient recipient = statements.recipient(userId, FROM, TO);
        assertThat(recipient.displayName()).isEqualTo(name);
        assertThat(recipient.worklogCount()).isEqualTo(4);
        assertThat(recipient.confirmedMinutes()).isEqualTo(105);
        assertThat(recipient.pendingCount()).isEqualTo(1);
        assertThat(recipient.rejectedCount()).isEqualTo(1);
        assertThat(recipient.adoptedPhotos()).isEqualTo(1);
        assertThat(recipient.adoptionCount()).isEqualTo(1);
        assertThat(recipient.sameNameCount()).isZero();

        Statement statement = statements.statement(recipient, FROM, TO);
        assertThat(statement.worklogs()).extracting(SettlementStatementService.WorklogLine::workDate)
                .containsExactly(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 15),
                        LocalDate.of(2026, 10, 31));
        assertThat(statement.worklogs()).allSatisfy(line -> {
            assertThat(line.projectTitle()).isEqualTo("清算选题");
            assertThat(line.projectCompletedAt()).isEqualTo(SettlementFixtures.PROJECT_COMPLETED_AT);
        });
        assertThat(statement.worklogs().get(1).viaShareLink()).isTrue();
        assertThat(statement.worklogs().get(1).shareLinkName()).isEqualTo("活动当天");
        assertThat(statement.worklogs().get(2).rejectReason()).isEqualTo("日期填错了");
        assertThat(statement.worklogs().get(0).requestTitle()).isEqualTo("清算需求");
        assertThat(statement.worklogs().get(0).campus()).isEqualTo("清算校区");
        assertThat(statement.worklogs().get(0).events()).extracting(SettlementStatementService.ReviewEvent::action)
                .containsExactly("SUBMITTED", "CONFIRMED");
        assertThat(statement.adoptions()).singleElement().satisfies(line -> {
            assertThat(line.photoId()).isEqualTo(photo);
            assertThat(line.projectTitle()).isEqualTo("清算选题");
            assertThat(line.projectCompletedAt()).isEqualTo(SettlementFixtures.PROJECT_COMPLETED_AT);
        });
        assertThat(statement.totals().confirmedMinutes()).isEqualTo(105);
        assertThat(statement.totals().pendingMinutes()).isEqualTo(45);
        assertThat(statement.totals().rejectedCount()).isEqualTo(1);
    }

    @Test
    void projectsAreSplitIntoEndedAndOngoing() {
        long userId = fixtures.user(name, "jia@example.cn");
        long ongoing = fixtures.project("清算选题乙");
        long endsLater = fixtures.project("清算选题丙", "COMPLETED", LocalDateTime.of(2026, 11, 3, 9, 0));
        long endedBefore = fixtures.project("清算选题丁", "COMPLETED", LocalDateTime.of(2026, 9, 30, 0, 0));
        long cancelled = fixtures.project("清算选题戊", "CANCELLED", null);
        // 期内结束的选题：上传时间在期外的图也算。
        fixtures.photo(name, fixtures.projectId, LocalDateTime.of(2026, 9, 1, 0, 0), "AVAILABLE", false);
        // 老数据只挂需求、没有 project_id，选题从需求上取。
        fixtures.photo(name, fixtures.projectId, LocalDateTime.of(2026, 10, 20, 12, 0), "ARCHIVED", true);
        // 未结束的选题：算期末之前上传的（期初之前的也算），期末之后的不算。
        fixtures.photo(name + " ", ongoing, LocalDateTime.of(2026, 8, 31, 23, 59), "PROCESSING", false);
        fixtures.photo(name, ongoing, LocalDateTime.of(2026, 10, 31, 23, 59), "AVAILABLE", false);
        fixtures.photo(name, ongoing, LocalDateTime.of(2026, 11, 1, 0, 0), "AVAILABLE", false);
        fixtures.photo(name, endsLater, LocalDateTime.of(2026, 10, 5, 0, 0), "AVAILABLE", false);
        // 期初之前结束的、取消的、没传完的、删掉的、别人拍的都不算。
        fixtures.photo(name, endedBefore, LocalDateTime.of(2026, 9, 10, 0, 0), "AVAILABLE", false);
        fixtures.photo(name, cancelled, LocalDateTime.of(2026, 10, 10, 0, 0), "AVAILABLE", false);
        fixtures.photo(name, ongoing, LocalDateTime.of(2026, 10, 10, 0, 0), "UPLOADING", false);
        fixtures.photo(name, ongoing, LocalDateTime.of(2026, 10, 10, 0, 0), "DELETED", false);
        fixtures.photo(name + "乙", ongoing, LocalDateTime.of(2026, 10, 10, 0, 0), "AVAILABLE", false);

        Recipient recipient = statements.recipient(userId, FROM, TO);
        assertThat(recipient.uploadedPhotos()).isEqualTo(2);
        assertThat(recipient.projectCount()).isEqualTo(1);
        assertThat(recipient.ongoingPhotos()).isEqualTo(3);
        assertThat(recipient.ongoingProjectCount()).isEqualTo(2);
        assertThat(recipient.hasRecords()).isTrue();

        Statement statement = statements.statement(recipient, FROM, TO);
        // 已结束的在前，未结束的按首张上传时间。
        assertThat(statement.projects()).extracting(ProjectLine::projectTitle, ProjectLine::ended, ProjectLine::photoCount)
                .containsExactly(tuple("清算选题", true, 2), tuple("清算选题乙", false, 2), tuple("清算选题丙", false, 1));
        ProjectLine ended = statement.projects().get(0);
        assertThat(ended.projectId()).isEqualTo(fixtures.projectId);
        assertThat(ended.completedAt()).isEqualTo(SettlementFixtures.PROJECT_COMPLETED_AT);
        assertThat(ended.firstUploadAt()).isEqualTo(LocalDateTime.of(2026, 9, 1, 0, 0));
        assertThat(ended.lastUploadAt()).isEqualTo(LocalDateTime.of(2026, 10, 20, 12, 0));
        assertThat(statement.projects().get(1).completedAt()).isNull();
        assertThat(statement.projects().get(2).completedAt()).isEqualTo(LocalDateTime.of(2026, 11, 3, 9, 0));
        assertThat(statement.totals().projectCount()).isEqualTo(1);
        assertThat(statement.totals().uploadedPhotos()).isEqualTo(2);
        assertThat(statement.totals().ongoingProjectCount()).isEqualTo(2);
        assertThat(statement.totals().ongoingPhotos()).isEqualTo(3);
    }

    @Test
    void uploadSourcesTellSelfFromShareLinkAndProxies() {
        long userId = fixtures.user(name, "jia@example.cn");
        long leader = fixtures.user("清算负责人" + fixtures.base, null);
        long editor = fixtures.user("清算采编" + fixtures.base, null);
        long link = fixtures.shareLink(fixtures.projectId, "运动会现场", leader);
        LocalDateTime at = LocalDateTime.of(2026, 10, 10, 9, 0);
        // 自己的账号：挂需求的和直接传进选题的都是「本人上传」。
        fixtures.photo(name, null, fixtures.requestId, at, "AVAILABLE", userId, null);
        fixtures.photo(name, fixtures.projectId, null, at, "AVAILABLE", userId, null);
        // 上传链接：uploaded_by 是开链接的人，也不算代传。
        fixtures.photo(name, fixtures.projectId, null, at, "AVAILABLE", leader, link);
        // 负责人在需求交付里代传两张。
        fixtures.photo(name, null, fixtures.requestId, at, "AVAILABLE", leader, null);
        fixtures.photo(name, null, fixtures.requestId, at.plusHours(1), "AVAILABLE", leader, null);
        // 采编不经需求直接添加到选题。
        fixtures.photo(name, fixtures.projectId, null, at, "AVAILABLE", editor, null);

        Statement statement = statements.statement(statements.recipient(userId, FROM, TO), FROM, TO);

        assertThat(statement.projects()).singleElement().satisfies(line -> {
            assertThat(line.photoCount()).isEqualTo(6);
            assertThat(line.sources()).extracting(UploadSource::channel, UploadSource::uploaderName,
                            UploadSource::shareLinkName, UploadSource::photoCount)
                    .containsExactly(tuple(UploadChannel.SELF, null, null, 2),
                            tuple(UploadChannel.SHARE_LINK, null, "运动会现场", 1),
                            tuple(UploadChannel.REQUEST_PROXY, "清算负责人" + fixtures.base, null, 2),
                            tuple(UploadChannel.PROJECT_ADDED, "清算采编" + fixtures.base, null, 1));
        });
    }

    @Test
    void sameNamedAccountsAreFlagged() {
        long first = fixtures.user(name, "a@example.cn");
        fixtures.user(name, "b@example.cn");
        long noEmail = fixtures.user(name + "丙", null);

        List<Recipient> recipients = statements.recipients(FROM, TO);
        assertThat(recipients).filteredOn(r -> r.userId() == first).singleElement()
                .satisfies(r -> assertThat(r.sameNameCount()).isEqualTo(1));
        assertThat(recipients).filteredOn(r -> r.userId() == noEmail).singleElement()
                .satisfies(r -> assertThat(r.email()).isNull());
    }

    @Test
    void reviewLogKeepsEverySubmissionAndRejectionReason() {
        long filerId = fixtures.ownerId;
        AuthenticatedUser filer = new AuthenticatedUser(filerId, "settle-filer", "负责人", UserRole.CAMPUS_MANAGER,
                null, false, 3L, "ADMIN", "管理员", DataScope.GLOBAL, EnumSet.allOf(PermissionCode.class), Set.of());
        AuthenticatedUser reviewer = new AuthenticatedUser(filerId, "settle-reviewer", "审核人", UserRole.ADMIN,
                null, false, 1L, "ADMIN", "管理员", DataScope.GLOBAL, EnumSet.allOf(PermissionCode.class), Set.of());
        long id = fixtures.worklog(name, LocalDate.now().minusDays(1), 60, 0, "SUBMITTED", null);

        worklogs.reject(id, "少报了修图", 1, reviewer);
        worklogs.submit(id, 2, filer);
        worklogs.reject(id, "日期不对", 3, reviewer);
        worklogs.submit(id, 4, filer);
        worklogs.confirm(id, 5, reviewer);

        List<String> log = jdbc.sql("""
                SELECT CONCAT(action, ':', COALESCE(reason, '')) FROM worklog_review_log WHERE worklog_id=:id ORDER BY id
                """).param("id", id).query(String.class).list();
        // worklog.reject_reason 只存最近一次的原因，第一次退回的原因只留在审核记录里。
        assertThat(log).containsExactly("REJECTED:少报了修图", "SUBMITTED:", "REJECTED:日期不对", "SUBMITTED:",
                "CONFIRMED:");
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM worklog_review_log WHERE worklog_id=:id AND action='SUBMITTED'
                  AND shooting_minutes=60 AND retouching_minutes=0 AND actor_id=:actor
                """).param("id", id).param("actor", filerId).query(Long.class).single()).isEqualTo(2);
    }
}
