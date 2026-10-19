package cn.photolib.worklog;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.PageResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.directory.CampusMemberEntity;
import cn.photolib.directory.CampusMemberService;
import cn.photolib.project.ProjectService;
import cn.photolib.project.model.ProjectEntity;
import cn.photolib.project.model.ProjectStatus;
import cn.photolib.request.RequestService;
import cn.photolib.notification.NotificationService;
import cn.photolib.request.mapper.PhotoRequestMapper;
import cn.photolib.request.mapper.RequestParticipantMapper;
import cn.photolib.request.model.PhotoRequestEntity;
import cn.photolib.request.model.RequestParticipantEntity;
import cn.photolib.user.mapper.UserMapper;
import cn.photolib.user.model.UserEntity;
import cn.photolib.permission.PermissionCode;
import cn.photolib.recruitment.RecruitmentStudentId;
import cn.photolib.worklog.mapper.WorklogMapper;
import cn.photolib.worklog.model.WorklogEntity;
import cn.photolib.worklog.model.WorklogStatus;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorklogService {
    private final WorklogMapper mapper;
    private final RequestParticipantMapper participantMapper;
    private final PhotoRequestMapper requestMapper;
    private final UserMapper userMapper;
    private final RequestService requestService;
    private final ProjectService projectService;
    private final CampusMemberService campusMemberService;
    private final NotificationService notifications;
    private final JdbcClient jdbc;

    @Transactional
    public WorklogEntity create(Long requestId, WorklogCommand command, AuthenticatedUser user) {
        requirePermission(user, PermissionCode.WORKLOG_SUBMIT);
        PhotoRequestEntity request = requestService.get(requestId);
        requireCampusAccess(request, user);
        requireProjectOpen(request, user);
        requireParticipant(requestId, user);
        validate(command);
        CampusMemberEntity member = campusMemberService.getForWorklog(
                command.memberContactId(), request.getCampusId());
        WorklogEntity worklog = new WorklogEntity();
        worklog.setRequestId(requestId);
        worklog.setUserId(user.id());
        apply(worklog, command, member);
        worklog.setStatus(command.status() == WorklogStatus.DRAFT
                ? WorklogStatus.DRAFT : WorklogStatus.SUBMITTED);
        mapper.insert(worklog);
        return worklog;
    }

    /**
     * 通过上传链接申报一条工时，直接落成「待确认」。
     *
     * <p><b>这个方法不做授权</b>，与 {@code BatchUploadService} 上那几个中立接口同一个约定：
     * 调用方（{@code ProjectShareUploadService}）已经用「链接 + 密码」换来的会话确认过
     * 「这个人能不能在这条链接上申报」。这里只管工时自己的规则，而且和站内填报是同一份：
     * 日期不能晚于今天、两项时长不能同时为 0，所属选题结束后不再收
     * （{@link #requireProjectOpen(Long)}）。</p>
     *
     * <p>和站内填报不同的三处，都是因为申报人没有账号：</p>
     * <ul>
     *   <li>没有草稿——访客没有「回来接着改」的地方，交上来就是提交；</li>
     *   <li>姓名学号是会话上的身份快照，不走通讯录（理由见 Flyway V48）；</li>
     *   <li>同一条链接上同一个学号同一天只能有一条在审或已确认的工时。站内靠
     *       「草稿 → 提交」这一步让人自己看见重复，这里没有那一步，而访客页一刷新
     *       表单就回到初始状态，不拦的话手一抖就是两条一样的。被退回的不算，
     *       退回之后重新申报正是它该走的路。</li>
     * </ul>
     */
    @Transactional
    public WorklogEntity createForShareLink(ShareLinkWorklog command) {
        validate(command.workDate(), command.shootingMinutes(), command.retouchingMinutes());
        requireProjectOpen(command.projectId());
        long duplicates = mapper.selectCount(Wrappers.<WorklogEntity>lambdaQuery()
                .eq(WorklogEntity::getShareLinkId, command.shareLinkId())
                .eq(WorklogEntity::getMemberStudentId, command.memberStudentId())
                .eq(WorklogEntity::getWorkDate, command.workDate())
                .in(WorklogEntity::getStatus, WorklogStatus.SUBMITTED, WorklogStatus.CONFIRMED));
        if (duplicates > 0) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "这一天的工时已经申报过了。如果填错了，请联系发给你链接的同学退回后重新申报");
        }
        WorklogEntity worklog = new WorklogEntity();
        worklog.setShareLinkId(command.shareLinkId());
        worklog.setUserId(command.responsibleUserId());
        worklog.setMemberName(command.memberName());
        worklog.setMemberStudentId(command.memberStudentId());
        worklog.setWorkDate(command.workDate());
        worklog.setShootingMinutes(command.shootingMinutes());
        worklog.setRetouchingMinutes(command.retouchingMinutes());
        worklog.setRemark(StringUtils.hasText(command.remark()) ? command.remark().trim() : null);
        worklog.setStatus(WorklogStatus.SUBMITTED);
        mapper.insert(worklog);
        return worklog;
    }

    /** 某个学号在某条上传链接上申报过的工时，新的在前。同样不做授权，见 {@link #createForShareLink}。 */
    public List<WorklogEntity> listForShareLink(Long shareLinkId, String memberStudentId) {
        return mapper.selectList(Wrappers.<WorklogEntity>lambdaQuery()
                .eq(WorklogEntity::getShareLinkId, shareLinkId)
                .eq(WorklogEntity::getMemberStudentId, memberStudentId)
                .orderByDesc(WorklogEntity::getWorkDate)
                .orderByDesc(WorklogEntity::getId));
    }

    public PageResponse<WorklogEntity> list(int page, int pageSize, Long requestId, Long userId,
                                            WorklogStatus status, LocalDate from, LocalDate to,
                                            AuthenticatedUser principal) {
        return list(page, pageSize, requestId, null, userId, status, from, to, principal);
    }

    /**
     * @param projectId 只看这个选题的工时：站内填报的按需求所属选题，链接申报的按链接所属选题，
     *                  与统计里 {@code COALESCE(需求的选题, 链接的选题)} 是同一个口径。
     *                  它只是收窄条件，可见范围（本人 / 校区）照旧由下面几条决定。
     */
    public PageResponse<WorklogEntity> list(int page, int pageSize, Long requestId, Long projectId, Long userId,
                                            WorklogStatus status, LocalDate from, LocalDate to,
                                            AuthenticatedUser principal) {
        boolean reviewer = principal.hasAnyPermission(
                PermissionCode.WORKLOG_CONFIRM, PermissionCode.WORKLOG_EXPORT);
        Long effectiveUser = reviewer ? userId : principal.id();
        Page<WorklogEntity> result = mapper.selectPage(Page.of(page, pageSize),
                Wrappers.<WorklogEntity>lambdaQuery()
                        .eq(requestId != null, WorklogEntity::getRequestId, requestId)
                        // {0} 由 MyBatis-Plus 绑定成参数，不拼进 SQL 文本。链接是软删的，撤销之后
                        // 申报的工时仍属于这个选题，所以链接那一侧不看 deleted。
                        .and(projectId != null, q -> q
                                .apply("request_id IN (SELECT id FROM photo_request WHERE project_id = {0})",
                                        projectId)
                                .or().apply("share_link_id IN (SELECT id FROM project_share_link"
                                        + " WHERE project_id = {0})", projectId))
                        // 链接申报的工时没有需求（request_id 为 NULL），于是校区范围账号在这里
                        // 天然筛不到它们——这类工时不属于任何校区，见 requireVisible。
                        .inSql(principal.isCampusScoped(), WorklogEntity::getRequestId,
                                "SELECT id FROM photo_request WHERE deleted=0 AND campus_id IN ("
                                        + campusIdList(principal) + ")")
                        .eq(effectiveUser != null, WorklogEntity::getUserId, effectiveUser)
                        .eq(status != null, WorklogEntity::getStatus, status)
                        .ge(from != null, WorklogEntity::getWorkDate, from)
                        .le(to != null, WorklogEntity::getWorkDate, to)
                        .orderByDesc(WorklogEntity::getWorkDate));
        populateDisplayNames(result.getRecords());
        return PageResponse.from(result);
    }

    @Transactional
    public WorklogEntity update(Long id, WorklogCommand command, int version, AuthenticatedUser user) {
        requirePermission(user, PermissionCode.WORKLOG_SUBMIT);
        WorklogEntity worklog = requireOwned(id, user);
        requireInAppWorklog(worklog);
        if (worklog.getStatus() != WorklogStatus.DRAFT && worklog.getStatus() != WorklogStatus.REJECTED) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "当前工时不可编辑");
        }
        validate(command);
        PhotoRequestEntity request = requestService.get(worklog.getRequestId());
        requireCampusAccess(request, user);
        requireProjectOpen(request, user);
        CampusMemberEntity member = campusMemberService.getForWorklog(
                command.memberContactId(), request.getCampusId());
        apply(worklog, command, member);
        worklog.setStatus(WorklogStatus.DRAFT);
        worklog.setRejectReason(null);
        worklog.setVersion(version);
        updateChecked(worklog);
        return mapper.selectById(id);
    }

    @Transactional
    public WorklogEntity submit(Long id, int version, AuthenticatedUser user) {
        requirePermission(user, PermissionCode.WORKLOG_SUBMIT);
        WorklogEntity worklog = requireOwned(id, user);
        requireInAppWorklog(worklog);
        if (worklog.getStatus() != WorklogStatus.DRAFT && worklog.getStatus() != WorklogStatus.REJECTED) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "当前工时不可提交");
        }
        requireProjectOpen(requestService.get(worklog.getRequestId()), user);
        worklog.setStatus(WorklogStatus.SUBMITTED);
        worklog.setRejectReason(null);
        worklog.setVersion(version);
        updateChecked(worklog);
        return mapper.selectById(id);
    }

    @Transactional
    public WorklogEntity confirm(Long id, int version, AuthenticatedUser confirmer) {
        requirePermission(confirmer, PermissionCode.WORKLOG_CONFIRM);
        WorklogEntity worklog = require(id);
        requireVisible(worklog, confirmer);
        if (worklog.getStatus() != WorklogStatus.SUBMITTED) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "仅已提交工时可确认");
        }
        worklog.setStatus(WorklogStatus.CONFIRMED);
        worklog.setConfirmedBy(confirmer.id());
        worklog.setConfirmedAt(LocalDateTime.now());
        worklog.setVersion(version);
        updateChecked(worklog);
        notifications.notifyUser(worklog.getUserId(), "WORKLOG_CONFIRMED",
                "工时已确认", NotificationService.paragraphs(
                        "工时记录 #" + worklog.getId() + declaredBy(worklog) + " 已确认"));
        return mapper.selectById(id);
    }

    @Transactional
    public WorklogEntity reject(Long id, String reason, int version, AuthenticatedUser confirmer) {
        requirePermission(confirmer, PermissionCode.WORKLOG_CONFIRM);
        WorklogEntity worklog = require(id);
        requireVisible(worklog, confirmer);
        if (worklog.getStatus() != WorklogStatus.SUBMITTED) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "仅已提交工时可退回");
        }
        worklog.setStatus(WorklogStatus.REJECTED);
        worklog.setRejectReason(reason);
        worklog.setConfirmedBy(confirmer.id());
        worklog.setConfirmedAt(LocalDateTime.now());
        worklog.setVersion(version);
        updateChecked(worklog);
        // 链接申报的工时，通知到的是开链接的成员而不是申报人本人（他没有账号）：
        // 所以要写明是谁报的，开链接的人才知道该去转告谁。申报人自己在上传页也看得到退回原因。
        notifications.notifyUser(worklog.getUserId(), "WORKLOG_REJECTED",
                "工时被退回", worklog.getShareLinkId() == null
                        ? NotificationService.paragraphs("退回原因：" + reason)
                        : NotificationService.paragraphs(
                                "工时记录 #" + worklog.getId() + declaredBy(worklog) + " 被退回",
                                "退回原因：" + reason));
        return mapper.selectById(id);
    }

    /**
     * 管理员更正一条已经报上来的工时：姓名、学号、工作日期、两项时长和说明都可以直接改。
     *
     * <p>和填报侧的 {@link #update} 是两回事，刻意不复用：</p>
     * <ul>
     *   <li>只给管理员（权限组 {@code ADMIN}），不看是不是本人填的、选题是否已结束——
     *       发现报错了的时候选题往往早就结束了，这正是要更正的场景；</li>
     *   <li>不改状态：已确认的改完仍是已确认，统计和导出立刻按新值算，不用再走一遍审核；</li>
     *   <li>姓名学号直接写，不走通讯录。站内填报的工时从通讯录取身份快照，链接申报的是访客自报，
     *       两种报错了都只能在这里改；学号用和链接申报同一套格式校验（全角转半角、去首尾空白）。</li>
     *   <li>草稿不能改：它还没交上来，是填报人自己的东西。</li>
     * </ul>
     *
     * <p>返回值里带着改了哪些字段（旧值 → 新值），控制器把它写进审计日志；
     * 填报账号不是管理员本人时还会收到一条通知，免得核对时发现数字变了却不知道是谁改的。</p>
     */
    @Transactional
    public Correction correct(Long id, CorrectionCommand command, int version, AuthenticatedUser admin) {
        if (!admin.isAdministrator()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有管理员可以修改已报上来的工时");
        }
        WorklogEntity worklog = require(id);
        if (worklog.getStatus() == WorklogStatus.DRAFT) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "草稿还没提交，由填报人自己修改");
        }
        validate(command.workDate(), command.shootingMinutes(), command.retouchingMinutes());
        String name = command.memberName() == null ? "" : command.memberName().strip();
        if (name.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "姓名不能为空");
        }
        String studentId = RecruitmentStudentId.normalize(command.memberStudentId()).display();
        String remark = StringUtils.hasText(command.remark()) ? command.remark().strip() : null;

        Map<String, Object> changes = new LinkedHashMap<>();
        track(changes, "memberName", worklog.getMemberName(), name);
        track(changes, "memberStudentId", worklog.getMemberStudentId(), studentId);
        track(changes, "workDate", worklog.getWorkDate(), command.workDate());
        track(changes, "shootingMinutes", worklog.getShootingMinutes(), command.shootingMinutes());
        track(changes, "retouchingMinutes", worklog.getRetouchingMinutes(), command.retouchingMinutes());
        track(changes, "remark", worklog.getRemark(), remark);
        if (changes.isEmpty()) {
            return new Correction(worklog, changes);
        }
        worklog.setMemberName(name);
        worklog.setMemberStudentId(studentId);
        worklog.setWorkDate(command.workDate());
        worklog.setShootingMinutes(command.shootingMinutes());
        worklog.setRetouchingMinutes(command.retouchingMinutes());
        worklog.setRemark(remark);
        worklog.setVersion(version);
        updateChecked(worklog);
        if (!worklog.getUserId().equals(admin.id())) {
            notifications.notifyUser(worklog.getUserId(), "WORKLOG_CORRECTED", "工时被管理员修改",
                    NotificationService.paragraphs(
                            "工时记录 #" + worklog.getId() + declaredBy(worklog) + " 已由管理员 "
                                    + admin.displayName() + " 修改：" + describe(changes)));
        }
        return new Correction(mapper.selectById(id), changes);
    }

    private static void track(Map<String, Object> changes, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            Map<String, Object> change = new LinkedHashMap<>();
            // 日期写成字符串：这份差异要进审计详情的 JSON，也要拼进通知正文。
            change.put("from", before instanceof LocalDate date ? date.toString() : before);
            change.put("to", after instanceof LocalDate date ? date.toString() : after);
            changes.put(field, change);
        }
    }

    private static final Map<String, String> CORRECTION_LABELS = Map.of(
            "memberName", "姓名", "memberStudentId", "学号", "workDate", "工作日期",
            "shootingMinutes", "拍摄（分钟）", "retouchingMinutes", "修图（分钟）", "remark", "说明");

    private static String describe(Map<String, Object> changes) {
        return changes.entrySet().stream().map(entry -> {
            // 说明最长 1000 字，新旧两段拼进来会撑爆通知正文（同样 1000 字）；原文在审计详情里。
            if (entry.getKey().equals("remark")) return "说明已修改";
            Map<?, ?> change = (Map<?, ?>) entry.getValue();
            return CORRECTION_LABELS.get(entry.getKey()) + " " + display(change.get("from"))
                    + " → " + display(change.get("to"));
        }).collect(Collectors.joining("；"));
    }

    private static String display(Object value) {
        return value == null || value.toString().isEmpty() ? "（空）" : value.toString();
    }

    @Transactional
    public void delete(Long id, AuthenticatedUser user) {
        WorklogEntity worklog = require(id);
        requireVisible(worklog, user);
        boolean reviewer = user.hasPermission(PermissionCode.WORKLOG_CONFIRM);
        if (!reviewer && !worklog.getUserId().equals(user.id())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权删除该工时");
        }
        if (!reviewer && worklog.getStatus() != WorklogStatus.DRAFT && worklog.getStatus() != WorklogStatus.REJECTED) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "当前工时不可删除");
        }
        mapper.deleteById(id);
    }

    private void populateDisplayNames(List<WorklogEntity> worklogs) {
        if (worklogs.isEmpty()) return;
        // 链接申报的工时没有需求；空集合交给 selectBatchIds 会拼出一句 IN ()。
        List<Long> requestIds = worklogs.stream().map(WorklogEntity::getRequestId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, PhotoRequestEntity> requests = requestIds.isEmpty() ? Map.of()
                : requestMapper.selectBatchIds(requestIds)
                .stream().collect(Collectors.toMap(PhotoRequestEntity::getId, Function.identity()));
        Map<Long, ShareLinkOrigin> origins = shareLinkOrigins(worklogs);
        Map<Long, UserEntity> users = userMapper.selectBatchIds(
                        worklogs.stream().map(WorklogEntity::getUserId).distinct().toList())
                .stream().collect(Collectors.toMap(UserEntity::getId, Function.identity()));
        worklogs.forEach(worklog -> {
            UserEntity member = users.get(worklog.getUserId());
            worklog.setUserDisplayName(member == null ? "已删除的成员" : member.getDisplayName());
            if (worklog.getRequestId() == null) {
                // requestTitle 如实留空：这条工时没有需求，前端按 shareLinkId 显示成「上传链接」。
                ShareLinkOrigin origin = origins.get(worklog.getShareLinkId());
                if (origin != null) {
                    worklog.setProjectId(origin.projectId());
                    worklog.setProjectTitle(origin.projectTitle());
                    worklog.setShareLinkName(origin.linkName());
                }
                return;
            }
            PhotoRequestEntity request = requests.get(worklog.getRequestId());
            worklog.setRequestTitle(request == null ? "已删除的需求" : request.getTitle());
        });
    }

    /**
     * 链接申报的工时来自哪个选题、哪条链接。
     *
     * <p>用原生 SQL 而不是 mapper：链接是软删的，撤销之后 mapper 就查不到它了，
     * 而审核面板恰恰要在链接撤销之后还说得出「这条工时是哪个选题的」。</p>
     */
    private Map<Long, ShareLinkOrigin> shareLinkOrigins(List<WorklogEntity> worklogs) {
        List<Long> linkIds = worklogs.stream().map(WorklogEntity::getShareLinkId)
                .filter(Objects::nonNull).distinct().toList();
        if (linkIds.isEmpty()) return Map.of();
        Map<Long, ShareLinkOrigin> origins = new HashMap<>();
        jdbc.sql("""
                        SELECT l.id, l.name, p.id, p.title
                        FROM project_share_link l JOIN project p ON p.id = l.project_id
                        WHERE l.id IN (:ids)
                        """).param("ids", linkIds)
                .query((rs, row) -> Map.entry(rs.getLong(1),
                        new ShareLinkOrigin(rs.getString(2), rs.getLong(3), rs.getString(4))))
                .list().forEach(entry -> origins.put(entry.getKey(), entry.getValue()));
        return origins;
    }

    /** 通知正文里的申报人：链接申报的工时通知到的是开链接的成员，得写明是替谁收的。 */
    private String declaredBy(WorklogEntity worklog) {
        return worklog.getShareLinkId() == null ? ""
                : "（" + worklog.getMemberName() + " 通过上传链接申报）";
    }

    private void validate(WorklogCommand command) {
        validate(command.workDate(), command.shootingMinutes(), command.retouchingMinutes());
    }

    private void validate(LocalDate workDate, int shootingMinutes, int retouchingMinutes) {
        if (workDate.isAfter(LocalDate.now())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "工时日期不能晚于今天");
        }
        if (shootingMinutes + retouchingMinutes <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "拍摄和修图工时不能同时为0");
        }
    }

    private void apply(WorklogEntity target, WorklogCommand command, CampusMemberEntity member) {
        target.setWorkDate(command.workDate());
        target.setMemberName(member.getName());
        target.setMemberStudentId(member.getStudentId());
        target.setShootingMinutes(command.shootingMinutes());
        target.setRetouchingMinutes(command.retouchingMinutes());
        target.setRemark(command.remark());
    }

    // 项目一旦完成或取消就不再计发工时，因此填报侧（新建、编辑、提交）都要挡住已结束项目下的
    // 需求；只挡新建的话，草稿仍能在项目结束后被提交上来。审核侧（确认、退回、删除）不受影响，
    // 否则项目结束时还没审完的工时会永远卡住。
    // 持有 WORKLOG_SUBMIT_ANY 的账号（默认只有管理员）用于项目结束后补录工时，不受这条限制；
    // 但校区约束不放开：调用方仍要先过 requireCampusAccess，工作人员也只能取需求所属校区的通讯录。
    private void requireProjectOpen(PhotoRequestEntity request, AuthenticatedUser user) {
        if (user.hasPermission(PermissionCode.WORKLOG_SUBMIT_ANY)) return;
        requireProjectOpen(request.getProjectId());
    }

    /** 「项目结束后不再计发工时」这条规则本身，站内填报和链接申报共用。 */
    private void requireProjectOpen(Long projectId) {
        ProjectEntity project = projectService.get(projectId);
        if (project.getStatus() == ProjectStatus.COMPLETED || project.getStatus() == ProjectStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "所属项目已结束，不能再填报工时");
        }
    }

    /**
     * 链接申报的工时不能在站内编辑或重新提交。
     *
     * <p>这两个入口都要一条需求（查项目状态、按需求校区取通讯录成员），而这类工时没有；
     * 更要紧的是，记在名下的账号是开链接的成员，他不是干活的人，替别人改时长不是他该做的事。
     * 被退回的由申报人在上传页重新申报一条。</p>
     */
    private void requireInAppWorklog(WorklogEntity worklog) {
        if (worklog.getRequestId() == null) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "通过上传链接申报的工时不能在站内修改，请让申报人在上传页重新申报");
        }
    }

    private void requireParticipant(Long requestId, AuthenticatedUser user) {
        if (user.hasPermission(PermissionCode.WORKLOG_SUBMIT_ANY)) return;
        if (participantMapper.selectCount(Wrappers.<RequestParticipantEntity>lambdaQuery()
                .eq(RequestParticipantEntity::getRequestId, requestId)
                .eq(RequestParticipantEntity::getUserId, user.id())) == 0) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅需求参与人可填报工时");
        }
    }

    private WorklogEntity requireOwned(Long id, AuthenticatedUser user) {
        WorklogEntity worklog = require(id);
        requireVisible(worklog, user);
        if (!worklog.getUserId().equals(user.id()) && !user.isAdministrator()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作该工时");
        }
        return worklog;
    }

    private WorklogEntity require(Long id) {
        WorklogEntity worklog = mapper.selectById(id);
        if (worklog == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "工时不存在");
        return worklog;
    }

    private void updateChecked(WorklogEntity worklog) {
        if (mapper.updateById(worklog) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "工时已被其他操作修改");
        }
    }

    private void requireVisible(WorklogEntity worklog, AuthenticatedUser user) {
        if (worklog.getRequestId() == null) {
            // 链接申报的工时不属于任何校区（选题没有校区，访客也报不出）。校区范围账号
            // 只管自己校区的需求，这类工时不在它的范围里——与它不能创建分享链接是同一条边界，
            // 也和上面 list 里按需求校区筛的结果一致，不会出现「列表里没有、却能凭 id 操作」。
            if (user.isCampusScoped()) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该校区的工时");
            }
            return;
        }
        PhotoRequestEntity request = requestMapper.selectById(worklog.getRequestId());
        if (request == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "工时所属需求不存在");
        }
        requireCampusAccess(request, user);
    }

    private void requireCampusAccess(PhotoRequestEntity request, AuthenticatedUser user) {
        if (!user.canAccessCampus(request.getCampusId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该校区的工时");
        }
    }

    private String campusIdList(AuthenticatedUser user) {
        return user.scopedCampusIds().stream().sorted().map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    private void requirePermission(AuthenticatedUser user, PermissionCode permission) {
        if (!user.hasPermission(permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权执行该工时操作");
        }
    }

    public record WorklogCommand(LocalDate workDate, Long memberContactId,
                                 int shootingMinutes, int retouchingMinutes,
                                 String remark, WorklogStatus status) {
    }

    /** 管理员更正工时的新值，见 {@link #correct}。 */
    public record CorrectionCommand(String memberName, String memberStudentId, LocalDate workDate,
                                    int shootingMinutes, int retouchingMinutes, String remark) {
    }

    /** @param changes 改了的字段，{@code 字段名 → {from, to}}；什么都没改时为空，也不写库 */
    public record Correction(WorklogEntity worklog, Map<String, Object> changes) {
    }

    /**
     * 一条通过上传链接申报的工时。
     *
     * @param responsibleUserId 开这条链接的成员，记进 {@code worklog.user_id}——与链接传进来的
     *                          照片记 {@code uploaded_by} 同一个道理，他是站内唯一可追责的人
     * @param memberName        申报人进门时自报的姓名（会话上的身份快照）
     * @param memberStudentId   申报人自报的学号，已规范化；统计按它归并
     */
    public record ShareLinkWorklog(Long shareLinkId, Long projectId, Long responsibleUserId,
                                   String memberName, String memberStudentId, LocalDate workDate,
                                   int shootingMinutes, int retouchingMinutes, String remark) {
    }

    private record ShareLinkOrigin(String linkName, Long projectId, String projectTitle) {
    }
}
