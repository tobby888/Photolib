package cn.photolib.worklog;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.PageResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.util.LikeFilter;
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
import cn.photolib.worklog.model.WorklogSource;
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
        if (worklog.getStatus() == WorklogStatus.SUBMITTED) {
            recordReview(worklog, WorklogStatus.SUBMITTED, null, user.id());
        }
        return worklog;
    }

    /**
     * 不挂需求、只写自定义选题地填报一条工时（V79）。
     *
     * <p>自定义选题是给「系统里没有这个选题」的活动用的，这时也就没有对应的需求，工作人员可以从
     * <b>任意校区</b>通讯录里选（{@link CampusMemberService#getAnyForWorklog}）。</p>
     *
     * <p>只给持有 {@link PermissionCode#WORKLOG_SUBMIT_ANY}（默认只有管理员）、且不限校区的账号：
     * 这类工时和链接申报的一样不属于任何校区，校区范围账号在列表里看不到、凭 id 也碰不到
     * （{@link #requireVisible}），让它们建出来只会建出自己再也找不到的记录。没有需求也就没有
     * 参与人和「选题已结束」的校验。</p>
     */
    @Transactional
    public WorklogEntity createWithoutRequest(WorklogCommand command, AuthenticatedUser user) {
        requireWithoutRequestFiler(user);
        validate(command);
        WorklogEntity worklog = new WorklogEntity();
        worklog.setUserId(user.id());
        apply(worklog, command, campusMemberService.getAnyForWorklog(command.memberContactId()));
        requireCustomProject(worklog);
        worklog.setStatus(command.status() == WorklogStatus.DRAFT
                ? WorklogStatus.DRAFT : WorklogStatus.SUBMITTED);
        mapper.insert(worklog);
        if (worklog.getStatus() == WorklogStatus.SUBMITTED) {
            recordReview(worklog, WorklogStatus.SUBMITTED, null, user.id());
        }
        return worklog;
    }

    /** 没有需求也没有上传链接：只靠自定义选题说明来历的工时（V79）。 */
    static boolean withoutRequest(WorklogEntity worklog) {
        return worklog.getRequestId() == null && worklog.getShareLinkId() == null;
    }

    private void requireWithoutRequestFiler(AuthenticatedUser user) {
        requirePermission(user, PermissionCode.WORKLOG_SUBMIT);
        if (!user.hasPermission(PermissionCode.WORKLOG_SUBMIT_ANY) || user.isCampusScoped()) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "只有可以向任意需求申报工时、且不限校区的账号能不关联需求填报");
        }
    }

    private static void requireCustomProject(WorklogEntity worklog) {
        if (withoutRequest(worklog) && worklog.getCustomProjectTitle() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "不关联需求时必须填写自定义选题");
        }
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
     * <p>和站内填报不同的两处，都是因为申报人没有账号：</p>
     * <ul>
     *   <li>没有草稿——访客没有「回来接着改」的地方，交上来就是提交；</li>
     *   <li>姓名学号是会话上的身份快照，不走通讯录（理由见 Flyway V48）。</li>
     * </ul>
     *
     * <p>同一天可以报多条：一天里跑两场活动、上午拍下午修，都是各报一条的正当情况。
     * 早先「同一条链接 + 同一个学号 + 同一天只能一条」的限制已经去掉，重复提交由审核的人退回。</p>
     */
    @Transactional
    public WorklogEntity createForShareLink(ShareLinkWorklog command) {
        if (cn.photolib.photo.PublicPhoto.isPublic(command.memberName())) {
            // 公共照片不属于任何人，工时没有主人（上传链接里选了公共照片就不能申报）。
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "公共照片不能申报工时");
        }
        validate(command.workDate(), command.shootingMinutes(), command.retouchingMinutes());
        requireProjectOpen(command.projectId());
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
        recordReview(worklog, WorklogStatus.SUBMITTED, null, command.responsibleUserId());
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
        return list(page, pageSize, new ListFilter(requestId, projectId, null, userId, status, from, to,
                null, null), principal);
    }

    /**
     * 工时列表。筛选条件只会收窄结果，可见范围（本人 / 校区）照旧由权限决定。
     *
     * @see ListFilter
     */
    public PageResponse<WorklogEntity> list(int page, int pageSize, ListFilter filter, AuthenticatedUser principal) {
        boolean reviewer = principal.hasAnyPermission(
                PermissionCode.WORKLOG_CONFIRM, PermissionCode.WORKLOG_EXPORT);
        Long effectiveUser = reviewer ? filter.userId() : principal.id();
        Long requestId = filter.requestId();
        Long projectId = filter.projectId();
        Long campusId = filter.campusId();
        String keyword = StringUtils.hasText(filter.keyword()) ? filter.keyword().strip() : null;
        String likeKeyword = LikeFilter.escape(keyword);
        WorklogSource source = filter.source();
        Page<WorklogEntity> result = mapper.selectPage(Page.of(page, pageSize),
                Wrappers.<WorklogEntity>lambdaQuery()
                        .eq(requestId != null, WorklogEntity::getRequestId, requestId)
                        // 关键词查成员姓名、学号、说明和需求标题——审核时最常拿来找人的就是这几样。
                        .and(keyword != null, q -> q
                                .apply(LikeFilter.contains("member_name"), likeKeyword)
                                .or().apply(LikeFilter.contains("member_student_id"), likeKeyword)
                                .or().apply(LikeFilter.contains("remark"), likeKeyword)
                                .or().apply(LikeFilter.contains("custom_project_title"), likeKeyword)
                                .or().apply("request_id IN (SELECT id FROM photo_request WHERE "
                                        + LikeFilter.contains("title") + ")", likeKeyword))
                        // 按需求所属校区筛；链接申报的工时没有校区，选了校区就不会出现。
                        .apply(campusId != null,
                                "request_id IN (SELECT id FROM photo_request WHERE campus_id = {0})", campusId)
                        .isNull(source == WorklogSource.IN_APP, WorklogEntity::getShareLinkId)
                        .isNotNull(source == WorklogSource.SHARE_LINK, WorklogEntity::getShareLinkId)
                        // {0} 由 MyBatis-Plus 绑定成参数，不拼进 SQL 文本。链接是软删的，撤销之后
                        // 申报的工时仍属于这个选题，所以链接那一侧不看 deleted。
                        // 管理员填了自定义选题的工时算在自定义选题下（WorklogProjectSql），不再属于这个选题。
                        .isNull(projectId != null, WorklogEntity::getCustomProjectTitle)
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
                        .eq(filter.status() != null, WorklogEntity::getStatus, filter.status())
                        // 两端都含：to 是那一天本身（work_date 是 DATE，没有时分）。
                        .ge(filter.from() != null, WorklogEntity::getWorkDate, filter.from())
                        .le(filter.to() != null, WorklogEntity::getWorkDate, filter.to())
                        // 同一天的工时很多，再按 id 排一次，翻页时才不会重复或漏行。
                        .orderByDesc(WorklogEntity::getWorkDate)
                        .orderByDesc(WorklogEntity::getId));
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
        if (withoutRequest(worklog)) {
            // 不挂需求的：同样从任意校区通讯录选人，自定义选题不能清掉。
            requireWithoutRequestFiler(user);
            apply(worklog, command, campusMemberService.getAnyForWorklog(command.memberContactId()));
            requireCustomProject(worklog);
        } else {
            PhotoRequestEntity request = requestService.get(worklog.getRequestId());
            requireCampusAccess(request, user);
            requireProjectOpen(request, user);
            CampusMemberEntity member = campusMemberService.getForWorklog(
                    command.memberContactId(), request.getCampusId());
            apply(worklog, command, member);
        }
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
        if (withoutRequest(worklog)) {
            requireWithoutRequestFiler(user);
        } else {
            requireProjectOpen(requestService.get(worklog.getRequestId()), user);
        }
        worklog.setStatus(WorklogStatus.SUBMITTED);
        worklog.setRejectReason(null);
        worklog.setVersion(version);
        updateChecked(worklog);
        recordReview(worklog, WorklogStatus.SUBMITTED, null, user.id());
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
        recordReview(worklog, WorklogStatus.CONFIRMED, null, confirmer.id());
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
        recordReview(worklog, WorklogStatus.REJECTED, reason, confirmer.id());
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
     * 管理员直接修改一条工时：姓名、学号、工作日期、两项时长、说明，以及状态、关联需求和自定义选题都可以改。
     *
     * <p>和填报侧的 {@link #update} 是两回事，刻意不复用：</p>
     * <ul>
     *   <li>只给管理员（权限组 {@code ADMIN}），不看是不是本人填的、选题是否已结束、需求是否在本人参与范围里——
     *       发现报错了的时候选题往往早就结束了，这正是要更正的场景；</li>
     *   <li>状态可以改成四种里的任意一种。改成已确认 / 已退回时审核人记成这位管理员、审核时间记现在，
     *       并在审核记录（{@code worklog_review_log}）里补一行；改回待确认 / 草稿时清掉审核人、审核时间和退回原因。
     *       状态不变时审核信息原样保留，已确认的改完仍是已确认，统计和导出立刻按新值算；</li>
     *   <li>关联需求可以换成任意一条没删除的需求（不限校区、参与人、选题是否结束）。站内填报的必须挂需求；
     *       链接申报的可以挂上一条需求，也可以清掉；</li>
     *   <li>自定义选题（V78）是手写的选题名，填了就按它归属选题，见 {@link WorklogProjectSql}；</li>
     *   <li>姓名学号直接写，不走通讯录。站内填报的工时从通讯录取身份快照，链接申报的是访客自报，
     *       两种报错了都只能在这里改；学号用和链接申报同一套格式校验（全角转半角、去首尾空白）。</li>
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
        validate(command.workDate(), command.shootingMinutes(), command.retouchingMinutes());
        String name = command.memberName() == null ? "" : command.memberName().strip();
        if (name.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "姓名不能为空");
        }
        String studentId = RecruitmentStudentId.normalize(command.memberStudentId()).display();
        String remark = StringUtils.hasText(command.remark()) ? command.remark().strip() : null;
        // 没传的项保持原样：状态为空不改状态，需求为空不改需求（要清掉链接申报工时上的需求用 clearRequest），
        // 自定义选题为 null 不改、空串清掉。
        WorklogStatus status = command.status() == null ? worklog.getStatus() : command.status();
        Long requestId = command.clearRequest() ? null
                : command.requestId() == null ? worklog.getRequestId() : command.requestId();
        // 只在换需求时查：原来挂的需求可能已被删除，不改需求时不能因此改不了别的字段。
        boolean requestChanged = !Objects.equals(requestId, worklog.getRequestId());
        PhotoRequestEntity request = requestChanged && requestId != null ? requestService.get(requestId) : null;
        String customProject = command.customProjectTitle() == null ? worklog.getCustomProjectTitle()
                : StringUtils.hasText(command.customProjectTitle()) ? command.customProjectTitle().strip() : null;
        if (customProject != null && customProject.length() > 200) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "自定义选题最多 200 个字");
        }
        // 一条工时要说得出来历：需求、上传链接、自定义选题至少一样（与 V79 的 chk_worklog_origin 一致）。
        if (requestId == null && worklog.getShareLinkId() == null && customProject == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "站内填报的工时要么关联一条需求，要么填写自定义选题");
        }

        Map<String, Object> changes = new LinkedHashMap<>();
        track(changes, "memberName", worklog.getMemberName(), name);
        track(changes, "memberStudentId", worklog.getMemberStudentId(), studentId);
        track(changes, "workDate", worklog.getWorkDate(), command.workDate());
        track(changes, "shootingMinutes", worklog.getShootingMinutes(), command.shootingMinutes());
        track(changes, "retouchingMinutes", worklog.getRetouchingMinutes(), command.retouchingMinutes());
        track(changes, "remark", worklog.getRemark(), remark);
        track(changes, "status", worklog.getStatus() == null ? null : worklog.getStatus().name(), status.name());
        track(changes, "requestId", worklog.getRequestId(), requestId);
        track(changes, "customProjectTitle", worklog.getCustomProjectTitle(), customProject);

        // 状态变了才动审核信息：改成已确认 / 已退回记这位管理员，改回待确认 / 草稿清空。
        boolean statusChanged = status != worklog.getStatus();
        Long confirmedBy = worklog.getConfirmedBy();
        LocalDateTime confirmedAt = worklog.getConfirmedAt();
        String rejectReason = worklog.getRejectReason();
        if (statusChanged) {
            boolean reviewed = status == WorklogStatus.CONFIRMED || status == WorklogStatus.REJECTED;
            confirmedBy = reviewed ? admin.id() : null;
            confirmedAt = reviewed ? LocalDateTime.now() : null;
            rejectReason = null;
        }
        if (status == WorklogStatus.REJECTED) {
            String reason = StringUtils.hasText(command.rejectReason()) ? command.rejectReason().strip() : null;
            if (statusChanged || reason != null) {
                rejectReason = reason == null ? "管理员修改为已退回" : reason;
            }
            if (rejectReason != null && rejectReason.length() > 500) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "退回原因最多 500 字");
            }
            track(changes, "rejectReason", worklog.getRejectReason(), rejectReason);
        }
        if (changes.isEmpty()) {
            return new Correction(worklog, changes);
        }
        // 用 set 逐列写：实体上的 null 字段 updateById 会跳过，而这里要能把审核人、需求、自定义选题清空。
        int updated = mapper.update(null, Wrappers.<WorklogEntity>lambdaUpdate()
                .eq(WorklogEntity::getId, id)
                .eq(WorklogEntity::getVersion, version)
                .set(WorklogEntity::getMemberName, name)
                .set(WorklogEntity::getMemberStudentId, studentId)
                .set(WorklogEntity::getWorkDate, command.workDate())
                .set(WorklogEntity::getShootingMinutes, command.shootingMinutes())
                .set(WorklogEntity::getRetouchingMinutes, command.retouchingMinutes())
                .set(WorklogEntity::getRemark, remark)
                .set(WorklogEntity::getStatus, status)
                .set(WorklogEntity::getRequestId, requestId)
                .set(WorklogEntity::getCustomProjectTitle, customProject)
                .set(WorklogEntity::getConfirmedBy, confirmedBy)
                .set(WorklogEntity::getConfirmedAt, confirmedAt)
                .set(WorklogEntity::getRejectReason, rejectReason)
                .set(WorklogEntity::getVersion, version + 1)
                .set(WorklogEntity::getUpdatedAt, LocalDateTime.now()));
        if (updated != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "工时已被其他操作修改");
        }
        WorklogEntity saved = mapper.selectById(id);
        if (statusChanged && status != WorklogStatus.DRAFT) {
            recordReview(saved, status, status == WorklogStatus.REJECTED ? rejectReason : null, admin.id());
        }
        if (!worklog.getUserId().equals(admin.id())) {
            notifications.notifyUser(worklog.getUserId(), "WORKLOG_CORRECTED", "工时被管理员修改",
                    NotificationService.paragraphs(
                            "工时记录 #" + worklog.getId() + declaredBy(worklog) + " 已由管理员 "
                                    + admin.displayName() + " 修改：" + describe(changes, worklog, request)));
        }
        return new Correction(saved, changes);
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

    private static final Map<String, String> CORRECTION_LABELS = Map.ofEntries(
            Map.entry("memberName", "姓名"), Map.entry("memberStudentId", "学号"),
            Map.entry("workDate", "工作日期"), Map.entry("shootingMinutes", "拍摄（分钟）"),
            Map.entry("retouchingMinutes", "修图（分钟）"), Map.entry("remark", "说明"),
            Map.entry("status", "状态"), Map.entry("requestId", "关联需求"),
            Map.entry("customProjectTitle", "自定义选题"), Map.entry("rejectReason", "退回原因"));

    private static final Map<String, String> STATUS_LABELS = Map.of(
            "DRAFT", "草稿", "SUBMITTED", "待确认", "CONFIRMED", "已确认", "REJECTED", "已退回");

    private String describe(Map<String, Object> changes, WorklogEntity before, PhotoRequestEntity after) {
        return changes.entrySet().stream().map(entry -> {
            String key = entry.getKey();
            // 说明最长 1000 字，新旧两段拼进来会撑爆通知正文（同样 1000 字）；原文在审计详情里。
            if (key.equals("remark")) return "说明已修改";
            if (key.equals("rejectReason")) return "退回原因已修改";
            Map<?, ?> change = (Map<?, ?>) entry.getValue();
            Object from = change.get("from");
            Object to = change.get("to");
            if (key.equals("status")) {
                from = from == null ? null : STATUS_LABELS.get(from.toString());
                to = STATUS_LABELS.get(to.toString());
            } else if (key.equals("requestId")) {
                // 通知里写需求标题，id 只进审计详情。
                from = requestTitle(before.getRequestId());
                to = after == null ? null : after.getTitle();
            }
            return CORRECTION_LABELS.get(key) + " " + display(abbreviate(from)) + " → " + display(abbreviate(to));
        }).collect(Collectors.joining("；"));
    }

    private String requestTitle(Long requestId) {
        if (requestId == null) return null;
        PhotoRequestEntity request = requestMapper.selectById(requestId);
        return request == null ? "已删除的需求" : request.getTitle();
    }

    /** 标题和自定义选题最长 200 字，通知正文只有 1000 字，拼进来时截短；原文在审计详情里。 */
    private static Object abbreviate(Object value) {
        if (value instanceof String text && text.length() > 40) return text.substring(0, 40) + "…";
        return value;
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
            if (worklog.getShareLinkId() != null) {
                // 前端按 shareLinkId 显示成「上传链接」。管理员可能给它挂上了一条需求，那时下面照样补需求标题。
                ShareLinkOrigin origin = origins.get(worklog.getShareLinkId());
                if (origin != null) {
                    worklog.setProjectId(origin.projectId());
                    worklog.setProjectTitle(origin.projectTitle());
                    worklog.setShareLinkName(origin.linkName());
                }
            }
            // requestTitle 如实留空：这条工时没有需求。
            if (worklog.getRequestId() == null) return;
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
        target.setCustomProjectTitle(customProjectTitle(command.customProjectTitle()));
    }

    /** 自定义选题去首尾空白，空串存 NULL（统计里只判 IS NULL）。 */
    private static String customProjectTitle(String value) {
        if (!StringUtils.hasText(value)) return null;
        String title = value.strip();
        if (title.length() > 200) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "自定义选题最多 200 个字");
        }
        return title;
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
     *
     * <p>只看 {@code share_link_id}：管理员可以给链接申报的工时挂上一条需求（correct），它仍是链接申报的；
     * 而不挂需求、只写自定义选题的站内工时（V79）可以在站内修改和提交。</p>
     */
    private void requireInAppWorklog(WorklogEntity worklog) {
        if (worklog.getShareLinkId() != null) {
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

    /**
     * 工时列表的筛选条件，全部可空（空 = 不按这一项筛）。
     *
     * @param projectId 站内填报的按需求所属选题，链接申报的按链接所属选题，与统计里
     *                  {@code COALESCE(需求的选题, 链接的选题)} 同一口径
     * @param campusId  需求所属校区；链接申报的工时没有校区，选了校区就筛不到它们
     * @param userId    填报账号，只对审核 / 导出权限生效，其他人永远只看自己的
     * @param from      工作日期下限（含）
     * @param to        工作日期上限（含）
     * @param keyword   成员姓名、学号、说明或需求标题包含它
     * @param source    站内填报 / 上传链接申报
     */
    public record ListFilter(Long requestId, Long projectId, Long campusId, Long userId, WorklogStatus status,
                             LocalDate from, LocalDate to, String keyword, WorklogSource source) {
    }

    /**
     * 站内填报 / 修改草稿的内容。{@code customProjectTitle}：填报人手写的自定义选题（可空），
     * 填了这条工时就按它归属选题、按工作日期计入清算期，见 {@link WorklogProjectSql}。
     */
    public record WorklogCommand(LocalDate workDate, Long memberContactId,
                                 int shootingMinutes, int retouchingMinutes,
                                 String remark, WorklogStatus status, String customProjectTitle) {
        public WorklogCommand(LocalDate workDate, Long memberContactId, int shootingMinutes, int retouchingMinutes,
                              String remark, WorklogStatus status) {
            this(workDate, memberContactId, shootingMinutes, retouchingMinutes, remark, status, null);
        }
    }

    /** 管理员更正工时的新值，见 {@link #correct}。 */
    public record CorrectionCommand(String memberName, String memberStudentId, LocalDate workDate,
                                    int shootingMinutes, int retouchingMinutes, String remark,
                                    WorklogStatus status, Long requestId, boolean clearRequest,
                                    String customProjectTitle, String rejectReason) {
        /** 只改身份、日期、时长、说明；状态、需求、自定义选题都不动。 */
        public CorrectionCommand(String memberName, String memberStudentId, LocalDate workDate,
                                 int shootingMinutes, int retouchingMinutes, String remark) {
            this(memberName, memberStudentId, workDate, shootingMinutes, retouchingMinutes, remark,
                    null, null, false, null, null);
        }
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

    /**
     * 工时每一次提交、确认、退回都留一行（Flyway V71）。worklog 表只存当前状态，退回后重交会把
     * {@code reject_reason} 清空；邮件清算单要列出每次申报的时间和每一次退回的原因，靠的是这张表。
     * 和状态更新在同一个事务里，状态改失败（乐观锁冲突）时这一行一起回滚。
     */
    private void recordReview(WorklogEntity worklog, WorklogStatus action, String reason, Long actorId) {
        boolean submitted = action == WorklogStatus.SUBMITTED;
        jdbc.sql("""
                INSERT INTO worklog_review_log(worklog_id, action, reason, actor_id,
                    shooting_minutes, retouching_minutes, backfilled, created_at)
                VALUES (:worklogId, :action, :reason, :actorId, :shooting, :retouching, FALSE, :createdAt)
                """)
                .param("worklogId", worklog.getId())
                .param("action", action.name())
                .param("reason", reason)
                .param("actorId", actorId)
                .param("shooting", submitted ? worklog.getShootingMinutes() : null)
                .param("retouching", submitted ? worklog.getRetouchingMinutes() : null)
                .param("createdAt", LocalDateTime.now())
                .update();
    }
}
