package cn.photolib.settlement;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 邮件清算单的数据：收件人候选名单，以及某个账号在一段时间内的工时和被引明细。
 *
 * <p><b>按姓名匹配。</b>收件人是系统账号（{@code app_user}，有邮箱的才能收），工时和被引记录里存的是
 * 成员姓名快照（{@code worklog.member_name}、{@code adoption.photographer_name}），两边没有学号或账号 id
 * 可以对。所以按去掉首尾空白后的姓名完全相等来取。两个在用账号同名时无法区分谁是谁，
 * {@link Recipient#sameNameCount()} 大于 0 的账号不能收清算单（{@link SettlementMailService} 会拒绝），
 * 免得把一个人的工时发给另一个人。</p>
 *
 * <p><b>时间口径</b>：工时按工作日期 {@code work_date}（两端都含），草稿不算；被引按被引时间
 * {@code adopted_at}（同样两端都含）。与成员核算按"选题结束时间"取的口径不同，邮件里写明了。</p>
 */
@Service
@RequiredArgsConstructor
public class SettlementStatementService {
    static final int MAX_RANGE_DAYS = 366 * 3;
    /** 单封邮件里最多列多少条，超出的在邮件里注明「另有 N 条」——再多邮件客户端会截断正文。 */
    static final int MAX_LINES = 800;

    private final JdbcClient jdbc;

    static void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择清算时间范围");
        if (to.isBefore(from)) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "结束日期不能早于开始日期");
        if (from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "清算时间范围最长三年");
        }
    }

    /** 全部在用账号（含没有邮箱的，前端标灰），附带这段时间内按姓名匹配到的记录概况。 */
    public List<Recipient> recipients(LocalDate from, LocalDate to) {
        validateRange(from, to);
        return jdbc.sql("""
                WITH wl AS (
                    SELECT TRIM(member_name) AS name, COUNT(*) AS cnt,
                           SUM(CASE WHEN status='CONFIRMED' THEN shooting_minutes + retouching_minutes ELSE 0 END) AS confirmed_minutes,
                           SUM(CASE WHEN status='SUBMITTED' THEN 1 ELSE 0 END) AS pending_count,
                           SUM(CASE WHEN status='REJECTED' THEN 1 ELSE 0 END) AS rejected_count
                    FROM worklog
                    WHERE deleted=0 AND status<>'DRAFT' AND member_name IS NOT NULL
                      AND work_date >= :fromDate AND work_date <= :toDate
                    GROUP BY TRIM(member_name)
                ),
                ad AS (
                    SELECT TRIM(a.photographer_name) AS name, COUNT(DISTINCT a.photo_id) AS photos, COUNT(*) AS adoptions
                    FROM adoption a JOIN project p ON p.id=a.project_id
                    WHERE a.deleted=0 AND p.deleted=0
                      AND a.adopted_at >= :fromDate AND a.adopted_at < :toExclusive
                    GROUP BY TRIM(a.photographer_name)
                ),
                names AS (
                    SELECT TRIM(display_name) AS name, COUNT(*) AS cnt
                    FROM app_user WHERE deleted=0 AND enabled=TRUE
                    GROUP BY TRIM(display_name)
                )
                SELECT u.id, u.username, TRIM(u.display_name), u.email, c.name, g.name,
                       COALESCE(wl.cnt,0), COALESCE(wl.confirmed_minutes,0), COALESCE(wl.pending_count,0),
                       COALESCE(wl.rejected_count,0), COALESCE(ad.photos,0), COALESCE(ad.adoptions,0),
                       COALESCE(n.cnt,1) - 1
                FROM app_user u
                LEFT JOIN campus c ON c.id=u.campus_id
                LEFT JOIN permission_group g ON g.id=u.permission_group_id
                LEFT JOIN wl ON wl.name=TRIM(u.display_name)
                LEFT JOIN ad ON ad.name=TRIM(u.display_name)
                LEFT JOIN names n ON n.name=TRIM(u.display_name)
                WHERE u.deleted=0 AND u.enabled=TRUE
                ORDER BY TRIM(u.display_name), u.id
                """)
                .param("fromDate", from).param("toDate", to).param("toExclusive", to.plusDays(1))
                .query((rs, n) -> new Recipient(rs.getLong(1), rs.getString(2), rs.getString(3),
                        blankToNull(rs.getString(4)), rs.getString(5), rs.getString(6),
                        rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getInt(10), rs.getInt(11), rs.getInt(12),
                        rs.getInt(13)))
                .list();
    }

    public Recipient recipient(long userId, LocalDate from, LocalDate to) {
        return recipients(from, to).stream().filter(r -> r.userId() == userId).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "账号不存在或已停用"));
    }

    /** 某个账号的清算单明细。调用方负责先确认这个账号能收（有邮箱、不重名）。 */
    public Statement statement(Recipient recipient, LocalDate from, LocalDate to) {
        validateRange(from, to);
        String name = recipient.displayName();
        List<WorklogLine> worklogs = new ArrayList<>(jdbc.sql("""
                SELECT w.id, w.work_date, p.title, r.title, c.name, w.share_link_id, l.name,
                       w.shooting_minutes, w.retouching_minutes, w.status, w.remark, w.reject_reason,
                       w.created_at, w.confirmed_at, rv.display_name, w.member_student_id
                FROM worklog w
                LEFT JOIN photo_request r ON r.id=w.request_id
                LEFT JOIN project_share_link l ON l.id=w.share_link_id
                LEFT JOIN project p ON p.id=COALESCE(r.project_id, l.project_id)
                LEFT JOIN campus c ON c.id=r.campus_id
                LEFT JOIN app_user rv ON rv.id=w.confirmed_by
                WHERE w.deleted=0 AND w.status<>'DRAFT' AND TRIM(w.member_name)=:name
                  AND w.work_date >= :fromDate AND w.work_date <= :toDate
                ORDER BY w.work_date, w.id
                """)
                .param("name", name).param("fromDate", from).param("toDate", to)
                .query((rs, n) -> new WorklogLine(rs.getLong(1), rs.getObject(2, LocalDate.class),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getObject(6, Long.class) != null,
                        rs.getString(7), rs.getInt(8), rs.getInt(9), rs.getString(10), rs.getString(11),
                        rs.getString(12), rs.getObject(13, LocalDateTime.class), rs.getObject(14, LocalDateTime.class),
                        rs.getString(15), rs.getString(16), new ArrayList<>()))
                .list());
        attachReviewEvents(worklogs);
        List<AdoptionLine> adoptions = jdbc.sql("""
                SELECT a.adopted_at, p.title, a.photo_id, COALESCE(ph.title, ph.stored_file_name), c.name,
                       u.display_name, a.remark
                FROM adoption a
                JOIN project p ON p.id=a.project_id
                JOIN photo ph ON ph.id=a.photo_id
                LEFT JOIN campus c ON c.id=ph.campus_id
                LEFT JOIN app_user u ON u.id=a.adopted_by
                WHERE a.deleted=0 AND p.deleted=0 AND TRIM(a.photographer_name)=:name
                  AND a.adopted_at >= :fromDate AND a.adopted_at < :toExclusive
                ORDER BY a.adopted_at, a.id
                """)
                .param("name", name).param("fromDate", from).param("toExclusive", to.plusDays(1))
                .query((rs, n) -> new AdoptionLine(rs.getObject(1, LocalDateTime.class), rs.getString(2),
                        rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)))
                .list();
        Totals totals = Totals.of(worklogs, adoptions);
        return new Statement(recipient, from, to,
                worklogs.size() > MAX_LINES ? List.copyOf(worklogs.subList(0, MAX_LINES)) : List.copyOf(worklogs),
                adoptions.size() > MAX_LINES ? List.copyOf(adoptions.subList(0, MAX_LINES)) : List.copyOf(adoptions),
                totals);
    }

    /** 一次查出这批工时的全部审核记录，按工时 id 挂上去（不逐条查）。 */
    private void attachReviewEvents(List<WorklogLine> worklogs) {
        if (worklogs.isEmpty()) return;
        Map<Long, WorklogLine> byId = new LinkedHashMap<>();
        worklogs.forEach(line -> byId.put(line.id(), line));
        for (List<Long> chunk : chunks(byId.keySet(), 500)) {
            jdbc.sql("""
                    SELECT e.worklog_id, e.action, e.created_at, u.display_name, e.reason,
                           e.shooting_minutes, e.retouching_minutes, e.backfilled
                    FROM worklog_review_log e
                    LEFT JOIN app_user u ON u.id=e.actor_id
                    WHERE e.worklog_id IN (:ids)
                    ORDER BY e.created_at, e.id
                    """).param("ids", chunk)
                    .query((rs, n) -> {
                        WorklogLine line = byId.get(rs.getLong(1));
                        line.events().add(new ReviewEvent(rs.getString(2), rs.getObject(3, LocalDateTime.class),
                                rs.getString(4), rs.getString(5), rs.getObject(6, Integer.class),
                                rs.getObject(7, Integer.class), rs.getBoolean(8)));
                        return 0;
                    }).list();
        }
    }

    private static List<List<Long>> chunks(Collection<Long> ids, int size) {
        List<Long> all = new ArrayList<>(ids);
        List<List<Long>> result = new ArrayList<>();
        for (int i = 0; i < all.size(); i += size) result.add(all.subList(i, Math.min(all.size(), i + size)));
        return result;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 收件人候选。{@code sameNameCount}：另有几个在用账号与他同名，大于 0 时不能收清算单。
     * {@code worklogCount} 含待审核和已退回（不含草稿）。
     */
    public record Recipient(long userId, String username, String displayName, String email, String campus,
                            String groupName, int worklogCount, int confirmedMinutes, int pendingCount,
                            int rejectedCount, int adoptedPhotos, int adoptionCount, int sameNameCount) {
        public boolean hasRecords() {
            return worklogCount > 0 || adoptionCount > 0;
        }
    }

    /** {@code events}：这条工时的每一次提交 / 确认 / 退回，按时间先后。 */
    public record WorklogLine(long id, LocalDate workDate, String projectTitle, String requestTitle, String campus,
                              boolean viaShareLink, String shareLinkName, int shootingMinutes,
                              int retouchingMinutes, String status, String remark, String rejectReason,
                              LocalDateTime createdAt, LocalDateTime reviewedAt, String reviewerName,
                              String memberStudentId, List<ReviewEvent> events) {
        public int totalMinutes() {
            return shootingMinutes + retouchingMinutes;
        }
    }

    /** {@code backfilled}：V71 之前的记录，时间是按创建 / 审核时间回填的近似值。 */
    public record ReviewEvent(String action, LocalDateTime at, String actorName, String reason,
                              Integer shootingMinutes, Integer retouchingMinutes, boolean backfilled) {}

    public record AdoptionLine(LocalDateTime adoptedAt, String projectTitle, long photoId, String photoTitle,
                               String campus, String adoptedBy, String remark) {}

    public record Totals(int worklogCount, int confirmedCount, int confirmedShootingMinutes,
                         int confirmedRetouchingMinutes, int pendingCount, int pendingMinutes,
                         int rejectedCount, int rejectedMinutes, int adoptedPhotos, int adoptionCount) {
        public int confirmedMinutes() {
            return confirmedShootingMinutes + confirmedRetouchingMinutes;
        }

        static Totals of(List<WorklogLine> worklogs, List<AdoptionLine> adoptions) {
            int confirmed = 0, shooting = 0, retouching = 0, pending = 0, pendingMinutes = 0, rejected = 0, rejectedMinutes = 0;
            for (WorklogLine line : worklogs) {
                switch (line.status()) {
                    case "CONFIRMED" -> {
                        confirmed++;
                        shooting += line.shootingMinutes();
                        retouching += line.retouchingMinutes();
                    }
                    case "SUBMITTED" -> {
                        pending++;
                        pendingMinutes += line.totalMinutes();
                    }
                    case "REJECTED" -> {
                        rejected++;
                        rejectedMinutes += line.totalMinutes();
                    }
                    default -> { }
                }
            }
            long photos = adoptions.stream().mapToLong(AdoptionLine::photoId).distinct().count();
            return new Totals(worklogs.size(), confirmed, shooting, retouching, pending, pendingMinutes,
                    rejected, rejectedMinutes, (int) photos, adoptions.size());
        }
    }

    /** 明细超过 {@link #MAX_LINES} 时只列前面这些，{@code totals} 仍按全部记录算。 */
    public record Statement(Recipient recipient, LocalDate from, LocalDate to, List<WorklogLine> worklogs,
                            List<AdoptionLine> adoptions, Totals totals) {
        public int hiddenWorklogs() {
            return Math.max(0, totals.worklogCount() - worklogs.size());
        }

        public int hiddenAdoptions() {
            return Math.max(0, totals.adoptionCount() - adoptions.size());
        }
    }
}
