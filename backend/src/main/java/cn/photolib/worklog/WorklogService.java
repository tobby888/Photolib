package cn.photolib.worklog;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.PageResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.request.RequestService;
import cn.photolib.notification.NotificationService;
import cn.photolib.request.mapper.RequestParticipantMapper;
import cn.photolib.request.model.RequestParticipantEntity;
import cn.photolib.user.model.UserRole;
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

@Service
@RequiredArgsConstructor
public class WorklogService {
    private final WorklogMapper mapper;
    private final RequestParticipantMapper participantMapper;
    private final RequestService requestService;
    private final NotificationService notifications;

    @Transactional
    public WorklogEntity create(Long requestId, WorklogCommand command, AuthenticatedUser user) {
        requestService.get(requestId);
        requireParticipant(requestId, user);
        validate(command);
        WorklogEntity worklog = new WorklogEntity();
        worklog.setRequestId(requestId);
        worklog.setUserId(user.id());
        apply(worklog, command);
        worklog.setStatus(command.status() == WorklogStatus.DRAFT
                ? WorklogStatus.DRAFT : WorklogStatus.SUBMITTED);
        mapper.insert(worklog);
        return worklog;
    }

    public PageResponse<WorklogEntity> list(int page, int pageSize, Long requestId, Long userId,
                                            WorklogStatus status, LocalDate from, LocalDate to,
                                            AuthenticatedUser principal) {
        Long effectiveUser = principal.role() == UserRole.CAMPUS_MANAGER ? principal.id() : userId;
        Page<WorklogEntity> result = mapper.selectPage(Page.of(page, pageSize),
                Wrappers.<WorklogEntity>lambdaQuery()
                        .eq(requestId != null, WorklogEntity::getRequestId, requestId)
                        .eq(effectiveUser != null, WorklogEntity::getUserId, effectiveUser)
                        .eq(status != null, WorklogEntity::getStatus, status)
                        .ge(from != null, WorklogEntity::getWorkDate, from)
                        .le(to != null, WorklogEntity::getWorkDate, to)
                        .orderByDesc(WorklogEntity::getWorkDate));
        return PageResponse.from(result);
    }

    @Transactional
    public WorklogEntity update(Long id, WorklogCommand command, int version, AuthenticatedUser user) {
        WorklogEntity worklog = requireOwned(id, user);
        if (worklog.getStatus() != WorklogStatus.DRAFT && worklog.getStatus() != WorklogStatus.REJECTED) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "当前工时不可编辑");
        }
        validate(command);
        apply(worklog, command);
        worklog.setStatus(WorklogStatus.DRAFT);
        worklog.setRejectReason(null);
        worklog.setVersion(version);
        updateChecked(worklog);
        return mapper.selectById(id);
    }

    @Transactional
    public WorklogEntity submit(Long id, int version, AuthenticatedUser user) {
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
        WorklogEntity worklog = require(id);
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
        WorklogEntity worklog = require(id);
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
        WorklogEntity worklog = requireOwned(id, user);
        if (worklog.getStatus() != WorklogStatus.DRAFT && worklog.getStatus() != WorklogStatus.REJECTED) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "当前工时不可删除");
        }
        mapper.deleteById(id);
    }

    private void validate(WorklogCommand command) {
        if (command.workDate().isAfter(LocalDate.now())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "工时日期不能晚于今天");
        }
        if (command.shootingMinutes() + command.retouchingMinutes() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "拍摄和修图工时不能同时为0");
        }
    }

    private void apply(WorklogEntity target, WorklogCommand command) {
        target.setWorkDate(command.workDate());
        target.setShootingMinutes(command.shootingMinutes());
        target.setRetouchingMinutes(command.retouchingMinutes());
        target.setRemark(command.remark());
    }

    private void requireParticipant(Long requestId, AuthenticatedUser user) {
        if (user.role() == UserRole.ADMIN) return;
        if (participantMapper.selectCount(Wrappers.<RequestParticipantEntity>lambdaQuery()
                .eq(RequestParticipantEntity::getRequestId, requestId)
                .eq(RequestParticipantEntity::getUserId, user.id())) == 0) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅需求参与人可填报工时");
        }
    }

    private WorklogEntity requireOwned(Long id, AuthenticatedUser user) {
        WorklogEntity worklog = require(id);
        if (!worklog.getUserId().equals(user.id()) && user.role() != UserRole.ADMIN) {
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

    public record WorklogCommand(LocalDate workDate, int shootingMinutes, int retouchingMinutes,
                                 String remark, WorklogStatus status) {
    }
}
