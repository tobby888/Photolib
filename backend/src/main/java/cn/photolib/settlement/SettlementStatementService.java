package cn.photolib.settlement;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.worklog.WorklogProjectSql;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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
 * <p><b>时间口径：按所属选题的结束时间清算</b>，与成员核算（{@code StatisticsService.members}）同一口径。
 * 工时和被引只取所属选题已完成（{@code status='COMPLETED'}）、且 {@code completed_at} 落在清算期内
 * （两端日期都含）的记录；选题没结束的记录不出现在清算单里，等选题结束后计入那一期。工时列待审核、
 * 已确认、已退回（不含草稿），合计以已确认为准。重新开放的选题 {@code completed_at} 被清空，它的记录
 * 会从清算单里退出，再次完成后按新的结束时间计入。</p>
 *
 * <p><b>参与的选题</b>分两组都列出来（{@link ProjectLine#ended()}）：已结束 = 期内结束的选题，拍摄者姓名是他
 * 的图全部算；未结束 = 到清算期末还没结束的选题（进行中，或期末之后才结束），算期末之前上传的图。
 * 取消的选题不列。只算已经传完（处理中、可用、已归档）的图片。图片属于哪些选题见 {@link #projectPhotos}：
 * 除了上传时的来源选题，后来从图库 / 别的选题添加进相册的也算参与。每个选题再按 {@link UploadChannel}
 * 标出这些图是本人传的、通过上传链接传的、别人代传的，还是后来添加进选题的。</p>
 *
 * <p>工时的所属选题和清算期统一走 {@link WorklogProjectSql}：管理员填了自定义选题的工时算在那个名字下，
 * 按工作日期落清算期（清算单上没有结束日期）。</p>
 *
 * <p>{@link #recipients} 和 {@link #statement} 两处 SQL 口径必须一致，改一处就改另一处。</p>
 */
@Service
@RequiredArgsConstructor
public class SettlementStatementService {
    static final int MAX_RANGE_DAYS = 366 * 3;
    /** 单封邮件里最多列多少条，超出的在邮件里注明「另有 N 条」——再多邮件客户端会截断正文。 */
    static final int MAX_LINES = 800;
    /** 传完了的图片：上传中（可能被放弃）和已删除的不算参与。 */
    private static final String UPLOADED_STATUSES = "'PROCESSING','AVAILABLE','ARCHIVED'";
    /** 选题 {@code p} 在清算期内结束：这一期要清算的选题。 */
    private static final String SETTLED_PROJECT = """
            (p.deleted=0 AND p.status='COMPLETED'
             AND p.completed_at >= :fromDate AND p.completed_at < :toExclusive)""";
    /** 选题 {@code p} 到清算期末还没结束（进行中，或期末之后才结束）；取消的不算。 */
    private static final String ONGOING_PROJECT = """
            (p.deleted=0 AND (p.status IN ('DRAFT','ACTIVE')
             OR (p.status='COMPLETED' AND p.completed_at >= :toExclusive)))""";
    /** 传完了、没删的图片（别名 {@code ph}），{@link #projectPhotos} 两路都套这个条件。 */
    public static final String UPLOADED_PHOTO = "ph.deleted=0 AND ph.status IN (%s)".formatted(UPLOADED_STATUSES);
    /**
     * 参与的选题里算哪些图（{@link #projectPhotos} 的结果 {@code x} 联选题 {@code p}）：期内结束的选题全算，
     * 未结束的只算期末之前进入选题的（上传的按上传时间，添加进来的按添加时间）。
     */
    private static final String PARTICIPATION = "(%s OR (%s AND x.entered_at < :toExclusive))"
            .formatted(SETTLED_PROJECT, ONGOING_PROJECT);

    private final JdbcClient jdbc;

    /**
     * 图片进了哪些选题，一行一个「图片 × 选题」，两路合起来：
     * <ul>
     *   <li>来源选题（{@code added=0}）：上传时挂的选题，只挂需求的取需求的选题。进入时间是上传时间。</li>
     *   <li>后来添加进去的选题相册（{@code added=1}）：{@code photo_project} 里来源选题以外的行——从图库添加、
     *       从别的选题复制 / 移动过来的。图库里直接上传、不属于任何选题的图片只会走这一路。
     *       进入时间是添加时间，{@code added_by} 是添加的账号（V73 之前添加的为空）。</li>
     * </ul>
     * 上传时 {@code photo_project} 也会写一行来源选题（AGENTS.md §2.5），按来源算，不重复计。
     * {@code photoCondition} 用别名 {@code ph} 写，两路都套上，按姓名筛选时不用先展开全部图片。
     * 结果列：photo_id, photographer_name, photographer_student_id, uploaded_by, share_link_id, request_id, project_id,
     * added, added_by, entered_at。工资计算的「上传张数」也用它（{@code SalaryCalculationService}），口径保持一致。
     */
    public static String projectPhotos(String photoCondition) {
        return """
                SELECT ph.id AS photo_id, ph.photographer_name, ph.photographer_student_id, ph.uploaded_by,
                       ph.share_link_id, ph.request_id,
                       COALESCE(ph.project_id, r.project_id) AS project_id, 0 AS added, NULL AS added_by,
                       ph.created_at AS entered_at
                FROM photo ph LEFT JOIN photo_request r ON r.id=ph.request_id
                WHERE COALESCE(ph.project_id, r.project_id) IS NOT NULL AND %1$s
                UNION ALL
                SELECT ph.id, ph.photographer_name, ph.photographer_student_id, ph.uploaded_by,
                       ph.share_link_id, ph.request_id, pp.project_id, 1, pp.added_by, pp.created_at
                FROM photo_project pp
                JOIN photo ph ON ph.id=pp.photo_id
                LEFT JOIN photo_request r ON r.id=ph.request_id
                WHERE (COALESCE(ph.project_id, r.project_id) IS NULL
                       OR pp.project_id <> COALESCE(ph.project_id, r.project_id))
                  AND %1$s""".formatted(photoCondition);
    }

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
                    SELECT TRIM(w.member_name) AS name, COUNT(*) AS cnt,
                           SUM(CASE WHEN w.status='CONFIRMED' THEN w.shooting_minutes + w.retouching_minutes ELSE 0 END) AS confirmed_minutes,
                           SUM(CASE WHEN w.status='SUBMITTED' THEN 1 ELSE 0 END) AS pending_count,
                           SUM(CASE WHEN w.status='REJECTED' THEN 1 ELSE 0 END) AS rejected_count
                    FROM worklog w
                    %4$s
                    WHERE w.deleted=0 AND w.status<>'DRAFT' AND w.member_name IS NOT NULL AND %5$s
                    GROUP BY TRIM(w.member_name)
                ),
                ad AS (
                    SELECT TRIM(a.photographer_name) AS name, COUNT(DISTINCT a.photo_id) AS photos, COUNT(*) AS adoptions
                    FROM adoption a JOIN project p ON p.id=a.project_id
                    WHERE a.deleted=0 AND %1$s
                    GROUP BY TRIM(a.photographer_name)
                ),
                up AS (
                    SELECT y.name,
                           SUM(y.ended) AS photos,
                           COUNT(DISTINCT CASE WHEN y.ended=1 THEN y.project_id END) AS projects,
                           SUM(1 - y.ended) AS ongoing_photos,
                           COUNT(DISTINCT CASE WHEN y.ended=0 THEN y.project_id END) AS ongoing_projects
                    FROM (
                        SELECT TRIM(x.photographer_name) AS name, p.id AS project_id,
                               CASE WHEN %1$s THEN 1 ELSE 0 END AS ended
                        FROM (%3$s) x
                        JOIN project p ON p.id=x.project_id
                        WHERE %2$s
                    ) y
                    GROUP BY y.name
                ),
                names AS (
                    SELECT TRIM(display_name) AS name, COUNT(*) AS cnt
                    FROM app_user WHERE deleted=0 AND enabled=TRUE
                    GROUP BY TRIM(display_name)
                )
                SELECT u.id, u.username, TRIM(u.display_name), u.email, c.name, g.name,
                       COALESCE(wl.cnt,0), COALESCE(wl.confirmed_minutes,0), COALESCE(wl.pending_count,0),
                       COALESCE(wl.rejected_count,0), COALESCE(ad.photos,0), COALESCE(ad.adoptions,0),
                       COALESCE(n.cnt,1) - 1, COALESCE(up.photos,0), COALESCE(up.projects,0),
                       COALESCE(up.ongoing_photos,0), COALESCE(up.ongoing_projects,0)
                FROM app_user u
                LEFT JOIN campus c ON c.id=u.campus_id
                LEFT JOIN permission_group g ON g.id=u.permission_group_id
                LEFT JOIN wl ON wl.name=TRIM(u.display_name)
                LEFT JOIN ad ON ad.name=TRIM(u.display_name)
                LEFT JOIN up ON up.name=TRIM(u.display_name)
                LEFT JOIN names n ON n.name=TRIM(u.display_name)
                WHERE u.deleted=0 AND u.enabled=TRUE
                ORDER BY TRIM(u.display_name), u.id
                """.formatted(SETTLED_PROJECT, PARTICIPATION, projectPhotos(UPLOADED_PHOTO),
                        WorklogProjectSql.joins(), WorklogProjectSql.settled()))
                .param("fromDate", from).param("toExclusive", to.plusDays(1))
                .query((rs, n) -> new Recipient(rs.getLong(1), rs.getString(2), rs.getString(3),
                        blankToNull(rs.getString(4)), rs.getString(5), rs.getString(6),
                        rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getInt(10), rs.getInt(11), rs.getInt(12),
                        rs.getInt(13), rs.getInt(14), rs.getInt(15), rs.getInt(16), rs.getInt(17)))
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
                SELECT w.id, w.work_date, %3$s, r.title, c.name, w.share_link_id, l.name,
                       w.shooting_minutes, w.retouching_minutes, w.status, w.remark, w.reject_reason,
                       w.created_at, w.confirmed_at, rv.display_name, w.member_student_id, p.completed_at
                FROM worklog w
                %1$s
                LEFT JOIN campus c ON c.id=r.campus_id
                LEFT JOIN app_user rv ON rv.id=w.confirmed_by
                WHERE w.deleted=0 AND w.status<>'DRAFT' AND TRIM(w.member_name)=:name AND %2$s
                ORDER BY CASE WHEN p.id IS NULL THEN 1 ELSE 0 END, p.completed_at, p.id,
                         w.custom_project_title, w.work_date, w.id
                """.formatted(WorklogProjectSql.joins(), WorklogProjectSql.settled(),
                        WorklogProjectSql.title("w", "p")))
                .param("name", name).param("fromDate", from).param("toExclusive", to.plusDays(1))
                .query((rs, n) -> new WorklogLine(rs.getLong(1), rs.getObject(2, LocalDate.class),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getObject(6, Long.class) != null,
                        rs.getString(7), rs.getInt(8), rs.getInt(9), rs.getString(10), rs.getString(11),
                        rs.getString(12), rs.getObject(13, LocalDateTime.class), rs.getObject(14, LocalDateTime.class),
                        rs.getString(15), rs.getString(16), rs.getObject(17, LocalDateTime.class), new ArrayList<>()))
                .list());
        attachReviewEvents(worklogs);
        List<AdoptionLine> adoptions = jdbc.sql("""
                SELECT a.adopted_at, p.title, a.photo_id, COALESCE(ph.title, ph.stored_file_name), c.name,
                       u.display_name, a.remark, p.completed_at
                FROM adoption a
                JOIN project p ON p.id=a.project_id
                JOIN photo ph ON ph.id=a.photo_id
                LEFT JOIN campus c ON c.id=ph.campus_id
                LEFT JOIN app_user u ON u.id=a.adopted_by
                WHERE a.deleted=0 AND TRIM(a.photographer_name)=:name AND %s
                ORDER BY p.completed_at, p.id, a.adopted_at, a.id
                """.formatted(SETTLED_PROJECT))
                .param("name", name).param("fromDate", from).param("toExclusive", to.plusDays(1))
                .query((rs, n) -> new AdoptionLine(rs.getObject(1, LocalDateTime.class), rs.getString(2),
                        rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getObject(8, LocalDateTime.class)))
                .list();
        List<ProjectLine> projects = projects(recipient, from, to);
        Totals totals = Totals.of(worklogs, adoptions, projects);
        return new Statement(recipient, from, to, projects,
                worklogs.size() > MAX_LINES ? List.copyOf(worklogs.subList(0, MAX_LINES)) : List.copyOf(worklogs),
                adoptions.size() > MAX_LINES ? List.copyOf(adoptions.subList(0, MAX_LINES)) : List.copyOf(adoptions),
                totals);
    }

    /**
     * 参与的选题，每个选题再按上传方式拆开（见 {@link UploadChannel}）。
     * 先按「选题 + 方式 + 上传账号 + 上传链接」分组计数，再在内存里并成每个选题一行——
     * 一个人一期参与的选题是几十个的量级。已结束的排在前面（按结束时间），未结束的按首张上传时间。
     */
    private List<ProjectLine> projects(Recipient recipient, LocalDate from, LocalDate to) {
        record Row(long projectId, String projectTitle, boolean ended, LocalDateTime completedAt,
                   UploadChannel channel, String uploaderName, String shareLinkName, int photoCount,
                   LocalDateTime first, LocalDateTime last) {}
        // actor：上传的账号；添加进相册的图片是添加的账号。上传链接的 uploaded_by 是开链接的人，不展示。
        List<Row> rows = jdbc.sql("""
                SELECT y.project_id, y.project_title, y.ended, y.completed_at, y.channel, y.actor_name,
                       y.link_name, COUNT(*), MIN(y.entered_at), MAX(y.entered_at), y.actor_id
                FROM (
                    SELECT p.id AS project_id, p.title AS project_title, p.completed_at, x.entered_at,
                           CASE WHEN %s THEN 1 ELSE 0 END AS ended,
                           CASE WHEN x.added=1 THEN 'ALBUM_ADDED'
                                WHEN x.share_link_id IS NOT NULL THEN 'SHARE_LINK'
                                WHEN x.uploaded_by = :userId THEN 'SELF'
                                WHEN x.request_id IS NOT NULL THEN 'REQUEST_PROXY'
                                ELSE 'PROJECT_ADDED' END AS channel,
                           u.id AS actor_id, u.display_name AS actor_name,
                           l.id AS link_id, l.name AS link_name
                    FROM (%s) x
                    JOIN project p ON p.id=x.project_id
                    LEFT JOIN app_user u ON u.id=CASE WHEN x.added=1 THEN x.added_by ELSE x.uploaded_by END
                    LEFT JOIN project_share_link l ON l.id=x.share_link_id AND x.added=0
                    WHERE %s
                ) y
                GROUP BY y.project_id, y.project_title, y.ended, y.completed_at, y.channel, y.actor_id,
                         y.actor_name, y.link_id, y.link_name
                """.formatted(SETTLED_PROJECT, projectPhotos(UPLOADED_PHOTO + " AND TRIM(ph.photographer_name)=:name"),
                        PARTICIPATION))
                .param("userId", recipient.userId()).param("name", recipient.displayName())
                .param("fromDate", from).param("toExclusive", to.plusDays(1))
                .query((rs, n) -> {
                    UploadChannel channel = UploadChannel.valueOf(rs.getString(5));
                    String actor = blankToNull(rs.getString(6));
                    // 自己把自己的图添加进选题，写「本人」而不是自己的名字。
                    if (channel == UploadChannel.ALBUM_ADDED && Long.valueOf(recipient.userId())
                            .equals(rs.getObject(11, Long.class))) {
                        actor = "本人";
                    }
                    return new Row(rs.getLong(1), rs.getString(2), rs.getInt(3) == 1,
                            rs.getObject(4, LocalDateTime.class), channel, actor, blankToNull(rs.getString(7)),
                            rs.getInt(8), rs.getObject(9, LocalDateTime.class), rs.getObject(10, LocalDateTime.class));
                })
                .list();
        Map<Long, List<Row>> byProject = new LinkedHashMap<>();
        rows.forEach(row -> byProject.computeIfAbsent(row.projectId(), id -> new ArrayList<>()).add(row));
        List<ProjectLine> result = new ArrayList<>();
        for (List<Row> group : byProject.values()) {
            Row head = group.get(0);
            LocalDateTime first = group.stream().map(Row::first).min(Comparator.naturalOrder()).orElseThrow();
            LocalDateTime last = group.stream().map(Row::last).max(Comparator.naturalOrder()).orElseThrow();
            List<UploadSource> sources = group.stream()
                    .map(row -> new UploadSource(row.channel(),
                            row.channel() == UploadChannel.SELF || row.channel() == UploadChannel.SHARE_LINK
                                    ? null : row.uploaderName(),
                            row.channel() == UploadChannel.SHARE_LINK ? row.shareLinkName() : null,
                            row.photoCount()))
                    .sorted(Comparator.comparing(UploadSource::channel)
                            .thenComparing(UploadSource::photoCount, Comparator.reverseOrder())
                            .thenComparing(source -> source.uploaderName() == null ? "" : source.uploaderName())
                            .thenComparing(source -> source.shareLinkName() == null ? "" : source.shareLinkName()))
                    .toList();
            result.add(new ProjectLine(head.projectId(), head.projectTitle(), head.ended(), head.completedAt(),
                    group.stream().mapToInt(Row::photoCount).sum(), first, last, sources));
        }
        result.sort(Comparator.comparing((ProjectLine line) -> !line.ended())
                .thenComparing(line -> line.ended() ? line.completedAt() : line.firstUploadAt())
                .thenComparing(ProjectLine::projectId));
        return result;
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
     * {@code worklogCount} 含待审核和已退回（不含草稿）。工时、被引、{@code uploadedPhotos} /
     * {@code projectCount} 都只算期内结束的选题；{@code ongoingPhotos} / {@code ongoingProjectCount}
     * 是到期末还没结束的选题。
     */
    public record Recipient(long userId, String username, String displayName, String email, String campus,
                            String groupName, int worklogCount, int confirmedMinutes, int pendingCount,
                            int rejectedCount, int adoptedPhotos, int adoptionCount, int sameNameCount,
                            int uploadedPhotos, int projectCount, int ongoingPhotos, int ongoingProjectCount) {
        public boolean hasRecords() {
            return worklogCount > 0 || adoptionCount > 0 || uploadedPhotos > 0 || ongoingPhotos > 0;
        }
    }

    /**
     * {@code events}：这条工时的每一次提交 / 确认 / 退回，按时间先后。
     * {@code projectCompletedAt}：所属选题的结束时间，清算单按它把这条工时计入这一期。
     */
    public record WorklogLine(long id, LocalDate workDate, String projectTitle, String requestTitle, String campus,
                              boolean viaShareLink, String shareLinkName, int shootingMinutes,
                              int retouchingMinutes, String status, String remark, String rejectReason,
                              LocalDateTime createdAt, LocalDateTime reviewedAt, String reviewerName,
                              String memberStudentId, LocalDateTime projectCompletedAt, List<ReviewEvent> events) {
        public int totalMinutes() {
            return shootingMinutes + retouchingMinutes;
        }
    }

    /** {@code backfilled}：V71 之前的记录，时间是按创建 / 审核时间回填的近似值。 */
    public record ReviewEvent(String action, LocalDateTime at, String actorName, String reason,
                              Integer shootingMinutes, Integer retouchingMinutes, boolean backfilled) {}

    /** {@code projectCompletedAt}：所属选题的结束时间，清算单按它把这次被引计入这一期。 */
    public record AdoptionLine(LocalDateTime adoptedAt, String projectTitle, long photoId, String photoTitle,
                               String campus, String adoptedBy, String remark, LocalDateTime projectCompletedAt) {}

    /**
     * 参与的选题：{@code ended} 为真是期内结束的选题（{@code completedAt} 是结束时间），为假是到期末还没结束的
     * （进行中时 {@code completedAt} 为空，期末之后才结束的有值）。{@code photoCount} 张，首张和最后一张的上传时间；
     * {@code sources} 是这些图分别是怎么进选题的，按 {@link UploadChannel} 的顺序、同一方式里张数多的在前。
     */
    public record ProjectLine(long projectId, String projectTitle, boolean ended, LocalDateTime completedAt,
                              int photoCount, LocalDateTime firstUploadAt, LocalDateTime lastUploadAt,
                              List<UploadSource> sources) {
        public ProjectLine {
            sources = List.copyOf(sources);
        }
    }

    /**
     * 图片是怎么进选题的。全看照片行自己的列，不需要翻审计日志：上传链接记 {@code share_link_id}，
     * 站内上传记 {@code uploaded_by}（上传账号），挂没挂需求看 {@code request_id}。
     * 枚举顺序就是清算单里的展示顺序。
     */
    public enum UploadChannel {
        /** 收件人用自己的账号在站内上传（需求交付或直接传进选题相册）。 */
        SELF,
        /**
         * 通过选题的上传链接上传。拍摄者是上传的人进门时自报的姓名，{@code uploaded_by} 记的是开链接的成员，
         * 不是传图的人，所以这里不写上传账号。
         */
        SHARE_LINK,
        /** 别的账号在需求交付里代传（需求负责人，或者管理员）。 */
        REQUEST_PROXY,
        /** 别的账号不经需求、直接把图传进选题相册（持选题采用权限的管理员、部长、采编）。 */
        PROJECT_ADDED,
        /**
         * 图片原本不属于这个选题（图库里直接上传的，或者来源是别的选题），后来被添加进这个选题相册：
         * 从图库添加、从别的选题复制 / 移动（{@code photo_project.added_by}）。
         */
        ALBUM_ADDED
    }

    /**
     * 一个选题里某一种上传方式的张数。{@code uploaderName}：代传 / 添加图片的账号姓名，
     * 只有 {@code REQUEST_PROXY}、{@code PROJECT_ADDED}、{@code ALBUM_ADDED} 有（{@code ALBUM_ADDED} 是收件人
     * 自己添加的写「本人」，V73 之前添加的为空）；{@code shareLinkName}：上传链接的备注名，
     * 只有 {@code SHARE_LINK} 有，且可能为空。
     */
    public record UploadSource(UploadChannel channel, String uploaderName, String shareLinkName, int photoCount) {}

    /**
     * {@code projectCount} / {@code uploadedPhotos}：期内结束的参与选题及其中的图片；
     * {@code ongoingProjectCount} / {@code ongoingPhotos}：到期末还没结束的。
     */
    public record Totals(int worklogCount, int confirmedCount, int confirmedShootingMinutes,
                         int confirmedRetouchingMinutes, int pendingCount, int pendingMinutes,
                         int rejectedCount, int rejectedMinutes, int adoptedPhotos, int adoptionCount,
                         int projectCount, int uploadedPhotos, int ongoingProjectCount, int ongoingPhotos) {
        public int confirmedMinutes() {
            return confirmedShootingMinutes + confirmedRetouchingMinutes;
        }

        static Totals of(List<WorklogLine> worklogs, List<AdoptionLine> adoptions, List<ProjectLine> projects) {
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
            List<ProjectLine> ended = projects.stream().filter(ProjectLine::ended).toList();
            List<ProjectLine> ongoing = projects.stream().filter(line -> !line.ended()).toList();
            return new Totals(worklogs.size(), confirmed, shooting, retouching, pending, pendingMinutes,
                    rejected, rejectedMinutes, (int) photos, adoptions.size(), ended.size(),
                    ended.stream().mapToInt(ProjectLine::photoCount).sum(), ongoing.size(),
                    ongoing.stream().mapToInt(ProjectLine::photoCount).sum());
        }
    }

    /** 明细超过 {@link #MAX_LINES} 时只列前面这些，{@code totals} 仍按全部记录算。 */
    public record Statement(Recipient recipient, LocalDate from, LocalDate to, List<ProjectLine> projects,
                            List<WorklogLine> worklogs,
                            List<AdoptionLine> adoptions, Totals totals) {
        public int hiddenWorklogs() {
            return Math.max(0, totals.worklogCount() - worklogs.size());
        }

        public int hiddenAdoptions() {
            return Math.max(0, totals.adoptionCount() - adoptions.size());
        }
    }
}
