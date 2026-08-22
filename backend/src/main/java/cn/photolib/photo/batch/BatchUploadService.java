package cn.photolib.photo.batch;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.upload.ImageUploadPolicy;
import cn.photolib.common.util.PublicId;
import cn.photolib.photo.PhotoProcessingService;
import cn.photolib.photo.mapper.PhotoMapper;
import cn.photolib.photo.model.PhotoEntity;
import cn.photolib.photo.model.PhotoStatus;
import cn.photolib.request.RequestService;
import cn.photolib.request.model.PhotoRequestEntity;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.storage.StorageProperties;
import cn.photolib.permission.PermissionCode;
import cn.photolib.project.ProjectService;
import cn.photolib.project.model.ProjectStatus;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BatchUploadService {
    private final PhotoUploadBatchMapper batchMapper;
    private final PhotoUploadItemMapper itemMapper;
    private final PhotoMapper photoMapper;
    private final RequestService requestService;
    private final ProjectService projectService;
    private final ObjectStorageService storage;
    private final StorageProperties storageProperties;
    private final ApplicationEventPublisher events;
    private final JdbcClient jdbc;
    private final cn.photolib.directory.CampusMemberService campusMemberService;

    @Transactional
    public BatchTicket create(CreateBatch command, AuthenticatedUser user) {
        PermissionCode requiredPermission = command.requestId() == null
                ? PermissionCode.PHOTO_UPLOAD : PermissionCode.REQUEST_PHOTO_MANAGE;
        if (!user.hasPermission(requiredPermission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权批量上传图片");
        }
        if (command.mode() == BatchMode.FILES && (command.files() == null
                || command.files().isEmpty() || command.files().size() > 100)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "FILES 模式需上传 1 至 100 张图片");
        }
        if (command.mode() == BatchMode.ZIP
                && (command.archiveSize() == null || command.archiveSize() <= 0
                || command.archiveSize() > ImageUploadPolicy.MAX_ARCHIVE_BYTES)) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "ZIP 不得超过 1.5 GB");
        }
        Long projectId = command.projectId();
        if (command.requestId() != null) {
            PhotoRequestEntity request = requestService.requireParticipantAccess(command.requestId(), user);
            projectId = request.getProjectId();
        } else {
            requireGalleryUploadCampus(user);
            if (projectId != null) requireProjectUploadAccess(projectId, user);
        }
        String batchId = PublicId.next();
        LocalDateTime now = LocalDateTime.now();
        PhotoUploadBatchEntity batch = new PhotoUploadBatchEntity();
        batch.setId(batchId);
        batch.setMode(command.mode());
        batch.setRequestId(command.requestId());
        batch.setProjectId(projectId);
        batch.setCreatedBy(user.id());
        batch.setStatus(BatchStatus.UPLOADING);
        batch.setTotalCount(command.mode() == BatchMode.FILES ? command.files().size() : 0);
        batch.setSuccessCount(0);
        batch.setFailureCount(0);
        batch.setCreatedAt(now);
        batch.setUpdatedAt(now);
        List<ItemTicket> tickets = new ArrayList<>();
        if (command.mode() == BatchMode.ZIP) {
            String key = "temporary/batches/" + batchId + "/archive.zip";
            batch.setArchiveObjectKey(key);
            batch.setArchiveFileName(command.archiveFileName());
            batch.setArchiveSize(command.archiveSize());
            ObjectStorageService.SignedUrl signed = storage.presignPut(
                    key, "application/zip", storageProperties.uploadUrlTtl());
            tickets.add(new ItemTicket(null, command.archiveFileName(), signed.url().toString(),
                    "application/zip", signed.expiresAt()));
        }
        batchMapper.insert(batch);
        if (command.mode() == BatchMode.FILES) {
            for (FileSpec file : command.files()) {
                validateFile(file);
                String extension = file.contentType().equals("image/png") ? "png" : "jpg";
                String key = "temporary/batches/" + batchId + "/" + UUID.randomUUID() + "." + extension;
                PhotoUploadItemEntity item = new PhotoUploadItemEntity();
                item.setBatchId(batchId);
                item.setOriginalFileName(file.fileName());
                item.setTempObjectKey(key);
                item.setContentType(file.contentType());
                item.setSize(file.size());
                item.setSha256(file.sha256());
                item.setStatus(BatchItemStatus.UPLOADING);
                item.setCreatedAt(now);
                item.setUpdatedAt(now);
                itemMapper.insert(item);
                ObjectStorageService.SignedUrl signed = storage.presignPut(
                        key, file.contentType(), storageProperties.uploadUrlTtl());
                tickets.add(new ItemTicket(item.getId(), file.fileName(), signed.url().toString(),
                        file.contentType(), signed.expiresAt()));
            }
        }
        return new BatchTicket(batchId, command.mode(), tickets);
    }

    @Transactional
    public BatchView complete(String id, AuthenticatedUser user) {
        PhotoUploadBatchEntity batch = requireOwned(id, user);
        if (batch.getRequestId() == null && batch.getProjectId() != null) {
            requireProjectUploadAccess(batch.getProjectId(), user);
        }
        if (batch.getStatus() != BatchStatus.UPLOADING) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "批次不处于上传状态");
        }
        if (batch.getMode() == BatchMode.ZIP) {
            ObjectStorageService.ObjectInfo info = storage.stat(batch.getArchiveObjectKey());
            if (info.size() > ImageUploadPolicy.MAX_ARCHIVE_BYTES) {
                throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "ZIP 不得超过 1.5 GB");
            }
            transitionBatch(id, BatchStatus.UPLOADING, BatchStatus.PROCESSING);
            events.publishEvent(new BatchProcessingService.ZipProcessRequested(id));
        } else {
            transitionBatch(id, BatchStatus.UPLOADING, BatchStatus.PROCESSING);
            List<PhotoUploadItemEntity> items = items(id);
            for (PhotoUploadItemEntity item : items) {
                ObjectStorageService.ObjectInfo info = storage.stat(item.getTempObjectKey());
                if (info.size() > ImageUploadPolicy.MAX_IMAGE_BYTES) {
                    item.setStatus(BatchItemStatus.FAILED);
                    item.setFailureReason("图片超过 100 MiB");
                } else {
                    item.setStatus(BatchItemStatus.WAITING_METADATA);
                    item.setSize(info.size());
                }
                itemMapper.updateById(item);
            }
            transitionBatch(id, BatchStatus.PROCESSING, BatchStatus.WAITING_METADATA);
        }
        return view(batchMapper.selectById(id));
    }

    public BatchView get(String id, AuthenticatedUser user) {
        return view(requireOwned(id, user));
    }

    @Transactional
    public BatchView setMetadata(String batchId, Long itemId, ItemMetadata metadata, AuthenticatedUser user) {
        PhotoUploadBatchEntity batch = requireOwned(batchId, user);
        if (batch.getRequestId() == null && batch.getProjectId() != null) {
            requireProjectUploadAccess(batch.getProjectId(), user);
        }
        PhotoUploadItemEntity item = itemMapper.selectById(itemId);
        if (item == null || !item.getBatchId().equals(batchId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "批次图片不存在");
        }
        if (item.getStatus() != BatchItemStatus.WAITING_METADATA) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "当前图片不能填写元数据");
        }
        transitionItem(itemId, BatchItemStatus.WAITING_METADATA, BatchItemStatus.PROCESSING);
        Long campusId = batch.getRequestId() == null
                ? requireGalleryUploadCampus(user)
                : requestService.get(batch.getRequestId()).getCampusId();
        var photographer = campusMemberService.resolvePhotographer(metadata.photographerContactId(), campusId);
        createPhoto(batch, item, metadata.title(), metadata.description(), metadata.takenAt(),
                metadata.tags(), photographer.getStudentId(), photographer.getName(), campusId, user.id());
        return view(batchMapper.selectById(batchId));
    }

    @Transactional
    public BatchView setMetadataForAll(String batchId, BatchMetadata metadata, AuthenticatedUser user) {
        PhotoUploadBatchEntity batch = requireOwned(batchId, user);
        if (batch.getRequestId() == null && batch.getProjectId() != null) {
            requireProjectUploadAccess(batch.getProjectId(), user);
        }
        if (batch.getStatus() != BatchStatus.WAITING_METADATA) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "批次尚未完成解压或已开始处理");
        }
        transitionBatch(batchId, BatchStatus.WAITING_METADATA, BatchStatus.PROCESSING);
        List<PhotoUploadItemEntity> waitingItems = itemMapper.selectList(
                Wrappers.<PhotoUploadItemEntity>lambdaQuery()
                        .eq(PhotoUploadItemEntity::getBatchId, batchId)
                        .eq(PhotoUploadItemEntity::getStatus, BatchItemStatus.WAITING_METADATA)
                        .orderByAsc(PhotoUploadItemEntity::getId));
        if (waitingItems.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "批次中没有可整理的图片");
        }
        Long campusId = batch.getRequestId() == null
                ? requireGalleryUploadCampus(user)
                : requestService.get(batch.getRequestId()).getCampusId();
        var photographer = campusMemberService.resolvePhotographer(metadata.photographerContactId(), campusId);
        for (PhotoUploadItemEntity item : waitingItems) {
            transitionItem(item.getId(), BatchItemStatus.WAITING_METADATA, BatchItemStatus.PROCESSING);
            createPhoto(batch, item, titleFromFileName(item.getOriginalFileName()), metadata.description(),
                    metadata.takenAt(), metadata.tags(), photographer.getStudentId(), photographer.getName(),
                    campusId, user.id());
        }
        return view(batchMapper.selectById(batchId));
    }

    private void createPhoto(PhotoUploadBatchEntity batch, PhotoUploadItemEntity item, String title,
                             String description, LocalDateTime takenAt, List<String> tags,
                             String photographerStudentId, String photographerName, Long campusId,
                             Long uploadedBy) {
        String extension = item.getContentType().equals("image/png") ? "png" : "jpg";
        PhotoEntity photo = new PhotoEntity();
        photo.setRequestId(batch.getRequestId());
        photo.setProjectId(batch.getProjectId());
        photo.setTitle(title);
        photo.setDescription(description);
        photo.setPhotographerStudentId(photographerStudentId);
        photo.setPhotographerName(photographerName);
        photo.setUploadedBy(uploadedBy);
        photo.setCampusId(campusId);
        photo.setTakenAt(takenAt);
        photo.setTagsJson(tagsJson(tags));
        photo.setSize(item.getSize());
        photo.setContentType(item.getContentType());
        photo.setOriginalObjectKey(item.getTempObjectKey());
        photo.setObjectKey("photos/" + LocalDateTime.now().getYear() + "/" + UUID.randomUUID() + "." + extension);
        photo.setSha256(item.getSha256() == null ? "0".repeat(64) : item.getSha256());
        photo.setStatus(PhotoStatus.PROCESSING);
        photoMapper.insert(photo);
        // 归属链接：项目相册/计数以 photo_project 为准。新照片 id 全新，不会撞主键。
        if (photo.getProjectId() != null) {
            jdbc.sql("INSERT INTO photo_project (photo_id, project_id) VALUES (:photoId, :projectId)")
                    .param("photoId", photo.getId())
                    .param("projectId", photo.getProjectId())
                    .update();
        }
        item.setTitle(title);
        item.setDescription(description);
        item.setPhotographerStudentId(photographerStudentId);
        item.setPhotographerName(photographerName);
        item.setTakenAt(takenAt);
        item.setTagsJson(photo.getTagsJson());
        item.setPhotoId(photo.getId());
        item.setStatus(BatchItemStatus.PROCESSING);
        itemMapper.updateById(item);
        events.publishEvent(new PhotoProcessingService.PhotoProcessRequested(photo.getId()));
    }

    private String titleFromFileName(String fileName) {
        String value = fileName == null ? "" : fileName.trim();
        int dot = value.lastIndexOf('.');
        if (dot > 0) value = value.substring(0, dot).trim();
        if (value.isEmpty()) value = "未命名图片";
        int[] codePoints = value.codePoints().limit(200).toArray();
        return new String(codePoints, 0, codePoints.length);
    }

    private void validateFile(FileSpec file) {
        if (file.size() <= 0 || file.size() > ImageUploadPolicy.MAX_IMAGE_BYTES) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "单张图片不得超过 100 MiB");
        }
        boolean valid = ImageUploadPolicy.fileNameMatchesContentType(file.fileName(), file.contentType());
        if (!valid) throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE, "仅支持 JPG 和 PNG");
    }

    private PhotoUploadBatchEntity requireOwned(String id, AuthenticatedUser user) {
        PhotoUploadBatchEntity batch = batchMapper.selectById(id);
        if (batch == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "上传批次不存在");
        if (!batch.getCreatedBy().equals(user.id()) && !user.isAdministrator()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该上传批次");
        }
        PermissionCode requiredPermission = batch.getRequestId() == null
                ? PermissionCode.PHOTO_UPLOAD : PermissionCode.REQUEST_PHOTO_MANAGE;
        if (!user.hasPermission(requiredPermission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前权限不能继续处理该上传批次");
        }
        if (batch.getRequestId() != null) {
            requestService.requireParticipantAccess(batch.getRequestId(), user);
        } else {
            requireGalleryUploadCampus(user);
        }
        return batch;
    }

    private Long requireGalleryUploadCampus(AuthenticatedUser user) {
        Long campusId = user.campusId();
        if (user.isCampusScoped() && (campusId == null || !user.canAccessCampus(campusId))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "校区范围账号尚未分配可用校区");
        }
        return campusId;
    }

    private void requireProjectUploadAccess(Long projectId, AuthenticatedUser user) {
        if (!user.hasPermission(PermissionCode.PROJECT_ADOPT)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权将批量上传图片加入项目相册");
        }
        if (projectService.getVisible(projectId, user).getStatus() != ProjectStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "仅进行中项目可批量上传图片");
        }
    }

    private List<PhotoUploadItemEntity> items(String id) {
        return itemMapper.selectList(Wrappers.<PhotoUploadItemEntity>lambdaQuery()
                .eq(PhotoUploadItemEntity::getBatchId, id).orderByAsc(PhotoUploadItemEntity::getId));
    }

    private void transitionBatch(String id, BatchStatus expected, BatchStatus next) {
        if (batchMapper.transition(id, expected, next, LocalDateTime.now()) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "上传批次已被其他操作修改");
        }
    }

    private void transitionItem(Long id, BatchItemStatus expected, BatchItemStatus next) {
        if (itemMapper.transition(id, expected, next, LocalDateTime.now()) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "批次图片已被其他操作修改");
        }
    }

    private BatchView view(PhotoUploadBatchEntity batch) {
        return new BatchView(batch, items(batch.getId()));
    }

    private String tagsJson(List<String> tags) {
        if (tags == null) return "[]";
        return tags.stream().map(v -> "\"" + v.replace("\"", "\\\"") + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    public record CreateBatch(BatchMode mode, Long requestId, Long projectId, String archiveFileName,
                              Long archiveSize, List<FileSpec> files) {}
    public record FileSpec(String fileName, String contentType, long size, String sha256) {}
    public record ItemMetadata(String title, String description, Long photographerContactId,
                               LocalDateTime takenAt, List<String> tags) {}
    public record BatchMetadata(String description, Long photographerContactId,
                                LocalDateTime takenAt, List<String> tags) {}
    public record ItemTicket(Long itemId, String fileName, String uploadUrl, String contentType,
                             java.time.Instant expiresAt) {}
    public record BatchTicket(String batchId, BatchMode mode, List<ItemTicket> tickets) {}
    public record BatchView(PhotoUploadBatchEntity batch, List<PhotoUploadItemEntity> items) {}
}
