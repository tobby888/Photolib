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
import cn.photolib.project.mapper.ProjectMapper;
import cn.photolib.project.model.ProjectEntity;
import cn.photolib.project.model.ProjectStatus;
import cn.photolib.permission.PermissionCode;
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
    private final JdbcClient jdbc;

    @Transactional
    public ProjectEntity create(String title, String description, ProjectStatus status, AuthenticatedUser user) {
        return create(title, description, status, List.of(), user);
    }

    @Transactional
    public ProjectEntity create(String title, String description, ProjectStatus status, List<String> tags,
                                AuthenticatedUser user) {
        requirePermission(user, PermissionCode.PROJECT_CREATE);
        if (status != ProjectStatus.DRAFT && status != ProjectStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "新项目状态只能是 DRAFT 或 ACTIVE");
        }
        ProjectEntity project = new ProjectEntity();
        project.setTitle(title);
        project.setDescription(description);
        project.setStatus(status);
        project.setCreatedBy(user.id());
        project.setTagsJson(PhotoTags.toJson(PhotoTags.normalize(tags)));
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
        return new ProjectDetail(project.getId(), project.getTitle(), project.getDescription(),
                project.getStatus(), project.getCreatedBy(), project.getCreatedAt(), project.getUpdatedAt(),
                project.getVersion(), summary.requestCount(), summary.photoCount(), summary.adoptionCount(),
                project.getTags());
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
        if (tags != null) project.setTagsJson(PhotoTags.toJson(PhotoTags.normalize(tags)));
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
        List<String> rejected = addedTags.stream().filter(tag -> !presets.contains(tag)).toList();
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
        return statement.query((rs, rowNum) -> rs.getLong("project_id")).list();
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

    public record ProjectDetail(Long id, String title, String description, ProjectStatus status,
                                Long createdBy, LocalDateTime createdAt, LocalDateTime updatedAt,
                                Integer version, long requestCount, long photoCount, long adoptionCount,
                                List<String> tags) {
    }
}
