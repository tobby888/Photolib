package cn.photolib.worklog;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.PageResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.directory.CampusMemberEntity;
import cn.photolib.directory.CampusMemberService;
import cn.photolib.request.RequestService;
import cn.photolib.notification.NotificationService;
import cn.photolib.request.mapper.PhotoRequestMapper;
import cn.photolib.request.mapper.RequestParticipantMapper;
import cn.photolib.request.model.PhotoRequestEntity;
import cn.photolib.request.model.RequestParticipantEntity;
import cn.photolib.user.mapper.UserMapper;
import cn.photolib.user.model.UserEntity;
import cn.photolib.permission.PermissionCode;
import cn.photolib.worklog.mapper.WorklogMapper;
import cn.photolib.worklog.model.WorklogEntity;
import cn.photolib.worklog.model.WorklogStatus;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
    private final CampusMemberService campusMemberService;
    private final NotificationService notifications;

    @Transactional
    public WorklogEntity create(Long requestId, WorklogCommand command, AuthenticatedUser user) {
        requirePermission(user, PermissionCode.WORKLOG_SUBMIT);
        PhotoRequestEntity request = requestService.get(requestId);
        requireCampusAccess(request, user);
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

    public PageResponse<WorklogEntity> list(int page, int pageSize, Long requestId, Long userId,
                                            WorklogStatus status, LocalDate from, LocalDate to,
                                            AuthenticatedUser principal) {
        boolean reviewer = principal.hasAnyPermission(
                PermissionCode.WORKLOG_CONFIRM, PermissionCode.WORKLOG_EXPORT);
        Long effectiveUser = reviewer ? userId : principal.id();
        Page<WorklogEntity> result = mapper.selectPage(Page.of(page, pageSize),
                Wrappers.<WorklogEntity>lambdaQuery()
                        .eq(requestId != null, WorklogEntity::getRequestId, requestId)
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
        if (worklog.getStatus() != WorklogStatus.DRAFT && worklog.getStatus() != WorklogStatus.REJECTED) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "当前工时不可编辑");
        }
        validate(command);
        PhotoRequestEntity request = requestService.get(worklog.getRequestId());
        requireCampusAccess(request, user);
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
        if (worklog.getStatus() != WorklogStatus.DRAFT && worklog.getStatus() != WorklogStatus.REJECTED) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "当前工时不可提交");
        }
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
                "工时已确认", "<p>工时记录 #" + worklog.getId() + " 已确认</p>");
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
        notifications.notifyUser(worklog.getUserId(), "WORKLOG_REJECTED",
                "工时被退回", "<p>" + reason + "</p>");
        return mapper.selectById(id);
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
        Map<Long, PhotoRequestEntity> requests = requestMapper.selectBatchIds(
                        worklogs.stream().map(WorklogEntity::getRequestId).distinct().toList())
                .stream().collect(Collectors.toMap(PhotoRequestEntity::getId, Function.identity()));
        Map<Long, UserEntity> users = userMapper.selectBatchIds(
                        worklogs.stream().map(WorklogEntity::getUserId).distinct().toList())
                .stream().collect(Collectors.toMap(UserEntity::getId, Function.identity()));
        worklogs.forEach(worklog -> {
            PhotoRequestEntity request = requests.get(worklog.getRequestId());
            UserEntity member = users.get(worklog.getUserId());
            worklog.setRequestTitle(request == null ? "已删除的需求" : request.getTitle());
            worklog.setUserDisplayName(member == null ? "已删除的成员" : member.getDisplayName());
        });
    }

    private void validate(WorklogCommand command) {
        if (command.workDate().isAfter(LocalDate.now())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "工时日期不能晚于今天");
        }
        if (command.shootingMinutes() + command.retouchingMinutes() <= 0) {
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

    private void requireParticipant(Long requestId, AuthenticatedUser user) {
        if (user.isAdministrator()) return;
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
}
