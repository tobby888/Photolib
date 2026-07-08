package cn.photolib.photo;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.campus.CampusService;
import cn.photolib.common.api.PageResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.photo.mapper.PhotoMapper;
import cn.photolib.photo.model.PhotoEntity;
import cn.photolib.photo.model.PhotoStatus;
import cn.photolib.request.RequestService;
import cn.photolib.request.mapper.RequestParticipantMapper;
import cn.photolib.request.model.PhotoRequestEntity;
import cn.photolib.request.model.RequestParticipantEntity;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.storage.StorageProperties;
import cn.photolib.user.model.UserRole;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PhotoService {
    private static final Logger log = LoggerFactory.getLogger(PhotoService.class);
    private final PhotoMapper mapper;
    private final RequestService requestService;
    private final RequestParticipantMapper participantMapper;
    private final ObjectStorageService storage;
    private final StorageProperties properties;
    private final ApplicationEventPublisher events;
    private final JdbcClient jdbc;
    private final CampusService campusService;
    private final cn.photolib.directory.CampusMemberService campusMemberService;

    @Transactional
    public UploadTicket createTicket(CreateTicket command, AuthenticatedUser user) {
        validateFile(command.fileName(), command.contentType(), command.size());

        // 检查 SHA256 哈希是否重复
        String sha256Lower = command.sha256().toLowerCase();
        PhotoEntity existing = mapper.selectOne(Wrappers.<PhotoEntity>lambdaQuery()
                .eq(PhotoEntity::getSha256, sha256Lower)
                .eq(PhotoEntity::getDeleted, false)
                .last("LIMIT 1"));
        if (existing != null) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE,
                    "已经上传过该图片（标题：" + existing.getTitle() + "）");
        }

        Long campusId = user.campusId();
        Long projectId = command.projectId();
        if (command.requestId() != null) {
            PhotoRequestEntity request = requestService.get(command.requestId());
            campusId = request.getCampusId();
            projectId = request.getProjectId();
            if (user.role() == UserRole.CAMPUS_MANAGER && !isParticipant(command.requestId(), user.id())) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "仅需求参与人可上传图片");
            }
        }
        // 强制拍摄者来自通讯录：解析放在文件校验、参与人校区校验之后，快照姓名/学号写入照片。
        var photographer = campusMemberService.resolvePhotographer(command.photographerContactId(), campusId);
        String extension = command.contentType().equals("image/png") ? "png" : "jpg";
        String id = UUID.randomUUID().toString();
        String originalKey = "temporary/photos/" + id + "." + extension;
        String finalKey = "photos/" + LocalDateTime.now().getYear() + "/" + id + "." + extension;
        PhotoEntity photo = new PhotoEntity();
        photo.setRequestId(command.requestId());
        photo.setProjectId(projectId);
        photo.setPhotographerStudentId(photographer.getStudentId());
        photo.setPhotographerName(photographer.getName());
        photo.setUploadedBy(user.id());
        photo.setCampusId(campusId);
        photo.setTakenAt(command.takenAt());
        photo.setSize(command.size());
        photo.setContentType(command.contentType());
        photo.setObjectKey(finalKey);
        photo.setOriginalObjectKey(originalKey);
        photo.setSha256(sha256Lower);
        photo.setStatus(PhotoStatus.UPLOADING);
        mapper.insert(photo);
        // 归属链接：项目相册/计数以 photo_project 为准。新照片 id 全新，(photo_id,project_id) 不会撞主键。
        if (projectId != null) {
            jdbc.sql("INSERT INTO photo_project (photo_id, project_id) VALUES (:photoId, :projectId)")
                    .param("photoId", photo.getId())
                    .param("projectId", projectId)
                    .update();
        }
        ObjectStorageService.SignedUrl signed = storage.presignPut(
                originalKey, command.contentType(), properties.uploadUrlTtl());
        return new UploadTicket(photo.getId(), signed.url().toString(), signed.method(),
                command.contentType(), signed.expiresAt());
    }

    @Transactional
    public PhotoView complete(Long id, CompleteUpload command, AuthenticatedUser user) {
        PhotoEntity photo = require(id);
        requireUploaderOrAdmin(photo, user);
        if (photo.getStatus() != PhotoStatus.UPLOADING) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "图片不处于待上传状态");
        }
        ObjectStorageService.ObjectInfo info = storage.stat(photo.getOriginalObjectKey());
        if (info.size() <= 0 || info.size() > properties.imageMaxBytes()) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "图片为空或超过 100 MiB");
        }
        photo.setTitle(command.title());
        photo.setDescription(command.description());
        photo.setTagsJson(tagsJson(command.tags()));
        photo.setStatus(PhotoStatus.PROCESSING);
        photo.setFailureReason(null);

        // Guard against concurrent completion: only the request that still sees
        // UPLOADING wins. Optimistic version bump/check is applied automatically by
        // the @Version OptimisticLockerInnerInterceptor (BaseEntity.version) — do NOT
        // touch version by hand here. Manually setting version and adding an explicit
        // version predicate double-applies the lock: the interceptor injects
        // `WHERE version = <manual value>` which never matches the stored row, so the
        // update silently affects 0 rows and every complete-upload 409s (H-3 regression).
        int updated = mapper.update(photo, Wrappers.<PhotoEntity>lambdaUpdate()
            .eq(PhotoEntity::getId, id)
            .eq(PhotoEntity::getStatus, PhotoStatus.UPLOADING));
        if (updated != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "图片状态已变更，请刷新后重试");
        }

        events.publishEvent(new PhotoProcessingService.PhotoProcessRequested(photo.getId()));
        return toView(photo);
    }

    public PageResponse<PhotoView> list(int page, int pageSize, String keyword, Long projectId,
                                        Long requestId, String studentId, String photographerName,
                                        Long uploadedBy, Long campusId, PhotoStatus status,
                                        boolean includeAllStatuses,
                                        AuthenticatedUser user) {
        Long effectiveUploader = user.role() == UserRole.CAMPUS_MANAGER ? user.id() : uploadedBy;
        // status explicitly given -> filter by it; otherwise default to AVAILABLE unless the
        // caller opts into every status (used by the project detail gallery to match photoCount).
        PhotoStatus effectiveStatus = status != null ? status
                : includeAllStatuses ? null : PhotoStatus.AVAILABLE;
        var query = Wrappers.<PhotoEntity>lambdaQuery()
                .and(StringUtils.hasText(keyword), q -> q.like(PhotoEntity::getTitle, keyword)
                        .or().like(PhotoEntity::getDescription, keyword)
                        .or().like(PhotoEntity::getTagsJson, keyword))
                .inSql(projectId != null, PhotoEntity::getId,
                        "SELECT photo_id FROM photo_project WHERE project_id = " + projectId)
                .eq(requestId != null, PhotoEntity::getRequestId, requestId)
                .eq(StringUtils.hasText(studentId), PhotoEntity::getPhotographerStudentId, studentId)
                .like(StringUtils.hasText(photographerName), PhotoEntity::getPhotographerName, photographerName)
                .eq(effectiveUploader != null, PhotoEntity::getUploadedBy, effectiveUploader)
                .eq(campusId != null, PhotoEntity::getCampusId, campusId)
                .eq(effectiveStatus != null, PhotoEntity::getStatus, effectiveStatus)
                .orderByDesc(PhotoEntity::getCreatedAt);
        Page<PhotoEntity> result = mapper.selectPage(Page.of(page, pageSize), query);
        return new PageResponse<>(result.getRecords().stream().map(this::toView).toList(),
                result.getCurrent(), result.getSize(), result.getTotal(), result.getPages());
    }

    public PhotoView get(Long id, AuthenticatedUser user) {
        PhotoEntity photo = require(id);
        if (user.role() == UserRole.CAMPUS_MANAGER && !photo.getUploadedBy().equals(user.id())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看该图片");
        }
        return toView(photo);
    }

    @Transactional
    public PhotoView update(Long id, Metadata command, AuthenticatedUser user) {
        PhotoEntity photo = require(id);
        requireUploaderOrPrivileged(photo, user);
        var photographer = campusMemberService.resolvePhotographer(
                command.photographerContactId(), photo.getCampusId());
        photo.setTitle(command.title());
        photo.setDescription(command.description());
        photo.setPhotographerStudentId(photographer.getStudentId());
        photo.setPhotographerName(photographer.getName());
        photo.setTakenAt(command.takenAt());
        photo.setTagsJson(tagsJson(command.tags()));
        photo.setVersion(command.version());
        if (mapper.updateById(photo) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "图片已被其他操作修改");
        }
        return toView(require(id));
    }

    @Transactional
    public PhotoView updateCampus(Long id, Long campusId, int version, AuthenticatedUser user) {
        if (user.role() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅管理员可修改图片校区");
        }
        PhotoEntity photo = require(id);
        if (campusId != null) {
            campusService.get(campusId);
        }
        int updated = jdbc.sql("""
                UPDATE photo
                SET campus_id=:campusId, version=version+1, updated_at=CURRENT_TIMESTAMP
                WHERE id=:id AND version=:version AND deleted=0
                """)
                .param("campusId", campusId)
                .param("id", id)
                .param("version", version)
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "图片已被其他操作修改");
        }
        photo.setCampusId(campusId);
        photo.setVersion(version + 1);
        return toView(photo);
    }

    public DownloadUrl download(Long id, AuthenticatedUser user) {
        PhotoEntity photo = require(id);
        if (photo.getStatus() != PhotoStatus.AVAILABLE && photo.getStatus() != PhotoStatus.ARCHIVED) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "图片暂不可下载");
        }
        requireVisible(photo, user);
        requireUploaderOrPrivileged(photo, user);
        ObjectStorageService.SignedUrl signed = storage.presignGet(
                photo.getObjectKey(), photo.getStoredFileName(), properties.downloadUrlTtl());
        return new DownloadUrl(signed.url().toString(), signed.expiresAt(), photo.getStoredFileName());
    }

    @Transactional
    public PhotoView changeArchive(Long id, boolean archive, AuthenticatedUser user) {
        PhotoEntity photo = require(id);
        requirePrivileged(user);
        PhotoStatus expected = archive ? PhotoStatus.AVAILABLE : PhotoStatus.ARCHIVED;
        if (photo.getStatus() != expected) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "图片状态不允许该操作");
        }
        photo.setStatus(archive ? PhotoStatus.ARCHIVED : PhotoStatus.AVAILABLE);
        mapper.updateById(photo);
        return toView(photo);
    }

    @Transactional
    public void delete(Long id, AuthenticatedUser user) {
        PhotoEntity photo = require(id);
        requirePrivileged(user);
        if (user.role() != UserRole.ADMIN) {
            long adopted = adoptionCount(id);
            if (adopted > 0) {
                throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "已被采用的图片只能归档");
            }
        }
        mapper.deleteById(id);
        deleteObject(id, "成品图", photo.getObjectKey());
        deleteObject(id, "缩略图", photo.getThumbnailObjectKey());
        deleteObject(id, "原图", photo.getOriginalObjectKey());
    }

    private void deleteObject(Long photoId, String objectType, String objectKey) {
        if (!StringUtils.hasText(objectKey)) return;
        try {
            storage.delete(objectKey);
        } catch (RuntimeException exception) {
            log.warn("数据库记录已删除，但{}清理失败（photoId={}, objectKey={}），需要人工或清理任务重试",
                    objectType, photoId, objectKey, exception);
        }
    }

    private void validateFile(String fileName, String contentType, long size) {
        if (size <= 0 || size > properties.imageMaxBytes()) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "单张图片不得超过 100 MiB");
        }
        boolean jpeg = contentType.equals("image/jpeg")
                && (fileName.toLowerCase().endsWith(".jpg") || fileName.toLowerCase().endsWith(".jpeg"));
        boolean png = contentType.equals("image/png") && fileName.toLowerCase().endsWith(".png");
        if (!jpeg && !png) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE, "仅支持 JPG 和 PNG");
        }
    }

    private boolean isParticipant(Long requestId, Long userId) {
        return participantMapper.selectCount(Wrappers.<RequestParticipantEntity>lambdaQuery()
                .eq(RequestParticipantEntity::getRequestId, requestId)
                .eq(RequestParticipantEntity::getUserId, userId)) > 0;
    }

    private PhotoEntity require(Long id) {
        PhotoEntity photo = mapper.selectById(id);
        if (photo == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "图片不存在");
        return photo;
    }

    private void requireUploaderOrAdmin(PhotoEntity photo, AuthenticatedUser user) {
        if (!photo.getUploadedBy().equals(user.id()) && user.role() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作该图片");
        }
    }

    private void requireUploaderOrPrivileged(PhotoEntity photo, AuthenticatedUser user) {
        if (!photo.getUploadedBy().equals(user.id())
                && user.role() != UserRole.ADMIN && user.role() != UserRole.MINISTER) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作该图片");
        }
    }

    private void requirePrivileged(AuthenticatedUser user) {
        if (user.role() != UserRole.ADMIN && user.role() != UserRole.MINISTER) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权执行该操作");
        }
    }

    private void requireVisible(PhotoEntity photo, AuthenticatedUser user) {
        // Admin and Minister can see all photos
        if (user.role() == UserRole.ADMIN || user.role() == UserRole.MINISTER) {
            return;
        }
        // Campus managers can only see photos from their campus
        if (user.role() == UserRole.CAMPUS_MANAGER) {
            if (photo.getCampusId() == null || !photo.getCampusId().equals(user.campusId())) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该图片");
            }
        }
    }

    private String tagsJson(List<String> tags) {
        if (tags == null) return "[]";
        return tags.stream().map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private PhotoView toView(PhotoEntity p) {
        String thumbnailUrl = null;
        if (p.getThumbnailObjectKey() != null
                && (p.getStatus() == PhotoStatus.AVAILABLE || p.getStatus() == PhotoStatus.ARCHIVED)) {
            thumbnailUrl = storage.presignGet(p.getThumbnailObjectKey(), null,
                    properties.downloadUrlTtl()).url().toString();
        }
        // 获取图片关联的所有项目
        List<ProjectLink> projects = jdbc.sql("""
                SELECT DISTINCT pr.id, pr.title
                FROM photo_project pp
                JOIN project pr ON pr.id = pp.project_id AND pr.deleted = 0
                WHERE pp.photo_id = :photoId
                ORDER BY pr.id
                """)
                .param("photoId", p.getId())
                .query((rs, rowNum) -> new ProjectLink(rs.getLong("id"), rs.getString("title")))
                .list();
        List<Long> projectIds = projects.stream().map(ProjectLink::id).toList();

        return new PhotoView(p.getId(), p.getRequestId(), p.getProjectId(), p.getTitle(), p.getDescription(),
                p.getPhotographerStudentId(), p.getPhotographerName(), p.getUploadedBy(), p.getCampusId(),
                p.getTakenAt(), p.getTagsJson(), p.getWidth(), p.getHeight(), p.getSize(), p.getContentType(),
                p.getStoredFileName(), thumbnailUrl, p.getStatus(), p.getFailureReason(),
                p.getCreatedAt(), p.getVersion(), adoptionCount(p.getId()), projectIds, projects);
    }

    private long adoptionCount(Long photoId) {
        return jdbc.sql("SELECT COUNT(*) FROM adoption WHERE photo_id=:photoId AND deleted=0")
                .param("photoId", photoId)
                .query(Long.class)
                .single();
    }

    public record CreateTicket(Long requestId, Long projectId, String fileName, String contentType, long size,
                               String sha256, Long photographerContactId,
                               LocalDateTime takenAt) {}
    public record CompleteUpload(String title, String description, List<String> tags) {}
    public record Metadata(String title, String description, Long photographerContactId,
                           LocalDateTime takenAt, List<String> tags, int version) {}
    public record UploadTicket(Long photoId, String uploadUrl, String method, String contentType,
                               java.time.Instant expiresAt) {}
    public record DownloadUrl(String downloadUrl, java.time.Instant expiresAt, String fileName) {}
    public record PhotoView(Long id, Long requestId, Long projectId, String title, String description,
                            String photographerStudentId, String photographerName, Long uploadedBy,
                            Long campusId, LocalDateTime takenAt, String tagsJson, Integer width,
                            Integer height, Long size, String contentType, String storedFileName,
                            String thumbnailUrl, PhotoStatus status, String failureReason,
                            LocalDateTime uploadedAt, Integer version, long adoptionCount,
                            List<Long> relatedProjectIds, List<ProjectLink> relatedProjects) {}
    public record ProjectLink(Long id, String title) {}
}
