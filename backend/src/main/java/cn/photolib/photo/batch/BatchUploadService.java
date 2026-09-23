package cn.photolib.photo.batch;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.upload.ImageUploadPolicy;
import cn.photolib.common.util.PublicId;
import cn.photolib.photo.PhotoProcessingService;
import cn.photolib.photo.PhotoProcessingWorkspace;
import cn.photolib.photo.PhotoTags;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
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
    private final cn.photolib.photo.AbandonedUploadCleanupJob abandonedUploads;
    private final PhotoProcessingWorkspace workspace;

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
        Long projectId = command.projectId();
        if (command.requestId() != null) {
            PhotoRequestEntity request = requestService.requireParticipantAccess(command.requestId(), user);
            projectId = request.getProjectId();
        } else {
            requireGalleryUploadCampus(user);
            if (projectId != null) requireProjectUploadAccess(projectId, user);
        }
        if (command.mode() == BatchMode.ZIP) {
            return createZipBatch(new ZipBatch(command.requestId(), projectId, user.id(), null,
                    command.archiveFileName(), command.archiveSize()));
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
        batch.setTotalCount(command.files().size());
        batch.setSuccessCount(0);
        batch.setFailureCount(0);
        batch.setCreatedAt(now);
        batch.setUpdatedAt(now);
        List<ItemTicket> tickets = new ArrayList<>();
        batchMapper.insert(batch);
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
            item.setUploadUrlExpiresAt(abandonedUploads.uploadUrlExpiresAt());
            item.setCreatedAt(now);
            item.setUpdatedAt(now);
            itemMapper.insert(item);
            ObjectStorageService.SignedUrl signed = storage.presignPut(
                    key, file.contentType(), storageProperties.uploadUrlTtl());
            tickets.add(new ItemTicket(item.getId(), file.fileName(), signed.url().toString(),
                    file.contentType(), signed.expiresAt()));
        }
        abandonedUploads.nudge();
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
            return completeZipBatch(id);
        }
        transitionBatch(id, BatchStatus.UPLOADING, BatchStatus.PROCESSING);
        for (PhotoUploadItemEntity item : items(id)) {
            ObjectStorageService.ObjectInfo info = storage.stat(item.getTempObjectKey());
            if (info.size() > imageMaxBytes()) {
                item.setStatus(BatchItemStatus.FAILED);
                item.setFailureReason("图片超过 100 MiB");
            } else {
                item.setStatus(BatchItemStatus.WAITING_METADATA);
                item.setSize(info.size());
            }
            itemMapper.updateById(item);
        }
        transitionBatch(id, BatchStatus.PROCESSING, BatchStatus.WAITING_METADATA);
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
        List<String> tags = allowedTags(batch, metadata.tags());
        transitionItem(itemId, BatchItemStatus.WAITING_METADATA, BatchItemStatus.PROCESSING);
        Long campusId = batch.getRequestId() == null
                ? requireGalleryUploadCampus(user)
                : requestService.get(batch.getRequestId()).getCampusId();
        var photographer = campusMemberService.resolvePhotographer(metadata.photographerContactId(), campusId);
        createPhoto(batch, item, metadata.title(), metadata.description(), metadata.takenAt(),
                tags, photographer.getStudentId(), photographer.getName(), campusId, user.id(), null);
        refreshBatch(batchId);
        return view(batchMapper.selectById(batchId));
    }

    @Transactional
    public BatchView setMetadataForAll(String batchId, BatchMetadata metadata, AuthenticatedUser user) {
        PhotoUploadBatchEntity batch = requireOwned(batchId, user);
        if (batch.getRequestId() == null && batch.getProjectId() != null) {
            requireProjectUploadAccess(batch.getProjectId(), user);
        }
        Long campusId = batch.getRequestId() == null
                ? requireGalleryUploadCampus(user)
                : requestService.get(batch.getRequestId()).getCampusId();
        var photographer = campusMemberService.resolvePhotographer(metadata.photographerContactId(), campusId);
        return finishAllWithSnapshot(batchId, new PhotoSnapshot(
                photographer.getStudentId(), photographer.getName(), campusId, user.id(), null,
                metadata.takenAt(), metadata.description(), metadata.tags()));
    }

    // ------------------------------------------------------------------ 中立接口
    //
    // 下面三个方法**不做任何授权**，授权由调用方按自己的规则完成：站内是这个类里
    // 的 create / complete / setMetadataForAll（权限码 + 参与人 + 校区），站外是
    // 上传链接（`ProjectShareUploadService`：链接用途 + 会话 + 选题是否仍在收图）。
    // 抽出来是为了让两条路共用同一套限额、同一套状态迁移和同一段"批次条目变成
    // 照片"的簿记——ZIP 解包的阈值只有一份，松的那一份才不会被拿来当入口。

    /** 建一个 ZIP 批次并签出上传地址。 */
    @Transactional
    public BatchTicket createZipBatch(ZipBatch request) {
        if (request.archiveSize() == null || request.archiveSize() <= 0
                || request.archiveSize() > ImageUploadPolicy.MAX_ARCHIVE_BYTES) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "ZIP 不得超过 1.5 GB");
        }
        String batchId = PublicId.next();
        LocalDateTime now = LocalDateTime.now();
        String key = "temporary/batches/" + batchId + "/archive.zip";
        PhotoUploadBatchEntity batch = new PhotoUploadBatchEntity();
        batch.setId(batchId);
        batch.setMode(BatchMode.ZIP);
        batch.setRequestId(request.requestId());
        batch.setProjectId(request.projectId());
        batch.setCreatedBy(request.createdBy());
        batch.setShareLinkId(request.shareLinkId());
        batch.setStatus(BatchStatus.UPLOADING);
        batch.setTotalCount(0);
        batch.setSuccessCount(0);
        batch.setFailureCount(0);
        batch.setArchiveObjectKey(key);
        batch.setArchiveFileName(request.archiveFileName());
        batch.setArchiveSize(request.archiveSize());
        batch.setUploadUrlExpiresAt(abandonedUploads.uploadUrlExpiresAt());
        batch.setCreatedAt(now);
        batch.setUpdatedAt(now);
        batchMapper.insert(batch);
        ObjectStorageService.SignedUrl signed = storage.presignPut(
                key, "application/zip", storageProperties.uploadUrlTtl());
        // 与单张一致：每次签票据都捅一下清理任务，它自己节流。
        abandonedUploads.nudge();
        return new BatchTicket(batchId, BatchMode.ZIP, List.of(new ItemTicket(null,
                request.archiveFileName(), signed.url().toString(), "application/zip",
                signed.expiresAt())));
    }

    /**
     * ZIP 的字节已经就位：复核实际大小后交给解包。
     *
     * <p>签票据时那次校验信的是客户端自报的 size，这里问的是对象存储上真实躺着的
     * 东西——两者不一致正是"签一个小的、传一个大的"这条路。</p>
     */
    @Transactional
    public BatchView completeZipBatch(String batchId) {
        PhotoUploadBatchEntity batch = batchMapper.selectById(batchId);
        if (batch == null || batch.getMode() != BatchMode.ZIP) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "上传批次不存在");
        }
        if (batch.getStatus() != BatchStatus.UPLOADING) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "批次不处于上传状态");
        }
        ObjectStorageService.ObjectInfo info = storage.stat(batch.getArchiveObjectKey());
        if (info.size() > ImageUploadPolicy.MAX_ARCHIVE_BYTES) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "ZIP 不得超过 1.5 GB");
        }
        transitionBatch(batchId, BatchStatus.UPLOADING, BatchStatus.PROCESSING);
        events.publishEvent(new BatchProcessingService.ZipProcessRequested(batchId));
        return view(batchMapper.selectById(batchId));
    }

    /**
     * 把批次里所有待整理的条目按同一份拍摄者快照落成照片，交给压缩管线。
     *
     * <p>标签仍按批次所属选题的预设校验，并且**在任何状态迁移之前**——被拒时批次
     * 原样留在待整理状态，可以改完再来一次。</p>
     */
    @Transactional
    public BatchView finishAllWithSnapshot(String batchId, PhotoSnapshot snapshot) {
        PhotoUploadBatchEntity batch = batchMapper.selectById(batchId);
        if (batch == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "上传批次不存在");
        if (batch.getStatus() != BatchStatus.WAITING_METADATA) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "批次尚未完成解压或已开始处理");
        }
        List<String> tags = allowedTags(batch, snapshot.tags());
        transitionBatch(batchId, BatchStatus.WAITING_METADATA, BatchStatus.PROCESSING);
        List<PhotoUploadItemEntity> waitingItems = waitingItems(batchId);
        if (waitingItems.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "批次中没有可整理的图片");
        }
        for (PhotoUploadItemEntity item : waitingItems) {
            transitionItem(item.getId(), BatchItemStatus.WAITING_METADATA, BatchItemStatus.PROCESSING);
            createPhoto(batch, item, titleFromFileName(item.getOriginalFileName()),
                    snapshot.description(), snapshot.takenAt(), tags,
                    snapshot.photographerStudentId(), snapshot.photographerName(),
                    snapshot.campusId(), snapshot.uploadedBy(), snapshot.shareLinkId());
        }
        refreshBatch(batchId);
        return view(batchMapper.selectById(batchId));
    }

    /** 等着被整理成照片的条目，按 id 升序（解包顺序）。 */
    public List<PhotoUploadItemEntity> waitingItems(String batchId) {
        return itemMapper.selectList(Wrappers.<PhotoUploadItemEntity>lambdaQuery()
                .eq(PhotoUploadItemEntity::getBatchId, batchId)
                .eq(PhotoUploadItemEntity::getStatus, BatchItemStatus.WAITING_METADATA)
                .orderByAsc(PhotoUploadItemEntity::getId));
    }

    public BatchView viewOf(PhotoUploadBatchEntity batch) {
        return view(batch);
    }

    private void createPhoto(PhotoUploadBatchEntity batch, PhotoUploadItemEntity item, String title,
                             String description, LocalDateTime takenAt, List<String> tags,
                             String photographerStudentId, String photographerName, Long campusId,
                             Long uploadedBy, Long shareLinkId) {
        String sha256 = normalizedSha256(item.getSha256());
        if (sha256 != null) item.setSha256(sha256);
        ExistingPhoto duplicate = findExistingPhoto(sha256);
        if (duplicate != null) {
            markDuplicate(item, duplicate);
            return;
        }
        String extension = item.getContentType().equals("image/png") ? "png" : "jpg";
        PhotoEntity photo = new PhotoEntity();
        photo.setRequestId(batch.getRequestId());
        photo.setProjectId(batch.getProjectId());
        photo.setTitle(title);
        photo.setDescription(description);
        photo.setPhotographerStudentId(photographerStudentId);
        photo.setPhotographerName(photographerName);
        photo.setUploadedBy(uploadedBy);
        photo.setShareLinkId(shareLinkId);
        photo.setCampusId(campusId);
        photo.setTakenAt(takenAt);
        photo.setTagsJson(PhotoTags.toJson(tags));
        photo.setSize(item.getSize());
        photo.setContentType(item.getContentType());
        photo.setOriginalObjectKey(item.getTempObjectKey());
        photo.setObjectKey("photos/" + LocalDateTime.now().getYear() + "/" + UUID.randomUUID() + "." + extension);
        photo.setSha256(sha256 == null ? "0".repeat(64) : sha256);
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

    private String normalizedSha256(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.toLowerCase(Locale.ROOT);
        return "0".repeat(64).equals(normalized) ? null : normalized;
    }

    /**
     * 全库查重。处理失败留下的行（停在 {@code UPLOADING} 且带 {@code failure_reason}）
     * 不算：那张图从没进过相册，重传同一个 ZIP 正是为了把它补上，算作重复就永远补不进来。
     */
    private ExistingPhoto findExistingPhoto(String sha256) {
        if (sha256 == null) return null;
        return jdbc.sql("""
                SELECT title FROM photo
                WHERE sha256=:sha256 AND deleted=0
                  AND NOT (status='UPLOADING' AND failure_reason IS NOT NULL)
                ORDER BY id LIMIT 1
                """).param("sha256", sha256)
                .query((rs, rowNum) -> new ExistingPhoto(rs.getString("title")))
                .list().stream().findFirst().orElse(null);
    }

    private void markDuplicate(PhotoUploadItemEntity item, ExistingPhoto existing) {
        cleanupDuplicateSource(item);
        item.setStatus(BatchItemStatus.FAILED);
        item.setFailureReason(existing.title() == null || existing.title().isBlank()
                ? "图片已存在，已跳过重复项"
                : "图片已存在，已跳过重复项（标题：" + existing.title() + "）");
        itemMapper.updateById(item);
        itemMapper.clearTempLocalPath(item.getId(), LocalDateTime.now());
    }

    /**
     * 删掉重复项的临时字节。放到事务提交之后：同一批里后面的条目失败导致回滚时，
     * 这一条会回到待整理状态，{@code temp_local_path} 也跟着回来，文件必须还在。
     */
    private void cleanupDuplicateSource(PhotoUploadItemEntity item) {
        Long itemId = item.getId();
        String localPath = item.getTempLocalPath();
        String objectKey = item.getTempObjectKey();
        item.setTempLocalPath(null);
        afterCommit(() -> {
            if (localPath != null && !localPath.isBlank()) {
                try {
                    workspace.deleteBatchFile(workspace.resolveStoredPath(localPath));
                } catch (RuntimeException exception) {
                    log.warn("清理重复图片的本地临时文件失败: itemId={}, path={}",
                            itemId, localPath, exception);
                }
                return;
            }
            if (objectKey != null && !objectKey.isBlank()) {
                try {
                    storage.delete(objectKey);
                } catch (RuntimeException exception) {
                    log.warn("清理重复图片的临时对象失败: itemId={}, objectKey={}",
                            itemId, objectKey, exception);
                }
            }
        });
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private void refreshBatch(String batchId) {
        batchMapper.refreshCounters(batchId, LocalDateTime.now());
    }

    /**
     * 批次落在选题里（需求上传，或带 projectId 的图库上传）时，标签受该选题预设约束；
     * 直接传图库的批次不受限。在任何状态迁移之前校验，被拒时批次原样留在待整理状态。
     */
    private List<String> allowedTags(PhotoUploadBatchEntity batch, List<String> requested) {
        List<String> tags = PhotoTags.normalize(requested);
        projectService.requireAllowedPhotoTags(batch.getProjectId(), tags);
        return tags;
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
        if (file.size() <= 0 || file.size() > imageMaxBytes()) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "单张图片不得超过 100 MiB");
        }
        boolean valid = ImageUploadPolicy.fileNameMatchesContentType(file.fileName(), file.contentType());
        if (!valid) throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE, "仅支持 JPG 和 PNG");
    }

    /**
     * {@code storage.image-max-bytes} stays authoritative for the gallery so an
     * operator can tighten the limit, while the shared policy constant remains
     * the hard ceiling a looser configuration cannot raise.
     */
    private long imageMaxBytes() {
        return Math.min(storageProperties.imageMaxBytes(), ImageUploadPolicy.MAX_IMAGE_BYTES);
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

    private record ExistingPhoto(String title) {}

    public record CreateBatch(BatchMode mode, Long requestId, Long projectId, String archiveFileName,
                              Long archiveSize, List<FileSpec> files) {}
    /** 中立的 ZIP 批次建立参数；{@code shareLinkId} 非空表示这是一条上传链接开的批次。 */
    public record ZipBatch(Long requestId, Long projectId, Long createdBy, Long shareLinkId,
                           String archiveFileName, Long archiveSize) {}
    /** 一个批次里所有照片共用的那份信息。拍摄者姓名/学号是快照，不落 contactId。 */
    public record PhotoSnapshot(String photographerStudentId, String photographerName, Long campusId,
                                Long uploadedBy, Long shareLinkId, LocalDateTime takenAt,
                                String description, List<String> tags) {}
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
