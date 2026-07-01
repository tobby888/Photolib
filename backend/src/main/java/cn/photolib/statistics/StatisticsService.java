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
                SELECT u.id, u.display_name, c.name,
                       COALESCE(SUM(w.shooting_minutes),0),
                       COALESCE(SUM(w.retouching_minutes),0)
                FROM app_user u
                LEFT JOIN campus c ON c.id=u.campus_id
                JOIN worklog w ON w.user_id=u.id AND w.deleted=0 AND w.status='CONFIRMED'
                JOIN photo_request r ON r.id=w.request_id
                WHERE w.work_date BETWEEN :fromDate AND :toDate
                  AND (:projectId=0 OR r.project_id=:projectId)
                  AND (:campusId=0 OR u.campus_id=:campusId)
                  AND (:userId=0 OR u.id=:userId)
                GROUP BY u.id,u.display_name,c.name
                ORDER BY u.display_name
                """).param("fromDate", from == null ? LocalDate.of(1900,1,1) : from)
                .param("toDate", to == null ? LocalDate.of(2999,12,31) : to)
                .param("projectId", projectId == null ? 0L : projectId)
                .param("campusId", campusId == null ? 0L : campusId)
                .param("userId", userId == null ? 0L : userId)
                .query((rs, n) -> {
                    int shooting = rs.getInt(4);
                    int retouching = rs.getInt(5);
                    return new MemberStatistics(rs.getLong(1), rs.getString(2), rs.getString(3),
                            shooting, retouching, shooting + retouching);
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

    public record MemberStatistics(Long userId, String displayName, String campus,
                                   int shootingMinutes, int retouchingMinutes, int totalMinutes) {}
    public record AdoptionStatistics(String photographerStudentId, String photographerName,
                                     long adoptedCount) {}
}
