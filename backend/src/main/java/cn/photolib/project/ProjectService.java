package cn.photolib.project;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.PageResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.project.mapper.ProjectMapper;
import cn.photolib.project.model.ProjectEntity;
import cn.photolib.project.model.ProjectStatus;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ProjectService {
    private final ProjectMapper mapper;
    private final JdbcClient jdbc;

    @Transactional
    public ProjectEntity create(String title, String description, ProjectStatus status, AuthenticatedUser user) {
        if (status != ProjectStatus.DRAFT && status != ProjectStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "新项目状态只能是 DRAFT 或 ACTIVE");
        }
        ProjectEntity project = new ProjectEntity();
        project.setTitle(title);
        project.setDescription(description);
        project.setStatus(status);
        project.setCreatedBy(user.id());
        mapper.insert(project);
        return project;
    }

    public PageResponse<ProjectEntity> list(int page, int pageSize, String keyword, ProjectStatus status) {
        Page<ProjectEntity> result = mapper.selectPage(Page.of(page, pageSize),
                Wrappers.<ProjectEntity>lambdaQuery()
                        .and(StringUtils.hasText(keyword), q -> q.like(ProjectEntity::getTitle, keyword)
                                .or().like(ProjectEntity::getDescription, keyword))
                        .eq(status != null, ProjectEntity::getStatus, status)
                        .orderByDesc(ProjectEntity::getCreatedAt));
        return PageResponse.from(result);
    }

    public ProjectEntity get(Long id) {
        ProjectEntity project = mapper.selectById(id);
        if (project == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "项目不存在");
        }
        return project;
    }

    public ProjectDetail getDetail(Long id) {
        ProjectEntity project = get(id);
        ProjectSummary summary = jdbc.sql("""
                SELECT
                    (SELECT COUNT(*) FROM photo_request WHERE project_id=:id AND deleted=0) AS request_count,
                    (SELECT COUNT(*) FROM photo WHERE project_id=:id AND deleted=0) AS photo_count,
                    (SELECT COUNT(*) FROM adoption WHERE project_id=:id AND deleted=0) AS adoption_count
                """)
                .param("id", id)
                .query((rs, rowNum) -> new ProjectSummary(
                        rs.getLong("request_count"),
                        rs.getLong("photo_count"),
                        rs.getLong("adoption_count")))
                .single();
        return new ProjectDetail(project.getId(), project.getTitle(), project.getDescription(),
                project.getStatus(), project.getCreatedBy(), project.getCreatedAt(), project.getUpdatedAt(),
                project.getVersion(), summary.requestCount(), summary.photoCount(), summary.adoptionCount());
    }

    @Transactional
    public ProjectEntity update(Long id, String title, String description, int version, AuthenticatedUser user) {
        ProjectEntity project = get(id);
        requireOwnerOrAdmin(project, user);
        if (project.getStatus() == ProjectStatus.COMPLETED || project.getStatus() == ProjectStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "已结束项目不能编辑");
        }
        project.setTitle(title);
        project.setDescription(description);
        project.setVersion(version);
        updateChecked(project);
        return get(id);
    }

    @Transactional
    public ProjectEntity changeStatus(Long id, ProjectStatus target, int version, AuthenticatedUser user) {
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
        project.setVersion(version);
        updateChecked(project);
        return get(id);
    }

    @Transactional
    public void delete(Long id, AuthenticatedUser user) {
        ProjectEntity project = get(id);
        requireOwnerOrAdmin(project, user);
        long related = jdbc.sql("""
                SELECT (SELECT COUNT(*) FROM photo_request WHERE project_id=:id AND deleted=0)
                     + (SELECT COUNT(*) FROM adoption WHERE project_id=:id AND deleted=0)
                     + (SELECT COUNT(*) FROM photo WHERE project_id=:id AND deleted=0)
                """).param("id", id).query(Long.class).single();
        if (related > 0) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "项目已有业务数据，只能取消");
        }
        mapper.deleteById(id);
    }

    private void requireOwnerOrAdmin(ProjectEntity project, AuthenticatedUser user) {
        if (!project.getCreatedBy().equals(user.id()) && user.role() != cn.photolib.user.model.UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权修改该项目");
        }
    }

    private void updateChecked(ProjectEntity project) {
        if (mapper.updateById(project) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "项目已被其他操作修改");
        }
    }

    private record ProjectSummary(long requestCount, long photoCount, long adoptionCount) {
    }

    public record ProjectDetail(Long id, String title, String description, ProjectStatus status,
                                Long createdBy, LocalDateTime createdAt, LocalDateTime updatedAt,
                                Integer version, long requestCount, long photoCount, long adoptionCount) {
    }
}
