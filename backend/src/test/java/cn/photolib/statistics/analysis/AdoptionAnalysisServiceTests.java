package cn.photolib.statistics.analysis;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.permission.DataScope;
import cn.photolib.permission.PermissionCode;
import cn.photolib.statistics.StatisticsService;
import cn.photolib.statistics.analysis.AdoptionAnalysisService.AnalysisResult;
import cn.photolib.statistics.analysis.AdoptionAnalysisService.Basis;
import cn.photolib.statistics.analysis.AdoptionAnalysisService.Bucket;
import cn.photolib.statistics.analysis.AdoptionAnalysisService.Granularity;
import cn.photolib.statistics.analysis.AdoptionAnalysisService.Query;
import cn.photolib.user.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 被引分析的口径：按选题结束时间取时与成员核算的被引张数一致；张数与次数的区别；
 * 按被引时间取时的边界；校区范围。只读用例，{@code @Transactional} 回滚，免得污染整轮共用的 H2 库。
 */
@SpringBootTest
@Transactional
class AdoptionAnalysisServiceTests {
    private static final LocalDate FROM = LocalDate.of(2004, 3, 1);
    private static final LocalDate TO = LocalDate.of(2004, 3, 31);

    @Autowired JdbcClient jdbc;
    @Autowired AdoptionAnalysisService service;
    @Autowired StatisticsService statistics;

    private long base;
    private long userId;
    private long campusA;
    private long campusB;
    private long projectX;
    private long projectY;
    private long projectLater;
    private long activeProject;
    private AuthenticatedUser admin;
    private String alice;
    private String bob;

    @BeforeEach
    void seed() {
        base = System.nanoTime() & 0xFFFF_FFFFL | 1L << 40;
        userId = base;
        campusA = base + 1;
        campusB = base + 2;
        projectX = base + 3;
        projectY = base + 4;
        projectLater = base + 5;
        activeProject = base + 6;
        alice = "AA" + base;
        bob = "BB" + base;
        jdbc.sql("INSERT INTO campus(id, code, name) VALUES (:id, :code, '东校区')")
                .param("id", campusA).param("code", "aa-a-" + base).update();
        jdbc.sql("INSERT INTO campus(id, code, name) VALUES (:id, :code, '西校区')")
                .param("id", campusB).param("code", "aa-b-" + base).update();
        jdbc.sql("""
                INSERT INTO app_user(id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (:id, :username, 'hash', '选片编辑', 'ADMIN', TRUE, FALSE)
                """).param("id", userId).param("username", "adoption-analysis-" + base).update();
        admin = new AuthenticatedUser(userId, "adoption-analysis", "选片编辑", UserRole.ADMIN, null, false);

        project(projectX, "迎新晚会", "COMPLETED", LocalDateTime.of(2004, 3, 20, 18, 0));
        project(projectY, "校运会", "COMPLETED", LocalDateTime.of(2004, 3, 31, 23, 30));
        // 四月才结束：按选题结束时间取时不算，按被引时间取时三月里选的图要算。
        project(projectLater, "毕业季", "COMPLETED", LocalDateTime.of(2004, 4, 5, 12, 0));
        project(activeProject, "进行中选题", "ACTIVE", null);

        long p1 = photo(base + 11, campusA, alice, "Alice", LocalDateTime.of(2004, 2, 25, 9, 0));
        long p2 = photo(base + 12, campusA, alice, "Alice", LocalDateTime.of(2004, 3, 2, 9, 0));
        long p3 = photo(base + 13, campusB, alice, "Alice", LocalDateTime.of(2004, 3, 2, 9, 0));
        long p4 = photo(base + 14, campusB, bob, "Bob", LocalDateTime.of(2003, 10, 1, 9, 0));
        // 选题进行中选的图（二月），选题三月结束：按结束时间取要算进来，走势横轴要从二月开始。
        adoption(base + 21, projectX, p1, alice, "Alice", LocalDateTime.of(2004, 2, 26, 10, 0));
        adoption(base + 22, projectX, p2, alice, "Alice", LocalDateTime.of(2004, 3, 2, 10, 0));
        adoption(base + 23, projectX, p4, bob, "Bob", LocalDateTime.of(2004, 3, 3, 10, 0));
        // p2 又被另一个选题引用：次数 +1，张数不变。
        adoption(base + 24, projectY, p2, alice, "Alice", LocalDateTime.of(2004, 3, 31, 23, 0));
        adoption(base + 25, projectY, p3, alice, "Alice ", LocalDateTime.of(2004, 3, 10, 10, 0));
        adoption(base + 26, projectLater, p3, alice, "Alice", LocalDateTime.of(2004, 3, 15, 10, 0));
        adoption(base + 27, activeProject, p4, bob, "Bob", LocalDateTime.of(2004, 4, 1, 0, 0));
        // 取消的被引不算。
        adoption(base + 28, projectY, p4, bob, "Bob", LocalDateTime.of(2004, 3, 12, 10, 0));
        jdbc.sql("UPDATE adoption SET deleted = TRUE WHERE id = :id").param("id", base + 28).update();
    }

