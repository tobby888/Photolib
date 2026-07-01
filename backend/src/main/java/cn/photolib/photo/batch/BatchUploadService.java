package cn.photolib.photo.batch;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.util.PublicId;
import cn.photolib.photo.PhotoProcessingService;
import cn.photolib.photo.mapper.PhotoMapper;
import cn.photolib.photo.model.PhotoEntity;
import cn.photolib.photo.model.PhotoStatus;
import cn.photolib.request.RequestService;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.storage.StorageProperties;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BatchUploadService {
    private static final long MAX_ARCHIVE = 1_500_000_000L;
    private final PhotoUploadBatchMapper batchMapper;
    private final PhotoUploadItemMapper itemMapper;
    private final PhotoMapper photoMapper;
    private final RequestService requestService;
    private final ObjectStorageService storage;
    private final StorageProperties storageProperties;
    private final ApplicationEventPublisher events;

    @Transactional
    public BatchTicket create(CreateBatch command, AuthenticatedUser user) {
        if (command.mode() == BatchMode.FILES && (command.files() == null
                || command.files().isEmpty() || command.files().size() > 100)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "FILES 模式需上传 1 至 100 张图片");
        }
        if (command.mode() == BatchMode.ZIP
                && (command.archiveSize() == null || command.archiveSize() <= 0
                || command.archiveSize() > MAX_ARCHIVE)) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "ZIP 不得超过 1.5 GB");
        }
        if (command.requestId() != null) requestService.get(command.requestId());
        String batchId = PublicId.next();
        LocalDateTime now = LocalDateTime.now();
        PhotoUploadBatchEntity batch = new PhotoUploadBatchEntity();
        batch.setId(batchId);
        batch.setMode(command.mode());
        batch.setRequestId(command.requestId());
        batch.setProjectId(command.projectId());
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
        if (batch.getStatus() != BatchStatus.UPLOADING) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "批次不处于上传状态");
        }
        if (batch.getMode() == BatchMode.ZIP) {
            ObjectStorageService.ObjectInfo info = storage.stat(batch.getArchiveObjectKey());
            if (info.size() > MAX_ARCHIVE) {
                throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "ZIP 不得超过 1.5 GB");
            }
            batch.setStatus(BatchStatus.PROCESSING);
            batchMapper.updateById(batch);
            events.publishEvent(new BatchProcessingService.ZipProcessRequested(id));
        } else {
            List<PhotoUploadItemEntity> items = items(id);
            for (PhotoUploadItemEntity item : items) {
                ObjectStorageService.ObjectInfo info = storage.stat(item.getTempObjectKey());
                if (info.size() > storageProperties.imageMaxBytes()) {
                    item.setStatus(BatchItemStatus.FAILED);
                    item.setFailureReason("图片超过 100 MiB");
                } else {
                    item.setStatus(BatchItemStatus.WAITING_METADATA);
                    item.setSize(info.size());
                }
                itemMapper.updateById(item);
            }
            batch.setStatus(BatchStatus.WAITING_METADATA);
            batchMapper.updateById(batch);
        }
        return view(batchMapper.selectById(id));
    }

    public BatchView get(String id, AuthenticatedUser user) {
        return view(requireOwned(id, user));
    }

    @Transactional
    public BatchView setMetadata(String batchId, Long itemId, ItemMetadata metadata, AuthenticatedUser user) {
        PhotoUploadBatchEntity batch = requireOwned(batchId, user);
        PhotoUploadItemEntity item = itemMapper.selectById(itemId);
        if (item == null || !item.getBatchId().equals(batchId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "批次图片不存在");
        }
        if (item.getStatus() != BatchItemStatus.WAITING_METADATA) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "当前图片不能填写元数据");
        }
        Long campusId = user.campusId();
        if (batch.getRequestId() != null) campusId = requestService.get(batch.getRequestId()).getCampusId();
        String extension = item.getContentType().equals("image/png") ? "png" : "jpg";
        PhotoEntity photo = new PhotoEntity();
        photo.setRequestId(batch.getRequestId());
        photo.setProjectId(batch.getProjectId());
        photo.setTitle(metadata.title());
        photo.setDescription(metadata.description());
        photo.setPhotographerStudentId(metadata.photographerStudentId());
        photo.setPhotographerName(metadata.photographerName());
        photo.setUploadedBy(user.id());
        photo.setCampusId(campusId);
        photo.setTakenAt(metadata.takenAt());
        photo.setTagsJson(tagsJson(metadata.tags()));
        photo.setSize(item.getSize());
        photo.setContentType(item.getContentType());
        photo.setOriginalObjectKey(item.getTempObjectKey());
        photo.setObjectKey("photos/" + LocalDateTime.now().getYear() + "/" + UUID.randomUUID() + "." + extension);
        photo.setSha256(item.getSha256() == null ? "0".repeat(64) : item.getSha256());
        photo.setStatus(PhotoStatus.PROCESSING);
        photoMapper.insert(photo);
        item.setTitle(metadata.title());
        item.setDescription(metadata.description());
        item.setPhotographerStudentId(metadata.photographerStudentId());
        item.setPhotographerName(metadata.photographerName());
        item.setTakenAt(metadata.takenAt());
        item.setTagsJson(photo.getTagsJson());
        item.setPhotoId(photo.getId());
        item.setStatus(BatchItemStatus.PROCESSING);
        itemMapper.updateById(item);
        events.publishEvent(new PhotoProcessingService.PhotoProcessRequested(photo.getId()));
        return view(batch);
    }

    private void validateFile(FileSpec file) {
        if (file.size() <= 0 || file.size() > storageProperties.imageMaxBytes()) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "单张图片不得超过 100 MiB");
        }
        String lower = file.fileName().toLowerCase();
        boolean valid = file.contentType().equals("image/png") && lower.endsWith(".png")
                || file.contentType().equals("image/jpeg")
                && (lower.endsWith(".jpg") || lower.endsWith(".jpeg"));
        if (!valid) throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE, "仅支持 JPG 和 PNG");
    }

    private PhotoUploadBatchEntity requireOwned(String id, AuthenticatedUser user) {
        PhotoUploadBatchEntity batch = batchMapper.selectById(id);
        if (batch == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "上传批次不存在");
        if (!batch.getCreatedBy().equals(user.id()) && user.role() != cn.photolib.user.model.UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该上传批次");
        }
        return batch;
    }

    private List<PhotoUploadItemEntity> items(String id) {
        return itemMapper.selectList(Wrappers.<PhotoUploadItemEntity>lambdaQuery()
                .eq(PhotoUploadItemEntity::getBatchId, id).orderByAsc(PhotoUploadItemEntity::getId));
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
    public record ItemMetadata(String title, String description, String photographerStudentId,
                               String photographerName, LocalDateTime takenAt, List<String> tags) {}
    public record ItemTicket(Long itemId, String fileName, String uploadUrl, String contentType,
                             java.time.Instant expiresAt) {}
    public record BatchTicket(String batchId, BatchMode mode, List<ItemTicket> tickets) {}
    public record BatchView(PhotoUploadBatchEntity batch, List<PhotoUploadItemEntity> items) {}
}
