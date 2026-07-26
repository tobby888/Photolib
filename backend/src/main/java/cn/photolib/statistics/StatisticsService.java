package cn.photolib.statistics;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class StatisticsService {
    private final JdbcClient jdbc;

    public List<MemberStatistics> members(LocalDate from, LocalDate to, Long projectId,
                                          Long campusId, Long userId) {
        return members(from, to, projectId, campusId, userId, Set.of());
    }

    public List<MemberStatistics> members(LocalDate from, LocalDate to, Long projectId,
                                          Long campusId, Long userId, AuthenticatedUser principal) {
        if (principal.isCampusScoped() && campusId != null && !principal.canAccessCampus(campusId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看该校区的统计数据");
        }
        return members(from, to, projectId, campusId, userId, principal.scopedCampusIds());
    }

    public List<MemberStatistics> members(LocalDate from, LocalDate to, Long projectId,
                                          Long campusId, Long userId, Set<Long> allowedCampusIds) {
        Set<Long> scopedIds = normalizedCampusIds(allowedCampusIds);
        boolean campusScoped = allowedCampusIds != null && !allowedCampusIds.isEmpty();
        return jdbc.sql("""
                WITH eligible_projects AS (
                    SELECT id
                    FROM project
                    WHERE deleted=0 AND status='COMPLETED'
                      AND completed_at >= :fromDate AND completed_at < :toExclusive
                      AND (:projectId=0 OR id=:projectId)
                ),
                adoption_totals AS (
                    SELECT a.photographer_student_id, COUNT(DISTINCT a.photo_id) AS adopted_count
                    FROM adoption a
                    JOIN photo p ON p.id=a.photo_id
                    JOIN eligible_projects ep ON ep.id=a.project_id
                    WHERE a.deleted=0
                      AND (:campusId=0 OR p.campus_id=:campusId)
                      AND (:campusScoped=FALSE OR p.campus_id IN (:campusIds))
                    GROUP BY a.photographer_student_id
                )
                SELECT MIN(w.user_id), w.member_student_id, w.member_name, MIN(c.name),
                       COALESCE(SUM(w.shooting_minutes),0),
                       COALESCE(SUM(w.retouching_minutes),0),
                       COALESCE(MAX(a.adopted_count),0)
                FROM worklog w
                JOIN photo_request r ON r.id=w.request_id
                JOIN eligible_projects ep ON ep.id=r.project_id
                LEFT JOIN campus c ON c.id=r.campus_id
                LEFT JOIN adoption_totals a ON a.photographer_student_id=w.member_student_id
                WHERE w.deleted=0 AND w.status='CONFIRMED'
                  AND (:campusId=0 OR r.campus_id=:campusId)
                  AND (:campusScoped=FALSE OR r.campus_id IN (:campusIds))
                  AND (:userId=0 OR w.user_id=:userId)
                GROUP BY w.member_student_id,w.member_name
                ORDER BY w.member_name
                """).param("fromDate", from == null ? LocalDate.of(1900,1,1) : from)
                .param("toExclusive", to == null ? LocalDate.of(3000,1,1) : to.plusDays(1))
                .param("projectId", projectId == null ? 0L : projectId)
                .param("campusId", campusId == null ? 0L : campusId)
                .param("campusScoped", campusScoped)
                .param("campusIds", scopedIds)
                .param("userId", userId == null ? 0L : userId)
                .query((rs, n) -> {
                    int shooting = rs.getInt(5);
                    int retouching = rs.getInt(6);
                    return new MemberStatistics(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            shooting, retouching, shooting + retouching, rs.getLong(7));
                }).list();
    }

    public List<AdoptionStatistics> adoptions(LocalDate from, LocalDate to, Long projectId, Long campusId) {
        return jdbc.sql("""
                SELECT a.photographer_student_id,a.photographer_name,COUNT(*)
                FROM adoption a JOIN photo p ON p.id=a.photo_id
                WHERE a.deleted=0 AND a.adopted_at >= :fromDate AND a.adopted_at < :toExclusive
                  AND (:projectId=0 OR a.project_id=:projectId)
                  AND (:campusId=0 OR p.campus_id=:campusId)
                GROUP BY a.photographer_student_id,a.photographer_name
                ORDER BY COUNT(*) DESC
                """).param("fromDate", from == null ? LocalDate.of(1900,1,1) : from)
                .param("toExclusive", to == null ? LocalDate.of(3000,1,1) : to.plusDays(1))
                .param("projectId", projectId == null ? 0L : projectId)
                .param("campusId", campusId == null ? 0L : campusId)
                .query((rs, n) -> new AdoptionStatistics(rs.getString(1), rs.getString(2), rs.getLong(3)))
                .list();
    }

    public List<WorklogExportRow> worklogs(LocalDate from, LocalDate to) {
        return worklogs(from, to, Set.of());
    }

    public List<WorklogExportRow> worklogs(LocalDate from, LocalDate to,
                                           Set<Long> allowedCampusIds) {
        Set<Long> scopedIds = normalizedCampusIds(allowedCampusIds);
        boolean campusScoped = allowedCampusIds != null && !allowedCampusIds.isEmpty();
        return jdbc.sql("""
                WITH confirmed_worklogs AS (
                    SELECT w.member_student_id, MIN(w.member_name) AS member_name, MIN(c.name) AS campus,
                           SUM(w.shooting_minutes) AS shooting_minutes,
                           SUM(w.retouching_minutes) AS retouching_minutes
                    FROM worklog w
                    JOIN photo_request r ON r.id=w.request_id
                    LEFT JOIN campus c ON c.id=r.campus_id
                    WHERE w.deleted=0
                      AND w.status='CONFIRMED'
                      AND w.work_date BETWEEN :fromDate AND :toDate
                      AND (:campusScoped=FALSE OR r.campus_id IN (:campusIds))
                    GROUP BY w.member_student_id
                ),
                worklog_statuses AS (
                    SELECT w.member_student_id,
                           MAX(CASE WHEN w.status='CONFIRMED' THEN 1 ELSE 0 END) AS has_confirmed,
                           MAX(CASE WHEN w.status='SUBMITTED' THEN 1 ELSE 0 END) AS has_submitted,
                           MAX(CASE WHEN w.status='REJECTED' THEN 1 ELSE 0 END) AS has_rejected,
                           MAX(CASE WHEN w.status='DRAFT' THEN 1 ELSE 0 END) AS has_draft
                    FROM worklog w
                    JOIN photo_request status_r ON status_r.id=w.request_id
                    WHERE w.deleted=0
                      AND w.work_date BETWEEN :fromDate AND :toDate
                      AND (:campusScoped=FALSE OR status_r.campus_id IN (:campusIds))
                    GROUP BY w.member_student_id
                ),
                adoption_totals AS (
                    SELECT a.photographer_student_id,
                           MIN(a.photographer_name) AS photographer_name,
                           MIN(c.name) AS campus,
                           COUNT(DISTINCT a.photo_id) AS adopted_count
                    FROM adoption a
                    JOIN project p ON p.id=a.project_id
                    JOIN photo ph ON ph.id=a.photo_id
                    LEFT JOIN campus c ON c.id=ph.campus_id
                    WHERE a.deleted=0
                      AND p.deleted=0
                      AND p.status='COMPLETED'
                      AND p.completed_at >= :fromDate AND p.completed_at < :toExclusive
                      AND (:campusScoped=FALSE OR ph.campus_id IN (:campusIds))
                    GROUP BY a.photographer_student_id
                ),
                export_people AS (
                    SELECT member_student_id AS student_id FROM confirmed_worklogs
                    UNION
                    SELECT photographer_student_id AS student_id FROM adoption_totals
                )
                SELECT COALESCE(mw.member_name, a.photographer_name), people.student_id,
                       COALESCE(mw.campus, a.campus),
                       COALESCE(mw.shooting_minutes, 0), COALESCE(mw.retouching_minutes, 0),
                       COALESCE(a.adopted_count, 0),
                       CASE
                           WHEN statuses.has_confirmed=1 THEN '已确认'
                           WHEN statuses.has_submitted=1 THEN '待确认'
                           WHEN statuses.has_rejected=1 THEN '已退回'
                           WHEN statuses.has_draft=1 THEN '草稿'
                           ELSE '未申报'
                       END
                FROM export_people people
                LEFT JOIN confirmed_worklogs mw
                  ON mw.member_student_id=people.student_id
                LEFT JOIN adoption_totals a
                  ON a.photographer_student_id=people.student_id
                LEFT JOIN worklog_statuses statuses
                  ON statuses.member_student_id=people.student_id
                ORDER BY COALESCE(mw.member_name, a.photographer_name), people.student_id
                """)
                .param("fromDate", from)
                .param("toDate", to)
                .param("toExclusive", to.plusDays(1))
                .param("campusScoped", campusScoped)
                .param("campusIds", scopedIds)
                .query((rs, n) -> {
                    int shooting = rs.getInt(4);
                    int retouching = rs.getInt(5);
                    return new WorklogExportRow(rs.getString(1), rs.getString(2), rs.getString(3),
                            shooting, retouching, shooting + retouching, rs.getLong(6), rs.getString(7));
                }).list();
    }

    public Map<String, Long> overview(LocalDate from, LocalDate to, Long projectId) {
        return overview(from, to, projectId, Set.of());
    }

    public Map<String, Long> overview(LocalDate from, LocalDate to, Long projectId,
                                      AuthenticatedUser principal) {
        return overview(from, to, projectId, principal.scopedCampusIds());
    }

    private Map<String, Long> overview(LocalDate from, LocalDate to, Long projectId,
                                       Set<Long> allowedCampusIds) {
        Set<Long> scopedIds = normalizedCampusIds(allowedCampusIds);
        boolean campusScoped = allowedCampusIds != null && !allowedCampusIds.isEmpty();
        long projects = jdbc.sql("""
                SELECT COUNT(*) FROM project p WHERE p.deleted=0 AND (:p=0 OR p.id=:p)
                  AND (:campusScoped=FALSE OR EXISTS (
                      SELECT 1 FROM photo_request r WHERE r.project_id=p.id AND r.deleted=FALSE
                        AND r.campus_id IN (:campusIds)))
                """).param("p", projectId == null ? 0L : projectId)
                .param("campusScoped", campusScoped).param("campusIds", scopedIds)
                .query(Long.class).single();
        long requests = jdbc.sql("""
                SELECT COUNT(*) FROM photo_request
                WHERE deleted=0 AND (:p=0 OR project_id=:p)
                  AND (:campusScoped=FALSE OR campus_id IN (:campusIds))
                """).param("p", projectId == null ? 0L : projectId)
                .param("campusScoped", campusScoped).param("campusIds", scopedIds)
                .query(Long.class).single();
        long photos = jdbc.sql("""
                SELECT COUNT(*) FROM photo p WHERE p.deleted=0 AND p.status='AVAILABLE'
                  AND (:p=0 OR EXISTS (SELECT 1 FROM photo_project pp
                                        WHERE pp.photo_id=p.id AND pp.project_id=:p))
                  AND (:campusScoped=FALSE OR p.campus_id IN (:campusIds))
                """)
                .param("p", projectId == null ? 0L : projectId)
                .param("campusScoped", campusScoped).param("campusIds", scopedIds)
                .query(Long.class).single();
        long adoptions = jdbc.sql("""
                SELECT COUNT(*) FROM adoption a JOIN photo ph ON ph.id=a.photo_id
                WHERE a.deleted=0 AND (:p=0 OR a.project_id=:p)
                  AND (:campusScoped=FALSE OR ph.campus_id IN (:campusIds))
                """).param("p", projectId == null ? 0L : projectId)
                .param("campusScoped", campusScoped).param("campusIds", scopedIds)
                .query(Long.class).single();
        List<MemberStatistics> members = members(from, to, projectId, null, null, allowedCampusIds);
        long shooting = members.stream().mapToLong(MemberStatistics::shootingMinutes).sum();
        long retouching = members.stream().mapToLong(MemberStatistics::retouchingMinutes).sum();
        return Map.of("projects", projects, "requests", requests, "photos", photos,
                "adoptions", adoptions, "shootingMinutes", shooting, "retouchingMinutes", retouching);
    }

    /**
     * 找出该期已完成项目中有被引（采用）记录、但其学号无法匹配到任何该期已确认工时成员的摄影师。
     * 口径与 {@link #worklogs} 的被引侧一致（项目 COMPLETED 且 completed_at 落在区间）。
     * 这类摄影师会进入工时导出并显示实际被引数，同时标注其申报/审核状态，仍需人工核对。
     */
    public List<UnmatchedAdoption> unmatchedAdoptions(LocalDate from, LocalDate to) {
        return unmatchedAdoptions(from, to, Set.of());
    }

    public List<UnmatchedAdoption> unmatchedAdoptions(LocalDate from, LocalDate to,
                                                       Set<Long> allowedCampusIds) {
        Set<Long> scopedIds = normalizedCampusIds(allowedCampusIds);
        boolean campusScoped = allowedCampusIds != null && !allowedCampusIds.isEmpty();
        return jdbc.sql("""
                SELECT a.photographer_student_id,
                       MIN(a.photographer_name) AS photographer_name,
                       COUNT(DISTINCT a.photo_id) AS adopted_count
                FROM adoption a
                JOIN project p ON p.id=a.project_id
                JOIN photo ph ON ph.id=a.photo_id
                WHERE a.deleted=0
                  AND p.deleted=0
                  AND p.status='COMPLETED'
                  AND p.completed_at >= :fromDate AND p.completed_at < :toExclusive
                  AND (:campusScoped=FALSE OR ph.campus_id IN (:campusIds))
                  AND NOT EXISTS (
                      SELECT 1 FROM worklog w
                      WHERE w.deleted=0
                        AND w.status='CONFIRMED'
                        AND w.work_date BETWEEN :fromDate AND :toDate
                        AND w.member_student_id=a.photographer_student_id
                  )
                GROUP BY a.photographer_student_id
                ORDER BY adopted_count DESC, a.photographer_student_id
                """)
                .param("fromDate", from)
                .param("toDate", to)
                .param("toExclusive", to.plusDays(1))
                .param("campusScoped", campusScoped)
                .param("campusIds", scopedIds)
                .query((rs, n) -> new UnmatchedAdoption(rs.getString(1), rs.getString(2), rs.getLong(3)))
                .list();
    }

    private Set<Long> normalizedCampusIds(Set<Long> allowedCampusIds) {
        return allowedCampusIds == null || allowedCampusIds.isEmpty() ? Set.of(-1L) : allowedCampusIds;
    }

    public record MemberStatistics(Long userId, String studentId, String displayName, String campus,
                                   int shootingMinutes, int retouchingMinutes, int totalMinutes,
                                   long adoptedCount) {}
    public record AdoptionStatistics(String photographerStudentId, String photographerName,
                                     long adoptedCount) {}
    public record WorklogExportRow(String memberName, String studentId, String campus,
                                   int shootingMinutes, int retouchingMinutes, int totalMinutes,
                                   long adoptedCount, String worklogStatus) {}
    public record UnmatchedAdoption(String photographerStudentId, String photographerName,
                                    long adoptedCount) {}
}
