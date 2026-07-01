package cn.photolib.request;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.PageResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.project.ProjectService;
import cn.photolib.notification.NotificationService;
import cn.photolib.project.model.ProjectEntity;
import cn.photolib.project.model.ProjectStatus;
import cn.photolib.request.mapper.PhotoRequestMapper;
import cn.photolib.request.mapper.RequestParticipantMapper;
import cn.photolib.request.model.PhotoRequestEntity;
import cn.photolib.request.model.RequestParticipantEntity;
import cn.photolib.request.model.RequestStatus;
import cn.photolib.user.model.UserRole;
import cn.photolib.user.mapper.UserMapper;
import cn.photolib.user.model.UserEntity;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RequestService {
    private final PhotoRequestMapper mapper;
    private final RequestParticipantMapper participantMapper;
    private final ProjectService projectService;
    private final UserMapper userMapper;
    private final NotificationService notifications;
    private final JdbcClient jdbc;

    @Transactional
    public PhotoRequestEntity create(Long projectId, CreateCommand command, AuthenticatedUser user) {
        ProjectEntity project = projectService.get(projectId);
        if (project.getStatus() == ProjectStatus.COMPLETED || project.getStatus() == ProjectStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "已结束项目不能创建需求");
        }
        if (command.deadline().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "截止时间不能早于当前时间");
        }
        PhotoRequestEntity request = new PhotoRequestEntity();
        request.setProjectId(projectId);
        request.setTitle(command.title());
        request.setDescription(command.description());
        request.setCampusId(command.campusId());
        request.setRequiredCount(command.requiredCount());
        request.setDeadline(command.deadline());
        request.setStatus(RequestStatus.DRAFT);
        request.setCreatedBy(user.id());
        mapper.insert(request);
        return request;
    }

    public PageResponse<PhotoRequestEntity> list(int page, int pageSize, Long projectId,
                                                 RequestStatus status, Long campusId,
                                                 AuthenticatedUser user) {
        Long effectiveCampus = user.role() == UserRole.CAMPUS_MANAGER ? user.campusId() : campusId;
        Page<PhotoRequestEntity> result = mapper.selectPage(Page.of(page, pageSize),
                Wrappers.<PhotoRequestEntity>lambdaQuery()
                        .eq(projectId != null, PhotoRequestEntity::getProjectId, projectId)
                        .eq(status != null, PhotoRequestEntity::getStatus, status)
                        .eq(effectiveCampus != null, PhotoRequestEntity::getCampusId, effectiveCampus)
                        .orderByDesc(PhotoRequestEntity::getCreatedAt));
        return PageResponse.from(result);
    }

    public PhotoRequestEntity get(Long id) {
        PhotoRequestEntity request = mapper.selectById(id);
        if (request == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "图片需求不存在");
        }
        return request;
    }

    @Transactional
    public PhotoRequestEntity publish(Long id, int version, AuthenticatedUser user) {
        PhotoRequestEntity request = get(id);
        requireCreatorOrAdmin(request, user);
        ProjectEntity project = projectService.get(request.getProjectId());
        if (project.getStatus() != ProjectStatus.ACTIVE || request.getStatus() != RequestStatus.DRAFT) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "仅进行中项目的草稿需求可发布");
        }
        request.setStatus(RequestStatus.PUBLISHED);
        request.setVersion(version);
        updateChecked(request);
        userMapper.selectList(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getRole, UserRole.CAMPUS_MANAGER)
                .eq(UserEntity::getCampusId, request.getCampusId())
                .eq(UserEntity::getEnabled, true))
                .forEach(member -> notifications.notifyUser(member.getId(), "REQUEST_PUBLISHED",
                        "新的图片需求", "<p>" + request.getTitle() + "</p>"));
        return get(id);
    }

    @Transactional
    public PhotoRequestEntity update(Long id, CreateCommand command, int version, AuthenticatedUser user) {
        PhotoRequestEntity request = get(id);
        requireCreatorOrAdmin(request, user);
        if (request.getStatus() != RequestStatus.DRAFT) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "仅草稿需求可编辑");
        }
        request.setTitle(command.title());
        request.setDescription(command.description());
        request.setCampusId(command.campusId());
        request.setRequiredCount(command.requiredCount());
        request.setDeadline(command.deadline());
        request.setVersion(version);
        updateChecked(request);
        return get(id);
    }

    @Transactional
    public PhotoRequestEntity accept(Long id, AuthenticatedUser user) {
        PhotoRequestEntity request = get(id);
        if (user.role() != UserRole.ADMIN && (user.role() != UserRole.CAMPUS_MANAGER
                || !request.getCampusId().equals(user.campusId()))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能接受所属校区的需求");
        }
        if (request.getStatus() != RequestStatus.PUBLISHED && request.getStatus() != RequestStatus.ACCEPTED) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "当前需求不可接受");
        }
        if (participantMapper.selectCount(Wrappers.<RequestParticipantEntity>lambdaQuery()
                .eq(RequestParticipantEntity::getRequestId, id)
                .eq(RequestParticipantEntity::getUserId, user.id())) > 0) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "已接受该需求");
        }
        LocalDateTime now = LocalDateTime.now();
        RequestParticipantEntity participant = new RequestParticipantEntity();
        participant.setRequestId(id);
        participant.setUserId(user.id());
        participant.setAcceptedAt(now);
        participant.setCreatedAt(now);
        participantMapper.insert(participant);
        if (request.getStatus() == RequestStatus.PUBLISHED) {
            request.setStatus(RequestStatus.ACCEPTED);
            request.setFirstAcceptedAt(now);
            mapper.updateById(request);
        }
        notifications.notifyUser(request.getCreatedBy(), "REQUEST_ACCEPTED",
                "图片需求已被接受", "<p>" + request.getTitle() + "</p>");
        return get(id);
    }

    public List<RequestParticipantEntity> participants(Long id) {
        get(id);
        return participantMapper.selectList(Wrappers.<RequestParticipantEntity>lambdaQuery()
                .eq(RequestParticipantEntity::getRequestId, id)
                .orderByAsc(RequestParticipantEntity::getAcceptedAt));
    }

    @Transactional
    public void leave(Long id, AuthenticatedUser user) {
        get(id);
        long related = jdbc.sql("""
                SELECT (SELECT COUNT(*) FROM photo WHERE request_id=:r AND uploaded_by=:u AND deleted=0)
                     + (SELECT COUNT(*) FROM worklog WHERE request_id=:r AND user_id=:u AND deleted=0)
                """).param("r", id).param("u", user.id()).query(Long.class).single();
        if (related > 0) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "已有图片或工时记录，不能退出需求");
        }
        int deleted = participantMapper.delete(Wrappers.<RequestParticipantEntity>lambdaQuery()
                .eq(RequestParticipantEntity::getRequestId, id)
                .eq(RequestParticipantEntity::getUserId, user.id()));
        if (deleted == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "尚未接受该需求");
        }
        if (participantMapper.selectCount(Wrappers.<RequestParticipantEntity>lambdaQuery()
                .eq(RequestParticipantEntity::getRequestId, id)) == 0) {
            PhotoRequestEntity request = get(id);
            request.setStatus(RequestStatus.PUBLISHED);
            request.setFirstAcceptedAt(null);
            mapper.updateById(request);
        }
    }

    @Transactional
    public PhotoRequestEntity submit(Long id, int version, AuthenticatedUser user) {
        PhotoRequestEntity request = get(id);
        requireParticipantOrAdmin(id, user);
        if (request.getStatus() != RequestStatus.ACCEPTED) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "仅已接受需求可提交");
        }
        long photos = jdbc.sql("SELECT COUNT(*) FROM photo WHERE request_id=:id AND status='AVAILABLE' AND deleted=0")
                .param("id", id).query(Long.class).single();
        if (photos == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "至少上传一张可用图片后才能提交");
        }
        request.setStatus(RequestStatus.SUBMITTED);
        request.setVersion(version);
        updateChecked(request);
        notifications.notifyUser(request.getCreatedBy(), "REQUEST_SUBMITTED",
                "图片需求已提交", "<p>" + request.getTitle() + "</p>");
        return get(id);
    }

    @Transactional
    public PhotoRequestEntity complete(Long id, int version) {
        PhotoRequestEntity request = get(id);
        if (request.getStatus() != RequestStatus.SUBMITTED) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "仅已提交需求可完成");
        }
        request.setStatus(RequestStatus.COMPLETED);
        request.setCompletedAt(LocalDateTime.now());
        request.setVersion(version);
        updateChecked(request);
        return get(id);
    }

    @Transactional
    public PhotoRequestEntity cancel(Long id, String reason, int version, AuthenticatedUser user) {
        PhotoRequestEntity request = get(id);
        requireCreatorOrAdmin(request, user);
        if (request.getStatus() == RequestStatus.COMPLETED || request.getStatus() == RequestStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "已结束需求不能取消");
        }
        request.setStatus(RequestStatus.CANCELLED);
        request.setCancelReason(reason);
        request.setVersion(version);
        updateChecked(request);
        return get(id);
    }

    private void requireParticipantOrAdmin(Long requestId, AuthenticatedUser user) {
        if (user.role() == UserRole.ADMIN) {
            return;
        }
        if (participantMapper.selectCount(Wrappers.<RequestParticipantEntity>lambdaQuery()
                .eq(RequestParticipantEntity::getRequestId, requestId)
                .eq(RequestParticipantEntity::getUserId, user.id())) == 0) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅需求参与人可执行该操作");
        }
    }

    private void requireCreatorOrAdmin(PhotoRequestEntity request, AuthenticatedUser user) {
        if (!request.getCreatedBy().equals(user.id()) && user.role() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作该需求");
        }
    }

    private void updateChecked(PhotoRequestEntity request) {
        if (mapper.updateById(request) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "需求已被其他操作修改");
        }
    }

    public record CreateCommand(String title, String description, Long campusId,
                                int requiredCount, LocalDateTime deadline) {
    }
}