    @Test
    void completedBasisMatchesTheMemberAccountingRowForThatPhotographer() {
        AnalysisResult result = service.analyze(query(Basis.PROJECT_COMPLETED, alice, null), admin);

        long memberRow = statistics.members(FROM, TO, null, null, null, admin).stream()
                .filter(row -> row.studentId().equals(alice)).findFirst().orElseThrow().adoptedCount();
        // 成员核算的被引张数按图片去重；点进详情看到的张数必须是同一个数。
        assertThat(result.summary().photoCount()).isEqualTo(memberRow).isEqualTo(3);
        assertThat(result.summary().adoptionCount()).isEqualTo(4);
        assertThat(result.summary().reusedPhotoCount()).isEqualTo(1);
        assertThat(result.projects()).extracting(AdoptionAnalysisService.ProjectBucket::title)
                .containsExactlyInAnyOrder("迎新晚会", "校运会");
        assertThat(result.projects()).allSatisfy(project -> assertThat(project.photoCount()).isEqualTo(2));
        // 姓名快照多了个空格也归到同一个人。
        assertThat(result.photographers()).singleElement().satisfies(person -> {
            assertThat(person.studentId()).isEqualTo(alice);
            assertThat(person.name()).isEqualTo("Alice");
            assertThat(person.campus()).isEqualTo("东校区、西校区");
            assertThat(person.projectCount()).isEqualTo(2);
        });
        // 二月选的那张也在：走势横轴跟着数据从二月开始，而不是被筛选区间截掉。
        assertThat(result.trend().getFirst().key()).isEqualTo("2004-02-26");
        assertThat(result.trend().stream().mapToLong(Bucket::adoptionCount).sum()).isEqualTo(4);
    }

    @Test
    void adoptedBasisCountsByAdoptionTimeIncludingTheWholeLastDay() {
        AnalysisResult result = service.analyze(query(Basis.ADOPTED, null, Granularity.DAY), admin);

        // 三月里被引：p2@X、p4@X、p2@Y（31 日 23 点，结束日要算满）、p3@Y、p3@毕业季；二月和四月的不算，取消的不算。
        assertThat(result.summary().adoptionCount()).isEqualTo(5);
        assertThat(result.summary().photoCount()).isEqualTo(3);
        assertThat(result.summary().projectCount()).isEqualTo(3);
        assertThat(result.trend()).hasSize(31);
        assertThat(result.trend().getLast().adoptionCount()).isEqualTo(1);
        assertThat(result.weekdays()).hasSize(7);
        assertThat(result.photographers()).extracting(AdoptionAnalysisService.PhotographerBucket::studentId)
                .containsExactly(alice, bob);
        // 拍摄当天、或者隔几天被引；Bob 那张是半年前的老图。
        assertThat(result.lags()).extracting(Bucket::adoptionCount).containsExactly(1L, 0L, 0L, 3L, 0L, 1L);
        assertThat(result.rows()).first().satisfies(row -> assertThat(row.adoptionId()).isEqualTo(base + 24));
    }

