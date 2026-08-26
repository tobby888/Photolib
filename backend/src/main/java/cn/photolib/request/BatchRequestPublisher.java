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
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class BatchRequestPublisher {
    private final PhotoRequestMapper mapper;
    private final ProjectService projectService;
    private final CampusService campusService;
    private final JdbcClient jdbc;
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

        jdbc.sql("""
                SELECT DISTINCT u.id
                FROM app_user u
                JOIN permission_group pg
                  ON pg.id=COALESCE(u.permission_group_id,
                      (SELECT legacy_pg.id FROM permission_group legacy_pg WHERE legacy_pg.code=u.role))
                 AND pg.data_scope='CAMPUS'
                JOIN permission_group_permission p ON p.group_id=pg.id AND p.permission_code='REQUEST_VIEW'
                JOIN user_campus_permission ucp ON ucp.user_id=u.id AND ucp.campus_id=:campusId
                WHERE u.enabled=TRUE AND u.deleted=FALSE AND pg.deleted=FALSE
                """).param("campusId", campusId).query(Long.class).list()
                .forEach(userId -> notifications.notifyUser(userId, "REQUEST_PUBLISHED",
                        "新的图片需求", NotificationService.paragraphs(request.getTitle())));
        return request;
    }
}
