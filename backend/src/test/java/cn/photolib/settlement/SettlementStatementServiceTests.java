package cn.photolib.settlement;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.permission.DataScope;
import cn.photolib.permission.PermissionCode;
import cn.photolib.settlement.SettlementStatementService.ProjectLine;
import cn.photolib.settlement.SettlementStatementService.Recipient;
import cn.photolib.settlement.SettlementStatementService.Statement;
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
 * 清算单的取数：按账号姓名匹配工时和被引、时间口径、审核记录。只读，用事务回滚。
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
    void matchesRecordsByTrimmedDisplayNameWithinTheRange() {
        long userId = fixtures.user(" " + name + " ", "jia@example.cn");
        long confirmed = fixtures.worklog(name, LocalDate.of(2026, 10, 1), 60, 30, "CONFIRMED", null);
        fixtures.worklog(name + " ", LocalDate.of(2026, 10, 31), 45, 0, "SUBMITTED", null);
        fixtures.worklog(name, LocalDate.of(2026, 10, 15), 20, 10, "REJECTED", "日期填错了");
        // 草稿、区间外、别人的工时都不算。
        fixtures.worklog(name, LocalDate.of(2026, 10, 16), 500, 0, "DRAFT", null);
        fixtures.worklog(name, LocalDate.of(2026, 11, 1), 500, 0, "CONFIRMED", null);
        fixtures.worklog(name + "乙", LocalDate.of(2026, 10, 2), 500, 0, "CONFIRMED", null);
        long photo = fixtures.adoption(name, LocalDateTime.of(2026, 10, 31, 23, 59));
        fixtures.adoption(name, LocalDateTime.of(2026, 11, 1, 0, 0));
        fixtures.reviewEvent(confirmed, "SUBMITTED", null, LocalDateTime.of(2026, 10, 1, 20, 0));
        fixtures.reviewEvent(confirmed, "CONFIRMED", null, LocalDateTime.of(2026, 10, 2, 9, 0));

        Recipient recipient = statements.recipient(userId, FROM, TO);
        assertThat(recipient.displayName()).isEqualTo(name);
        assertThat(recipient.worklogCount()).isEqualTo(3);
        assertThat(recipient.confirmedMinutes()).isEqualTo(90);
        assertThat(recipient.pendingCount()).isEqualTo(1);
        assertThat(recipient.rejectedCount()).isEqualTo(1);
        assertThat(recipient.adoptedPhotos()).isEqualTo(1);
        assertThat(recipient.sameNameCount()).isZero();

        Statement statement = statements.statement(recipient, FROM, TO);
        assertThat(statement.worklogs()).extracting(SettlementStatementService.WorklogLine::workDate)
                .containsExactly(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 31));
        assertThat(statement.worklogs().get(1).rejectReason()).isEqualTo("日期填错了");
        assertThat(statement.worklogs().get(0).projectTitle()).isEqualTo("清算选题");
        assertThat(statement.worklogs().get(0).requestTitle()).isEqualTo("清算需求");
        assertThat(statement.worklogs().get(0).campus()).isEqualTo("清算校区");
        assertThat(statement.worklogs().get(0).events()).extracting(SettlementStatementService.ReviewEvent::action)
                .containsExactly("SUBMITTED", "CONFIRMED");
        assertThat(statement.adoptions()).singleElement().satisfies(line -> {
            assertThat(line.photoId()).isEqualTo(photo);
            assertThat(line.projectTitle()).isEqualTo("清算选题");
        });
        assertThat(statement.totals().confirmedMinutes()).isEqualTo(90);
        assertThat(statement.totals().pendingMinutes()).isEqualTo(45);
        assertThat(statement.totals().rejectedCount()).isEqualTo(1);
    }

    @Test
    void projectsAreTheOnesWithPhotosUploadedInTheRange() {
        long userId = fixtures.user(name, "jia@example.cn");
        long other = fixtures.project("清算选题乙");
        fixtures.photo(name, fixtures.projectId, LocalDateTime.of(2026, 10, 1, 0, 0), "AVAILABLE", false);
        // 老数据只挂需求、没有 project_id，选题从需求上取。
        fixtures.photo(name, fixtures.projectId, LocalDateTime.of(2026, 10, 20, 12, 0), "ARCHIVED", true);
        fixtures.photo(name + " ", other, LocalDateTime.of(2026, 10, 31, 23, 59), "PROCESSING", false);
        // 没传完的、删掉的、区间外的、别人拍的都不算。
        fixtures.photo(name, other, LocalDateTime.of(2026, 10, 10, 0, 0), "UPLOADING", false);
        fixtures.photo(name, other, LocalDateTime.of(2026, 10, 10, 0, 0), "DELETED", false);
        fixtures.photo(name, other, LocalDateTime.of(2026, 11, 1, 0, 0), "AVAILABLE", false);
        fixtures.photo(name, other, LocalDateTime.of(2026, 9, 30, 23, 59), "AVAILABLE", false);
        fixtures.photo(name + "乙", other, LocalDateTime.of(2026, 10, 10, 0, 0), "AVAILABLE", false);

        Recipient recipient = statements.recipient(userId, FROM, TO);
        assertThat(recipient.uploadedPhotos()).isEqualTo(3);
        assertThat(recipient.projectCount()).isEqualTo(2);
        assertThat(recipient.hasRecords()).isTrue();

        Statement statement = statements.statement(recipient, FROM, TO);
        assertThat(statement.projects()).extracting(ProjectLine::projectTitle, ProjectLine::photoCount)
                .containsExactly(tuple("清算选题", 2), tuple("清算选题乙", 1));
        assertThat(statement.projects().get(0).projectId()).isEqualTo(fixtures.projectId);
        assertThat(statement.projects().get(0).firstUploadAt()).isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0));
        assertThat(statement.projects().get(0).lastUploadAt()).isEqualTo(LocalDateTime.of(2026, 10, 20, 12, 0));
        assertThat(statement.totals().projectCount()).isEqualTo(2);
        assertThat(statement.totals().uploadedPhotos()).isEqualTo(3);
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
