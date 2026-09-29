package cn.photolib.dashboard.mapper;

import lombok.Data;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据面板上的业务计数。都是只读的聚合，走的是已有索引或小表；结果在
 * {@code AdminDashboardService} 里缓存 30 秒，面板每几秒轮询一次也不会反复扫表。
 */
public interface DashboardStatsMapper {
    @Select("SELECT COUNT(*) FROM app_user WHERE deleted = FALSE")
    long countUsers();

    @Select("SELECT COUNT(*) FROM app_user WHERE deleted = FALSE AND enabled = TRUE")
    long countEnabledUsers();

    @Select("SELECT COUNT(*) FROM app_user WHERE deleted = FALSE AND created_at >= #{since}")
    long countUsersCreatedSince(@Param("since") LocalDateTime since);

    @Select("SELECT COUNT(*) FROM registration_application WHERE deleted = FALSE AND status = 'PENDING'")
    long countPendingRegistrations();

    /** 还能用来续期的会话数（没被吊销、没过闲置期限），一个人多个设备算多个。 */
    @Select("SELECT COUNT(*) FROM auth_session WHERE revoked_at IS NULL AND idle_expires_at > #{now}")
    long countLiveSessions(@Param("now") LocalDateTime now);

    @Select("SELECT COUNT(*) FROM photo WHERE deleted = FALSE AND status = 'AVAILABLE'")
    long countPhotos();

    @Select("SELECT COALESCE(SUM(size), 0) FROM photo WHERE deleted = FALSE AND status = 'AVAILABLE'")
    long sumPhotoBytes();

    @Select("SELECT COUNT(*) FROM photo WHERE deleted = FALSE AND status = 'AVAILABLE' AND created_at >= #{since}")
    long countPhotosCreatedSince(@Param("since") LocalDateTime since);

    @Select("SELECT COUNT(*) FROM project WHERE deleted = FALSE")
    long countProjects();

    @Select("SELECT COUNT(*) FROM project WHERE deleted = FALSE AND status = 'ACTIVE'")
    long countActiveProjects();

    @Select("SELECT COUNT(*) FROM photo_request WHERE deleted = FALSE AND status IN ('PUBLISHED', 'ACCEPTED', 'SUBMITTED')")
    long countOpenRequests();

    @Select("SELECT COUNT(*) FROM admin_alert WHERE resolved = FALSE")
    long countUnresolvedAlerts();

    @Select("""
            SELECT CAST(created_at AS DATE) AS stat_day, COUNT(*) AS total FROM app_user
            WHERE deleted = FALSE AND created_at >= #{since}
            GROUP BY CAST(created_at AS DATE)
            """)
    List<DayCount> dailyUsers(@Param("since") LocalDateTime since);

    @Select("""
            SELECT CAST(created_at AS DATE) AS stat_day, COUNT(*) AS total FROM photo
            WHERE deleted = FALSE AND status = 'AVAILABLE' AND created_at >= #{since}
            GROUP BY CAST(created_at AS DATE)
            """)
    List<DayCount> dailyPhotos(@Param("since") LocalDateTime since);

    @Data
    class DayCount {
        /** 列名避开 {@code day}：它在 H2 里是保留字。 */
        private LocalDate statDay;
        private long total;
    }
}
