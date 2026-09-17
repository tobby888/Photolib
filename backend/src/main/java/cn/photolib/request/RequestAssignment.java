package cn.photolib.request;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.notification.NotificationService;
import cn.photolib.permission.PermissionCode;
import cn.photolib.permission.PermissionGroupService;
import cn.photolib.request.mapper.PhotoRequestMapper;
import cn.photolib.request.mapper.RequestParticipantMapper;
import cn.photolib.request.model.PhotoRequestEntity;
import cn.photolib.request.model.RequestParticipantEntity;
import cn.photolib.request.model.RequestStatus;
import cn.photolib.user.mapper.UserMapper;
import cn.photolib.user.model.UserEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 新建需求时的「指派给某个用户」。
 *
 * <p>能不能被指派，与被指派人自己去接单的条件完全一致：持有 {@code REQUEST_VIEW}
 * （「需求访问、接受和提交」）并能访问需求所在校区。判定走 {@link PermissionGroupService#toPrincipal}，
 * 和登录会话用的是同一套解析，权限组、旧角色回退、校区授权都不会各算各的。
 *
 * <p>单独成一个 Bean 是因为 {@link RequestService} 和 {@link BatchRequestPublisher} 都要用，
 * 而前者已经依赖后者。
 */
@Service
@RequiredArgsConstructor
class RequestAssignment {
    private final UserMapper userMapper;
    private final PermissionGroupService permissionGroups;
    private final PhotoRequestMapper mapper;
    private final RequestParticipantMapper participantMapper;
    private final NotificationService notifications;
    private final JdbcClient jdbc;

    /** 被指派人当前是否能接收该校区的需求。停用、已删除的账号一律不行。 */
    boolean canBeAssigned(Long userId, Long campusId) {
        UserEntity user = userId == null ? null : userMapper.selectById(userId);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
            return false;
        }
        AuthenticatedUser principal = permissionGroups.toPrincipal(user);
        return principal.hasPermission(PermissionCode.REQUEST_VIEW) && principal.canAccessCampus(campusId);
    }

    void requireAssignable(Long userId, Long campusId) {
        if (userId != null && !canBeAssigned(userId, campusId)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "被指派人没有「需求访问、接受和提交」权限，或无权访问该校区");
        }
    }

    /** 能同时接收这些校区需求的用户，按显示名排序。 */
    List<RequestService.AssignableUser> candidates(Collection<Long> campusIds) {
        // 先用权限码在库里粗筛，再逐个解析成会话主体判断校区——校区授权的旧数据回退只在那里有。
        return jdbc.sql("""
                        SELECT DISTINCT u.id
                        FROM app_user u
                        JOIN permission_group pg
                          ON pg.id=COALESCE(u.permission_group_id,
                              (SELECT legacy_pg.id FROM permission_group legacy_pg WHERE legacy_pg.code=u.role))
                        JOIN permission_group_permission p ON p.group_id=pg.id AND p.permission_code='REQUEST_VIEW'
                        WHERE u.enabled=TRUE AND u.deleted=FALSE AND pg.deleted=FALSE
                        """).query(Long.class).list().stream()
                .map(userMapper::selectById)
                .filter(user -> user != null)
                .map(permissionGroups::toPrincipal)
                .filter(principal -> principal.hasPermission(PermissionCode.REQUEST_VIEW)
                        && campusIds.stream().allMatch(principal::canAccessCampus))
                .map(principal -> new RequestService.AssignableUser(principal.id(), principal.username(),
                        principal.displayName()))
                .sorted((a, b) -> String.valueOf(a.displayName()).compareTo(String.valueOf(b.displayName())))
                .toList();
    }

    /**
     * 刚发布的需求把被指派人加为参与人，等同于被指派人本人接了单：需求进入 {@code ACCEPTED}，
     * 并通知被指派人。调用方已经在同一事务里校验过资格。
     */
    void applyOnPublish(Long requestId) {
        PhotoRequestEntity request = mapper.selectById(requestId);
        if (request == null || request.getAssigneeId() == null
                || request.getStatus() != RequestStatus.PUBLISHED) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        RequestParticipantEntity participant = new RequestParticipantEntity();
        participant.setRequestId(requestId);
        participant.setUserId(request.getAssigneeId());
        participant.setAcceptedAt(now);
        participant.setCreatedAt(now);
        participantMapper.insert(participant);
        request.setStatus(RequestStatus.ACCEPTED);
        request.setFirstAcceptedAt(now);
        if (mapper.updateById(request) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "需求已被其他操作修改");
        }
        notifications.notifyUser(request.getAssigneeId(), "REQUEST_ASSIGNED",
                "你被指派了新的图片需求", NotificationService.paragraphs(request.getTitle()));
    }
}
