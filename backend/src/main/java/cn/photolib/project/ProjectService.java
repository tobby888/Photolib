package cn.photolib.project;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.PageResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.util.LikeFilter;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.photo.PhotoTags;
import cn.photolib.photo.mapper.PhotoMapper;
import cn.photolib.photo.model.PhotoEntity;
import cn.photolib.photo.model.PhotoStatus;
import cn.photolib.notification.NotificationService;
import cn.photolib.project.mapper.ProjectMapper;
import cn.photolib.project.mapper.ProjectSelectorMapper;
import cn.photolib.project.model.ProjectEntity;
import cn.photolib.project.model.ProjectSelectorEntity;
import cn.photolib.project.model.ProjectStatus;
import cn.photolib.project.model.ProjectType;
import cn.photolib.permission.PermissionCode;
import cn.photolib.user.mapper.UserMapper;
import cn.photolib.user.model.UserEntity;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProjectService {
    private final ProjectMapper mapper;
    private final PhotoMapper photoMapper;
    private final ProjectSelectorMapper selectorMapper;
    private final UserMapper userMapper;
    private final NotificationService notifications;
    private final JdbcClient jdbc;

    @Transactional
    public ProjectEntity create(String title, String description, ProjectStatus status, AuthenticatedUser user) {
        return create(title, description, status, List.of(), ProjectType.CREATION, user);
    }

    @Transactional
    public ProjectEntity create(String title, String description, ProjectStatus status, List<String> tags,
                                AuthenticatedUser user) {
        return create(title, description, status, tags, ProjectType.CREATION, user);
    }

    @Transactional
    public ProjectEntity create(String title, String description, ProjectStatus status, List<String> tags,
                                ProjectType type, AuthenticatedUser user) {
        requirePermission(user, PermissionCode.PROJECT_CREATE);
        if (status != ProjectStatus.DRAFT && status != ProjectStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "新项目状态只能是 DRAFT 或 ACTIVE");
        }
        ProjectEntity project = new ProjectEntity();
        project.setTitle(title);
        project.setDescription(description);
        project.setStatus(status);
        project.setType(type == null ? ProjectType.CREATION : type);
        project.setCreatedBy(user.id());
        project.setTagsJson(PhotoTags.toJson(PhotoTags.normalizeProjectPresets(tags)));
        mapper.insert(project);
        return project;
    }

    public PageResponse<ProjectEntity> list(int page, int pageSize, String keyword, ProjectStatus status,
                                            AuthenticatedUser user) {
        requireViewPermission(user);
        String likeKeyword = LikeFilter.escape(keyword);
        LambdaQueryWrapper<ProjectEntity> query = Wrappers.<ProjectEntity>lambdaQuery()
                .and(StringUtils.hasText(keyword), q -> q
                        .apply(LikeFilter.contains("title"), likeKeyword)
                        .or().apply(LikeFilter.contains("description"), likeKeyword)
                        .or().apply(LikeFilter.contains("tags_json"), likeKeyword))
                .eq(status != null, ProjectEntity::getStatus, status);

        // 没有"无条件查看"权限的账号只看得到自己参与过需求的选题（校区范围账号再叠一层校区过滤）
        if (user.seesOnlyAssignedProjects()) {
            List<Long> visibleProjectIds = assignedProjectIds(user);
            if (visibleProjectIds.isEmpty()) {
                // No visible projects - return empty result
                return PageResponse.from(Page.of(page, pageSize));
            }
            query.in(ProjectEntity::getId, visibleProjectIds);
        }

        query.orderByDesc(ProjectEntity::getCreatedAt);
        Page<ProjectEntity> result = mapper.selectPage(Page.of(page, pageSize), query);
        return PageResponse.from(result);
    }

    public ProjectEntity get(Long id) {
        ProjectEntity project = mapper.selectById(id);
        if (project == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "项目不存在");
        }
        return project;
    }

    public ProjectDetail getDetail(Long id, AuthenticatedUser user) {
        requireViewPermission(user);
        ProjectEntity project = get(id);
        requireVisible(project, user);
        // Campus managers only see a slice of the project (their campus's requests, their own
        // photos). Scope the summary counts to that same slice so the header stats match the
        // request table and photo wall instead of exposing project-wide totals.
        ProjectSummary summary = user.isCampusScoped()
                ? scopedSummary(id, user)
                : projectSummary(id);
        boolean event = project.getType() == ProjectType.EVENT;
        return new ProjectDetail(project.getId(), project.getTitle(), project.getDescription(),
                project.getStatus(), typeOf(project), project.getCreatedBy(), project.getCreatedAt(),
                project.getUpdatedAt(), project.getVersion(), summary.requestCount(), summary.photoCount(),
                summary.adoptionCount(), project.getTags(),
                event ? selectors(id) : List.of(),
                event && canSelect(project, user),
                event && canManageSelection(project, user),
                event ? deprecatedPhotoCount(id) : 0);
    }

    /** 存量行的 {@code type} 可能是 {@code NULL}（迁移之前建的选题），一律按创作选题看待。 */
    private ProjectType typeOf(ProjectEntity project) {
        return project.getType() == null ? ProjectType.CREATION : project.getType();
    }

    private ProjectSummary projectSummary(Long id) {
        return jdbc.sql("""
                SELECT
                    (SELECT COUNT(*) FROM photo_request WHERE project_id=:id AND deleted=0) AS request_count,
                    (SELECT COUNT(*) FROM photo p JOIN photo_project pp ON pp.photo_id=p.id
                        WHERE pp.project_id=:id AND p.deleted=0) AS photo_count,
                    (SELECT COUNT(*) FROM adoption WHERE project_id=:id AND deleted=0) AS adoption_count
                """)
                .param("id", id)
                .query((rs, rowNum) -> new ProjectSummary(
                        rs.getLong("request_count"),
                        rs.getLong("photo_count"),
                        rs.getLong("adoption_count")))
                .single();
    }

    private ProjectSummary scopedSummary(Long id, AuthenticatedUser user) {
        // Mirrors RequestService.list (campus lock, applied only when the manager has a campus)
        // and PhotoService.list (uploader lock) so the counts equal what the manager can list.
        return jdbc.sql("""
                SELECT
                    (SELECT COUNT(*) FROM photo_request r
                        WHERE r.project_id=:id AND r.deleted=0
                          AND r.campus_id IN (:campusIds)
                          AND EXISTS (SELECT 1 FROM request_participant rp
                                      WHERE rp.request_id=r.id AND rp.user_id=:userId)) AS request_count,
                    (SELECT COUNT(*) FROM photo p JOIN photo_project pp ON pp.photo_id=p.id
                        WHERE pp.project_id=:id AND p.deleted=0 AND p.uploaded_by=:userId
                          AND p.campus_id IN (:campusIds)) AS photo_count,
                    (SELECT COUNT(*) FROM adoption a
                        WHERE a.project_id=:id AND a.deleted=0
                          AND EXISTS (SELECT 1 FROM photo p
                                      WHERE p.id=a.photo_id AND p.deleted=0
                                        AND p.uploaded_by=:userId
                                        AND p.campus_id IN (:campusIds))) AS adoption_count
                """)
                .param("id", id)
                .param("userId", user.id())
                .param("campusIds", user.scopedCampusIds())
                .query((rs, rowNum) -> new ProjectSummary(
                        rs.getLong("request_count"),
                        rs.getLong("photo_count"),
                        rs.getLong("adoption_count")))
                .single();
    }

    @Transactional
    public ProjectEntity update(Long id, String title, String description, int version, AuthenticatedUser user) {
        return update(id, title, description, null, version, user);
    }

    /** {@code tags} 为 {@code null} 时保留原预设标签，空列表表示取消限制。 */
    @Transactional
    public ProjectEntity update(Long id, String title, String description, List<String> tags, int version,
                                AuthenticatedUser user) {
        requirePermission(user, PermissionCode.PROJECT_CREATE);
        ProjectEntity project = get(id);
        requireOwnerOrAdmin(project, user);
        if (project.getStatus() == ProjectStatus.COMPLETED || project.getStatus() == ProjectStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "已结束项目不能编辑");
        }
        project.setTitle(title);
        project.setDescription(description);
        if (tags != null) project.setTagsJson(PhotoTags.toJson(PhotoTags.normalizeProjectPresets(tags)));
        project.setVersion(version);
        updateChecked(project);
        return get(id);
    }

    /** 选题的预设标签；选题不存在或没有预设时返回空列表（即不限制）。 */
    public List<String> presetTags(Long projectId) {
        if (projectId == null) return List.of();
        ProjectEntity project = mapper.selectById(projectId);
        return project == null ? List.of() : project.getTags();
    }

    /**
     * 选题有预设标签时，新加到图片上的标签必须全部来自预设。
     * 只校验「新增」的标签：预设改过之后，图片上已有的旧标签可以保留，也可以删除。
     */
    public void requireAllowedPhotoTags(Long projectId, Collection<String> addedTags) {
        if (projectId == null || addedTags == null || addedTags.isEmpty()) return;
        List<String> presets = presetTags(projectId);
        if (presets.isEmpty()) return;
        // 保留标签（deprecated）不受预设限制：它表达的是「这张图用不上」，
        // 与选题想收哪几类图无关，把它写进每个选题的预设里只是重复劳动。
        List<String> rejected = addedTags.stream()
                .filter(tag -> !PhotoTags.isReserved(tag) && !presets.contains(tag)).toList();
        if (!rejected.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "该选题只允许使用预设标签（" + String.join("、", presets) + "），不能添加："
                            + String.join("、", rejected));
        }
    }

    @Transactional
    public ProjectEntity changeStatus(Long id, ProjectStatus target, int version, AuthenticatedUser user) {
        requirePermission(user, target == ProjectStatus.COMPLETED
                ? PermissionCode.PROJECT_COMPLETE : PermissionCode.PROJECT_CREATE);
        ProjectEntity project = get(id);
        requireOwnerOrAdmin(project, user);
        boolean allowed = switch (project.getStatus()) {
            case DRAFT -> target == ProjectStatus.ACTIVE || target == ProjectStatus.CANCELLED;
            case ACTIVE -> target == ProjectStatus.COMPLETED || target == ProjectStatus.CANCELLED;
            default -> false;
        };
        if (!allowed) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "不允许的项目状态流转");
        }
        project.setStatus(target);
        project.setCompletedAt(target == ProjectStatus.COMPLETED ? LocalDateTime.now() : null);
        project.setVersion(version);
        updateChecked(project);
        return get(id);
    }

    @Transactional
    public ProjectEntity reopen(Long id, int version) {
        ProjectEntity project = get(id);
        if (project.getStatus() != ProjectStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "仅已完成项目可重新开放");
        }
        project.setStatus(ProjectStatus.ACTIVE);
        project.setCompletedAt(null);
        project.setVersion(version);
        updateChecked(project);
        return get(id);
    }

    @Transactional
    public void delete(Long id, AuthenticatedUser user) {
        requirePermission(user, PermissionCode.PROJECT_CREATE);
        ProjectEntity project = get(id);
        requireOwnerOrAdmin(project, user);
        long related = jdbc.sql("""
                SELECT (SELECT COUNT(*) FROM photo_request WHERE project_id=:id AND deleted=0)
                     + (SELECT COUNT(*) FROM adoption WHERE project_id=:id AND deleted=0)
                     + (SELECT COUNT(*) FROM photo p JOIN photo_project pp ON pp.photo_id=p.id
                            WHERE pp.project_id=:id AND p.deleted=0)
                """).param("id", id).query(Long.class).single();
        if (related > 0) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "项目已有业务数据，只能取消");
        }
        mapper.deleteById(id);
    }

    // ---------------------------------------------------------------------
    // 活动选题：选片人与选片权限（issue #94）
    // ---------------------------------------------------------------------

    /** 选题的选片人列表，按加入顺序。创作选题恒为空。 */
    public List<Selector> selectors(Long projectId) {
        return jdbc.sql("""
                SELECT u.id, u.display_name, u.username
                FROM project_selector s JOIN app_user u ON u.id = s.user_id
                WHERE s.project_id = :projectId AND u.deleted = 0
                ORDER BY s.id
                """)
                .param("projectId", projectId)
                .query((rs, rowNum) -> new Selector(rs.getLong("id"),
                        rs.getString("display_name"), rs.getString("username")))
                .list();
    }

    /**
     * 可以指派为选片人的账号：所有已启用的账号。
     *
     * <p>刻意不复用 {@code GET /users}（仅管理员）或 {@code /users/message-recipients}
     * （要 {@code MESSAGE_SEND}）：指派选片人的是选题负责人，他多半两样都没有。
     * 这条接口的凭据与「改这个选题」完全一致，返回的字段也只有指人用得上的那几项，
     * 不含手机号、邮箱和校区。</p>
     */
    public List<Selector> selectorCandidates(Long projectId, AuthenticatedUser user) {
        requireManageableEventProject(projectId, user);
        return jdbc.sql("""
                SELECT id, display_name, username FROM app_user
                WHERE deleted = 0 AND enabled = TRUE
                ORDER BY display_name
                """)
                .query((rs, rowNum) -> new Selector(rs.getLong("id"),
                        rs.getString("display_name"), rs.getString("username")))
                .list();
    }

    public boolean isSelector(Long projectId, Long userId) {
        if (projectId == null || userId == null) return false;
        return jdbc.sql("SELECT COUNT(*) FROM project_selector WHERE project_id=:projectId AND user_id=:userId")
                .param("projectId", projectId)
                .param("userId", userId)
                .query(Long.class).single() > 0;
    }

    /**
     * 整组替换选片人。先删后插而不是逐个增量比对：这个集合最多几十个人，
     * 一次替换让「界面上看到的就是库里存的」，省掉一整类「漏删一个」的 bug。
     */
    @Transactional
    public List<Selector> replaceSelectors(Long projectId, List<Long> userIds, AuthenticatedUser user) {
        ProjectEntity project = requireManageableEventProject(projectId, user);
        List<Long> wanted = userIds == null ? List.of()
                : userIds.stream().filter(java.util.Objects::nonNull).distinct().limit(50).toList();
        for (Long userId : wanted) {
            UserEntity candidate = userMapper.selectById(userId);
            if (candidate == null || !Boolean.TRUE.equals(candidate.getEnabled())) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "选片人必须是已启用的账号");
            }
        }
        List<Long> before = selectors(projectId).stream().map(Selector::userId).toList();
        selectorMapper.delete(Wrappers.<ProjectSelectorEntity>lambdaQuery()
                .eq(ProjectSelectorEntity::getProjectId, projectId));
        for (Long userId : wanted) {
            ProjectSelectorEntity row = new ProjectSelectorEntity();
            row.setProjectId(projectId);
            row.setUserId(userId);
            row.setCreatedBy(user.id());
            row.setCreatedAt(LocalDateTime.now());
            selectorMapper.insert(row);
        }
        // 只通知新加进来的人：整组替换时把原有选片人再通知一遍，等于每改一次名单
        // 就给所有人重发一条同样的消息。
        wanted.stream().filter(id -> !before.contains(id)).forEach(id ->
                notifications.notifyUser(id, "PROJECT_SELECTION_ASSIGNED",
                        "你被指定为选题「" + project.getTitle() + "」的选片人",
                        NotificationService.paragraphs(
                                "活动选题「" + project.getTitle() + "」的图片已经可以开始选片。",
                                "请在选题详情页进入选片，为可用的图片打上标签；用不上的图片标记为 deprecated，"
                                        + "选题完成后由负责人确认清理。")));
        return selectors(projectId);
    }

    /** 能不能进这个选题的选片页：活动选题的选片人、选题创建者或管理员。 */
    public boolean canSelect(ProjectEntity project, AuthenticatedUser user) {
        if (typeOf(project) != ProjectType.EVENT) return false;
        return canManageSelection(project, user) || isSelector(project.getId(), user.id());
    }

    /** 能不能改选片人名单、能不能清理 deprecated 图片：选题创建者或管理员。 */
    public boolean canManageSelection(ProjectEntity project, AuthenticatedUser user) {
        return typeOf(project) == ProjectType.EVENT
                && user.hasPermission(PermissionCode.PROJECT_CREATE)
                && (user.isAdministrator() || project.getCreatedBy().equals(user.id()));
    }

    /**
     * 选片能力的唯一判定点。返回选题实体，调用方拿它继续判状态。
     *
     * <p>刻意**不**走 {@link #requireVisible}：选片人可能既没接过需求也没有
     * {@code PROJECT_VIEW_ALL}，那条规则会先把他挡掉。指派本身就是凭据。</p>
     */
    public ProjectEntity requireSelectionAccess(Long projectId, AuthenticatedUser user) {
        requireViewPermission(user);
        ProjectEntity project = get(projectId);
        if (typeOf(project) != ProjectType.EVENT) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "只有活动选题才有选片流程");
        }
        if (!canSelect(project, user)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "你不是这个选题的选片人");
        }
        return project;
    }

    /** 改选片人 / 清理图片共用的前置校验。 */
    public ProjectEntity requireManageableEventProject(Long projectId, AuthenticatedUser user) {
        requirePermission(user, PermissionCode.PROJECT_CREATE);
        ProjectEntity project = get(projectId);
        if (typeOf(project) != ProjectType.EVENT) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "只有活动选题才有选片流程");
        }
        requireOwnerOrAdmin(project, user);
        return project;
    }

    /**
     * 相册里打了 {@code deprecated} 的图片数。
     *
     * <p>用 LIKE 而不是解析 JSON：这里只做给人看的提示数字，真正的清理会逐张
     * 用 {@link PhotoTags#parse} 重新判定（见 {@code ProjectSelectionService}）。
     * 两者可能差一两张（标签里恰好含有 "deprecated" 子串的自定义标签），
     * 差异只会让提示偏大，不会让清理多删一张。</p>
     */
    public long deprecatedPhotoCount(Long projectId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM photo p JOIN photo_project pp ON pp.photo_id = p.id
                WHERE pp.project_id = :projectId AND p.deleted = 0
                  AND p.tags_json LIKE :needle
                """)
                .param("projectId", projectId)
                .param("needle", "%" + PhotoTags.DEPRECATED + "%")
                .query(Long.class).single();
    }

    private void requireOwnerOrAdmin(ProjectEntity project, AuthenticatedUser user) {
        if (!project.getCreatedBy().equals(user.id()) && !user.isAdministrator()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权修改该项目");
        }
    }

    public ProjectEntity getVisible(Long id, AuthenticatedUser user) {
        ProjectEntity project = get(id);
        requireVisible(project, user);
        return project;
    }

    @Transactional
    public void addPhotos(Long projectId, List<Long> photoIds, AuthenticatedUser user) {
        requirePermission(user, PermissionCode.PROJECT_ADOPT);
        if (photoIds == null || photoIds.isEmpty() || photoIds.size() > 200) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择 1 至 200 张图片");
        }
        if (getVisible(projectId, user).getStatus() != ProjectStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "仅进行中项目可添加图片");
        }

        for (Long photoId : photoIds.stream().distinct().toList()) {
            PhotoEntity photo = photoMapper.selectById(photoId);
            if (photo == null || photo.getStatus() != PhotoStatus.AVAILABLE) {
                throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "存在不可添加的图片");
            }
            if (user.isCampusScoped() && !photo.getUploadedBy().equals(user.id())) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "无权使用不可见的图库图片");
            }
            // Adding photos to an album is an idempotent membership operation. The gallery
            // can be stale by the time the user confirms a selection, so an already-linked
            // photo must not roll back other new links in the same request.
            jdbc.sql("INSERT IGNORE INTO photo_project (photo_id, project_id) VALUES (:photoId, :projectId)")
                    .param("photoId", photoId)
                    .param("projectId", projectId)
                    .update();
        }
    }

    /**
     * 选题可见性的唯一判定点。两条限制是**正交**的，不要合并：
     * <ul>
     *   <li>参与人限制来自权限码——没有 {@code PROJECT_VIEW_ALL} 就只看得到自己接过需求的选题；</li>
     *   <li>校区限制来自数据范围——{@code DataScope.CAMPUS} 的账号连"参与过"也只在授权校区内算数。</li>
     * </ul>
     * 全局范围但只勾了 {@code PROJECT_VIEW} 的账号因此不能带上校区条件：
     * {@link AuthenticatedUser#scopedCampusIds()} 对它返回空集合，拼进 {@code IN ()} 会直接是语法错误。
     */
    private void requireVisible(ProjectEntity project, AuthenticatedUser user) {
        if (!user.seesOnlyAssignedProjects()) {
            return;
        }
        // 活动选题的选片人往往一条需求都没接过——指派本身就是"允许看这个选题"的凭据，
        // 否则被指派的人打不开自己要选片的那个选题。
        if (isSelector(project.getId(), user.id())) {
            return;
        }
        boolean campusLocked = user.isCampusScoped();
        var statement = jdbc.sql("""
                SELECT COUNT(*)
                FROM photo_request r
                JOIN request_participant rp ON rp.request_id=r.id
                WHERE r.project_id=:projectId AND r.deleted=0 AND rp.user_id=:userId
                """ + (campusLocked ? "  AND r.campus_id IN (:campusIds)" : ""))
                .param("projectId", project.getId())
                .param("userId", user.id());
        if (campusLocked) {
            statement = statement.param("campusIds", user.scopedCampusIds());
        }
        if (statement.query(Long.class).single() == 0) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看未指派需求所属的项目");
        }
    }

    /** {@link #requireVisible} 的列表版：同一套参与人 + 校区规则，一次取出全部可见选题 id。 */
    private List<Long> assignedProjectIds(AuthenticatedUser user) {
        boolean campusLocked = user.isCampusScoped();
        var statement = jdbc.sql(
                "SELECT DISTINCT r.project_id FROM photo_request r "
                        + "JOIN request_participant rp ON rp.request_id = r.id "
                        + "WHERE r.deleted = 0 AND rp.user_id = :userId"
                        + (campusLocked ? " AND r.campus_id IN (:campusIds)" : ""))
                .param("userId", user.id());
        if (campusLocked) {
            statement = statement.param("campusIds", user.scopedCampusIds());
        }
        // 与 requireVisible 同一套规则的列表版：被指派为选片人的活动选题同样要能列出来，
        // 不然选片人在选题列表里看不到自己该去选片的那一个。
        List<Long> ids = new java.util.ArrayList<>(
                statement.query((rs, rowNum) -> rs.getLong("project_id")).list());
        ids.addAll(jdbc.sql("SELECT project_id FROM project_selector WHERE user_id=:userId")
                .param("userId", user.id())
                .query((rs, rowNum) -> rs.getLong("project_id")).list());
        return ids.stream().distinct().toList();
    }

    private void updateChecked(ProjectEntity project) {
        if (mapper.updateById(project) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "项目已被其他操作修改");
        }
    }

    private void requireViewPermission(AuthenticatedUser user) {
        if (!user.canViewProjects()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权执行该选题操作");
        }
    }

    private void requirePermission(AuthenticatedUser user, PermissionCode permission) {
        if (!user.hasPermission(permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权执行该选题操作");
        }
    }

    private record ProjectSummary(long requestCount, long photoCount, long adoptionCount) {
    }

    /**
     * @param selectors      活动选题的选片人；创作选题恒为空列表
     * @param canSelect      当前账号能不能进这个选题的选片页
     * @param canManageSelection 当前账号能不能改选片人、能不能清理 deprecated 图片
     * @param deprecatedCount 相册里打了 {@code deprecated} 的图片数（含已被引的，前端按需提示）
     */
    public record ProjectDetail(Long id, String title, String description, ProjectStatus status,
                                ProjectType type, Long createdBy, LocalDateTime createdAt,
                                LocalDateTime updatedAt, Integer version, long requestCount,
                                long photoCount, long adoptionCount, List<String> tags,
                                List<Selector> selectors, boolean canSelect, boolean canManageSelection,
                                long deprecatedCount) {
    }

    public record Selector(Long userId, String displayName, String username) {
    }
}
