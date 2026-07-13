package cn.photolib.request;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.campus.CampusService;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.notification.NotificationService;
import cn.photolib.project.ProjectService;
import cn.photolib.project.model.ProjectStatus;
import cn.photolib.request.mapper.PhotoRequestMapper;
import cn.photolib.request.model.PhotoRequestEntity;
import cn.photolib.request.model.RequestStatus;
import cn.photolib.user.mapper.UserMapper;
import cn.photolib.user.model.UserEntity;
import cn.photolib.user.model.UserRole;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class BatchRequestPublisher {
    private final PhotoRequestMapper mapper;
    private final ProjectService projectService;
    private final CampusService campusService;
    private final UserMapper userMapper;
    private final NotificationService notifications;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PhotoRequestEntity publish(Long projectId, Long campusId,
                                      RequestService.BatchPublishCommand command,
                                      AuthenticatedUser user) {
        if (projectService.get(projectId).getStatus() != ProjectStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "项目已不再进行中，当前校区需求未发布");
        }
        if (!Boolean.TRUE.equals(campusService.get(campusId).getEnabled())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "不能向已停用校区发布需求");
        }

        PhotoRequestEntity request = new PhotoRequestEntity();
        request.setProjectId(projectId);
        request.setTitle(command.title());
        request.setDescription(command.description());
        request.setCampusId(campusId);
        request.setRequiredCount(command.requiredCount());
        request.setDeadline(command.deadline());
        request.setStatus(RequestStatus.PUBLISHED);
        request.setCreatedBy(user.id());
        mapper.insert(request);

        userMapper.selectList(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getRole, UserRole.CAMPUS_MANAGER)
                .eq(UserEntity::getCampusId, campusId)
                .eq(UserEntity::getEnabled, true))
                .forEach(member -> notifications.notifyUser(member.getId(), "REQUEST_PUBLISHED",
                        "新的图片需求", "<p>" + request.getTitle() + "</p>"));
        return request;
    }
}
