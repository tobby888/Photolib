package cn.photolib.adoption;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.PageResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.photo.mapper.PhotoMapper;
import cn.photolib.photo.model.PhotoEntity;
import cn.photolib.photo.model.PhotoStatus;
import cn.photolib.project.ProjectService;
import cn.photolib.project.model.ProjectStatus;
import cn.photolib.user.model.UserRole;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdoptionService {
    private final AdoptionMapper mapper;
    private final PhotoMapper photoMapper;
    private final ProjectService projectService;
    private final JdbcClient jdbc;

    @Transactional
    public List<AdoptionEntity> adopt(Long projectId, List<Long> photoIds, String remark,
                                     AuthenticatedUser user) {
        if (photoIds == null || photoIds.isEmpty() || photoIds.size() > 200) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择 1 至 200 张图片");
        }
        if (projectService.getVisible(projectId, user).getStatus() != ProjectStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "仅进行中项目可采用图片");
        }
        LocalDateTime now = LocalDateTime.now();
        for (Long photoId : photoIds.stream().distinct().toList()) {
            PhotoEntity photo = photoMapper.selectById(photoId);
            if (photo == null || photo.getStatus() != PhotoStatus.AVAILABLE) {
                throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "存在不可采用的图片");
            }
            if (user.role() == UserRole.CAMPUS_MANAGER && !photo.getUploadedBy().equals(user.id())) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "无权使用不可见的图库图片");
            }
            long membership = jdbc.sql("SELECT COUNT(*) FROM photo_project WHERE photo_id=:photoId AND project_id=:projectId")
                    .param("photoId", photoId)
                    .param("projectId", projectId)
                    .query(Long.class)
                    .single();
            if (membership == 0) {
                throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "图片尚未加入项目相册");
            }
            Integer existing = jdbc.sql("SELECT deleted FROM adoption WHERE project_id=:p AND photo_id=:f")
                    .param("p", projectId).param("f", photoId).query(Integer.class).optional().orElse(null);
            if (existing != null) {
                if (existing == 0) throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "图片已被该项目采用");
                jdbc.sql("""
                        UPDATE adoption SET deleted=0, photographer_student_id=:sid,
                        photographer_name=:name, remark=:remark, adopted_by=:user, adopted_at=:at
                        WHERE project_id=:project AND photo_id=:photo
                        """).param("sid", photo.getPhotographerStudentId())
                        .param("name", photo.getPhotographerName()).param("remark", remark)
                        .param("user", user.id()).param("at", now).param("project", projectId)
                        .param("photo", photoId).update();
            } else {
                AdoptionEntity adoption = new AdoptionEntity();
                adoption.setProjectId(projectId);
                adoption.setPhotoId(photoId);
                adoption.setPhotographerStudentId(photo.getPhotographerStudentId());
                adoption.setPhotographerName(photo.getPhotographerName());
                adoption.setRemark(remark);
                adoption.setAdoptedBy(user.id());
                adoption.setAdoptedAt(now);
                adoption.setDeleted(false);
                adoption.setCreatedAt(now);
                mapper.insert(adoption);
            }
        }
        return mapper.selectList(Wrappers.<AdoptionEntity>lambdaQuery()
                .eq(AdoptionEntity::getProjectId, projectId)
                .in(AdoptionEntity::getPhotoId, photoIds));
    }

    public PageResponse<AdoptionEntity> list(Long projectId, int page, int pageSize, String studentId) {
        Page<AdoptionEntity> result = mapper.selectPage(Page.of(page, pageSize),
                Wrappers.<AdoptionEntity>lambdaQuery()
                        .eq(AdoptionEntity::getProjectId, projectId)
                        .eq(studentId != null, AdoptionEntity::getPhotographerStudentId, studentId)
                        .orderByDesc(AdoptionEntity::getAdoptedAt));
        return PageResponse.from(result);
    }

    @Transactional
    public void cancel(Long projectId, Long adoptionId) {
        if (projectService.get(projectId).getStatus() == ProjectStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "已完成项目不能取消采用");
        }
        AdoptionEntity adoption = mapper.selectById(adoptionId);
        if (adoption == null || !adoption.getProjectId().equals(projectId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "采用记录不存在");
        }
        mapper.deleteById(adoptionId);
    }

    public List<Ranking> ranking(LocalDate from, LocalDate to, Long projectId, Long campusId) {
        String sql = """
                SELECT a.photographer_student_id, a.photographer_name, COUNT(*) adopted_count
                FROM adoption a JOIN photo p ON p.id=a.photo_id
                WHERE a.deleted=0
                  AND (:fromDate IS NULL OR a.adopted_at>=:fromDate)
                  AND (:toExclusive IS NULL OR a.adopted_at<:toExclusive)
                  AND (:projectId IS NULL OR a.project_id=:projectId)
                  AND (:campusId IS NULL OR p.campus_id=:campusId)
                GROUP BY a.photographer_student_id, a.photographer_name
                ORDER BY adopted_count DESC, a.photographer_student_id
                """;
        List<Ranking> rows = jdbc.sql(sql).param("fromDate", from)
                .param("toExclusive", to == null ? null : to.plusDays(1))
                .param("projectId", projectId).param("campusId", campusId)
                .query((rs, n) -> new Ranking(0, rs.getString(1), rs.getString(2), rs.getLong(3))).list();
        long previous = -1; int rank = 0;
        for (int i = 0; i < rows.size(); i++) {
            Ranking row = rows.get(i);
            if (row.adoptedCount() != previous) rank = i + 1;
            rows.set(i, new Ranking(rank, row.photographerStudentId(), row.photographerName(), row.adoptedCount()));
            previous = row.adoptedCount();
        }
        return rows;
    }

    public record Ranking(int rank, String photographerStudentId, String photographerName, long adoptedCount) {}
}
