package cn.photolib.statistics;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StatisticsService {
    private final JdbcClient jdbc;

    public List<MemberStatistics> members(LocalDate from, LocalDate to, Long projectId,
                                          Long campusId, Long userId) {
        return jdbc.sql("""
                WITH eligible_projects AS (
                    SELECT id
                    FROM project
                    WHERE deleted=0 AND status='COMPLETED'
                      AND DATE(completed_at) BETWEEN :fromDate AND :toDate
                      AND (:projectId=0 OR id=:projectId)
                ),
                adoption_totals AS (
                    SELECT a.photographer_student_id, COUNT(DISTINCT a.photo_id) AS adopted_count
                    FROM adoption a
                    JOIN photo p ON p.id=a.photo_id
                    JOIN eligible_projects ep ON ep.id=a.project_id
                    WHERE a.deleted=0
                      AND (:campusId=0 OR p.campus_id=:campusId)
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
                  AND (:userId=0 OR w.user_id=:userId)
                GROUP BY w.member_student_id,w.member_name
                ORDER BY w.member_name
                """).param("fromDate", from == null ? LocalDate.of(1900,1,1) : from)
                .param("toDate", to == null ? LocalDate.of(2999,12,31) : to)
                .param("projectId", projectId == null ? 0L : projectId)
                .param("campusId", campusId == null ? 0L : campusId)
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
                WHERE a.deleted=0 AND DATE(a.adopted_at) BETWEEN :fromDate AND :toDate
                  AND (:projectId=0 OR a.project_id=:projectId)
                  AND (:campusId=0 OR p.campus_id=:campusId)
                GROUP BY a.photographer_student_id,a.photographer_name
                ORDER BY COUNT(*) DESC
                """).param("fromDate", from == null ? LocalDate.of(1900,1,1) : from)
                .param("toDate", to == null ? LocalDate.of(2999,12,31) : to)
                .param("projectId", projectId == null ? 0L : projectId)
                .param("campusId", campusId == null ? 0L : campusId)
                .query((rs, n) -> new AdoptionStatistics(rs.getString(1), rs.getString(2), rs.getLong(3)))
                .list();
    }

    public List<WorklogExportRow> worklogs(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                WITH matched_worklogs AS (
                    SELECT w.member_student_id, w.member_name, MIN(c.name) AS campus,
                           SUM(w.shooting_minutes) AS shooting_minutes,
                           SUM(w.retouching_minutes) AS retouching_minutes
                    FROM worklog w
                    JOIN photo_request r ON r.id=w.request_id
                    LEFT JOIN campus c ON c.id=r.campus_id
                    WHERE w.deleted=0
                      AND w.work_date BETWEEN :fromDate AND :toDate
                    GROUP BY w.member_student_id, w.member_name
                ),
                adoption_totals AS (
                    SELECT a.photographer_student_id, COUNT(*) AS adopted_count
                    FROM adoption a
                    JOIN project p ON p.id=a.project_id
                    WHERE a.deleted=0
                      AND p.deleted=0
                      AND p.status='COMPLETED'
                      AND DATE(p.completed_at) BETWEEN :fromDate AND :toDate
                      AND EXISTS (
                          SELECT 1 FROM matched_worklogs mw
                          WHERE mw.member_student_id=a.photographer_student_id
                      )
                    GROUP BY a.photographer_student_id
                )
                SELECT mw.member_name, mw.member_student_id, mw.campus,
                       mw.shooting_minutes, mw.retouching_minutes,
                       COALESCE(a.adopted_count, 0)
                FROM matched_worklogs mw
                LEFT JOIN adoption_totals a
                  ON a.photographer_student_id=mw.member_student_id
                ORDER BY mw.member_name, mw.member_student_id
                """)
                .param("fromDate", from)
                .param("toDate", to)
                .query((rs, n) -> {
                    int shooting = rs.getInt(4);
                    int retouching = rs.getInt(5);
                    return new WorklogExportRow(rs.getString(1), rs.getString(2), rs.getString(3),
                            shooting, retouching, shooting + retouching, rs.getLong(6));
                }).list();
    }

    public Map<String, Long> overview(LocalDate from, LocalDate to, Long projectId) {
        long projects = jdbc.sql("SELECT COUNT(*) FROM project WHERE deleted=0 AND (:p=0 OR id=:p)")
                .param("p", projectId == null ? 0L : projectId).query(Long.class).single();
        long requests = jdbc.sql("SELECT COUNT(*) FROM photo_request WHERE deleted=0 AND (:p=0 OR project_id=:p)")
                .param("p", projectId == null ? 0L : projectId).query(Long.class).single();
        long photos = jdbc.sql("SELECT COUNT(*) FROM photo WHERE deleted=0 AND status='AVAILABLE' AND (:p=0 OR project_id=:p)")
                .param("p", projectId == null ? 0L : projectId).query(Long.class).single();
        long adoptions = jdbc.sql("SELECT COUNT(*) FROM adoption WHERE deleted=0 AND (:p=0 OR project_id=:p)")
                .param("p", projectId == null ? 0L : projectId).query(Long.class).single();
        List<MemberStatistics> members = members(from, to, projectId, null, null);
        long shooting = members.stream().mapToLong(MemberStatistics::shootingMinutes).sum();
        long retouching = members.stream().mapToLong(MemberStatistics::retouchingMinutes).sum();
        return Map.of("projects", projects, "requests", requests, "photos", photos,
                "adoptions", adoptions, "shootingMinutes", shooting, "retouchingMinutes", retouching);
    }

    public record MemberStatistics(Long userId, String studentId, String displayName, String campus,
                                   int shootingMinutes, int retouchingMinutes, int totalMinutes,
                                   long adoptedCount) {}
    public record AdoptionStatistics(String photographerStudentId, String photographerName,
                                     long adoptedCount) {}
    public record WorklogExportRow(String memberName, String studentId, String campus,
                                   int shootingMinutes, int retouchingMinutes, int totalMinutes,
                                   long adoptedCount) {}
}