    @Test
    void narrowsByProjectAndCampus() {
        assertThat(service.analyze(new Query(FROM, TO, Basis.ADOPTED, projectX, null, null, null), admin)
                .summary().adoptionCount()).isEqualTo(2);
        AnalysisResult west = service.analyze(new Query(FROM, TO, Basis.ADOPTED, null, campusB, null, null), admin);
        assertThat(west.campuses()).singleElement().satisfies(campus -> assertThat(campus.label()).isEqualTo("西校区"));
        assertThat(west.summary().photoCount()).isEqualTo(2);
    }

    @Test
    void campusScopedAccountsOnlySeeTheirCampusesAndNothingWhenTheyHaveNone() {
        AnalysisResult east = service.analyze(query(Basis.ADOPTED, null, null), scoped(Set.of(campusA)));
        assertThat(east.rows()).allSatisfy(row -> assertThat(row.campusId()).isEqualTo(campusA));
        assertThat(east.summary().adoptionCount()).isEqualTo(2);
        assertThat(service.options(FROM, TO, Basis.ADOPTED, scoped(Set.of(campusA))).campuses())
                .extracting(AdoptionAnalysisService.Option::label).containsExactly("东校区");

        // 授权校区为空不是「全局」，是什么都看不到。
        assertThat(service.analyze(query(Basis.ADOPTED, null, null), scoped(Set.of())).rows()).isEmpty();
        assertThatThrownBy(() -> service.analyze(
                new Query(FROM, TO, Basis.ADOPTED, null, campusB, null, null), scoped(Set.of(campusA))))
                .hasMessageContaining("无权查看该校区");
    }

    @Test
    void rejectsBadRanges() {
        assertThatThrownBy(() -> service.analyze(new Query(TO, FROM, null, null, null, null, null), admin))
                .hasMessageContaining("开始日期不能晚于结束日期");
        assertThatThrownBy(() -> service.analyze(
                new Query(LocalDate.of(1990, 1, 1), TO, null, null, null, null, null), admin))
                .hasMessageContaining("三年");
    }

    private Query query(Basis basis, String studentId, Granularity granularity) {
        return new Query(FROM, TO, basis, null, null, studentId, granularity);
    }

    private AuthenticatedUser scoped(Set<Long> campuses) {
        return new AuthenticatedUser(userId, "scoped", "校区统计", UserRole.CAMPUS_MANAGER,
                campuses.stream().findFirst().orElse(null), false, 78L, "SCOPED_ADOPTION", "校区统计组",
                DataScope.CAMPUS, Set.of(PermissionCode.STATISTICS_DOWNLOAD), campuses);
    }

    private void project(long id, String title, String status, LocalDateTime completedAt) {
        jdbc.sql("""
                INSERT INTO project(id, title, status, created_by, completed_at)
                VALUES (:id, :title, :status, :userId, :completedAt)
                """).param("id", id).param("title", title).param("status", status).param("userId", userId)
                .param("completedAt", completedAt).update();
    }

    private long photo(long id, long campusId, String studentId, String name, LocalDateTime takenAt) {
        jdbc.sql("""
                INSERT INTO photo
                    (id, title, photographer_student_id, photographer_name, uploaded_by, campus_id, taken_at,
                     size, content_type, object_key, stored_file_name, sha256, status)
                VALUES (:id, NULL, :studentId, :name, :userId, :campusId, :takenAt,
                        100, :type, :objectKey, :fileName, :sha256, 'AVAILABLE')
                """)
                .param("id", id).param("studentId", studentId).param("name", name).param("userId", userId)
                .param("campusId", campusId).param("takenAt", takenAt).param("type", MediaType.IMAGE_JPEG_VALUE)
                .param("objectKey", "adoption-analysis-" + id + ".jpg").param("fileName", "IMG_" + id + ".jpg")
                .param("sha256", Long.toHexString(id) + "a".repeat(64 - Long.toHexString(id).length())).update();
        return id;
    }

    private void adoption(long id, long project, long photoId, String studentId, String name, LocalDateTime at) {
        jdbc.sql("""
                INSERT INTO adoption(id, project_id, photo_id, photographer_student_id, photographer_name,
                                     adopted_by, adopted_at)
                VALUES (:id, :projectId, :photoId, :studentId, :name, :userId, :at)
                """).param("id", id).param("projectId", project).param("photoId", photoId)
                .param("studentId", studentId).param("name", name).param("userId", userId).param("at", at).update();
    }
}
