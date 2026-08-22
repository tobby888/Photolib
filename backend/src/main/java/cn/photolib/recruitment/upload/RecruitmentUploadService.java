package cn.photolib.recruitment.upload;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.upload.ImageUploadPolicy;
import cn.photolib.common.util.PublicId;
import cn.photolib.recruitment.RecruitmentDraftService;
import cn.photolib.recruitment.RecruitmentTimeConfig;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.storage.StorageProperties;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecruitmentUploadService {
    private final RecruitmentDraftService draftService;
    private final RecruitmentUploadBatchMapper batchMapper;
    private final RecruitmentUploadItemMapper itemMapper;
    private final ObjectStorageService storage;
    private final StorageProperties storageProperties;
    private final ApplicationEventPublisher events;
    private final Clock recruitmentClock;

    @Transactional
    public BatchTicket create(String publicId, String draftId, String rawToken, CreateBatch command) {
        var draft = draftService.requireWritableForMutation(publicId, draftId, rawToken);
        validateCreate(command);
        if (!batchMapper.lockNonFailedByDraft(draftId).isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "当前草稿已有上传批次，请等待处理完成或移除后再试");
        }
        Duration uploadTtl = boundedUploadTtl(draft.getExpiresAt());

        String batchId = PublicId.next();
        LocalDateTime now = LocalDateTime.now(recruitmentClock);
        RecruitmentUploadBatchEntity batch = new RecruitmentUploadBatchEntity();
        batch.setId(batchId);
        batch.setDraftId(draftId);
        batch.setMode(command.mode());
        batch.setStatus(RecruitmentUploadBatchStatus.UPLOADING);
        batch.setTotalCount(command.mode() == RecruitmentUploadMode.FILES
                ? command.files().size() : 0);
        batch.setSuccessCount(0);
        batch.setFailureCount(0);
        batch.setCreatedAt(now);
        batch.setUpdatedAt(now);

        List<ItemTicket> tickets = new ArrayList<>();
        if (command.mode() == RecruitmentUploadMode.ZIP) {
            String objectKey = temporaryPrefix(draftId, batchId) + "archive.zip";
            batch.setArchiveObjectKey(objectKey);
            String archiveName = ImageUploadPolicy.safeDisplayFileName(command.archiveFileName());
            batch.setArchiveFileName(archiveName);
            batch.setArchiveSize(command.archiveSize());
            ObjectStorageService.SignedUrl signed = storage.presignPut(
                    objectKey, "application/zip", uploadTtl);
            batch.setUploadUrlExpiresAt(expiresAt(signed.expiresAt()));
            tickets.add(new ItemTicket(null, archiveName, signed.url().toString(),
                    signed.method(), "application/zip", signed.expiresAt()));
        }
        batchMapper.insert(batch);

        if (command.mode() == RecruitmentUploadMode.FILES) {
            for (FileSpec file : command.files()) {
                String displayName = ImageUploadPolicy.safeDisplayFileName(file.fileName());
                String normalizedSha = file.sha256().toLowerCase(Locale.ROOT);
                String objectKey = temporaryPrefix(draftId, batchId) + UUID.randomUUID()
                        + ImageUploadPolicy.extension(file.contentType());
                RecruitmentUploadItemEntity item = new RecruitmentUploadItemEntity();
                item.setBatchId(batchId);
                item.setOriginalFileName(displayName);
                item.setTempObjectKey(objectKey);
                item.setContentType(file.contentType());
                item.setSize(file.size());
                item.setSha256(normalizedSha);
                item.setStatus(RecruitmentUploadItemStatus.UPLOADING);
                item.setCreatedAt(now);
                item.setUpdatedAt(now);
                ObjectStorageService.SignedUrl signed = storage.presignPut(
                        objectKey, file.contentType(), uploadTtl);
                item.setUploadUrlExpiresAt(expiresAt(signed.expiresAt()));
                itemMapper.insert(item);
                tickets.add(new ItemTicket(item.getId(), displayName, signed.url().toString(),
                        signed.method(), file.contentType(), signed.expiresAt()));
            }
        }
        return new BatchTicket(batchId, command.mode(), List.copyOf(tickets));
    }

    @Transactional
    public BatchView complete(String publicId, String draftId, String batchId, String rawToken) {
        draftService.requireWritableForMutation(publicId, draftId, rawToken);
        RecruitmentUploadBatchEntity batch = requireBatch(draftId, batchId);
        if (batch.getStatus() != RecruitmentUploadBatchStatus.UPLOADING) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "上传批次不处于上传状态");
        }

        if (batch.getMode() == RecruitmentUploadMode.ZIP) {
            ObjectStorageService.ObjectInfo archive = storage.find(batch.getArchiveObjectKey())
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                            "ZIP 尚未上传完成"));
            boolean invalidArchive = archive.size() <= 0
                    || archive.size() > ImageUploadPolicy.MAX_ARCHIVE_BYTES
                    || archive.size() != batch.getArchiveSize()
                    || !"application/zip".equalsIgnoreCase(archive.contentType());
            if (invalidArchive) {
                LocalDateTime now = LocalDateTime.now(recruitmentClock);
                if (batchMapper.failInvalidZip(batchId, draftId,
                        RecruitmentUploadMessages.ZIP_HEAD_INVALID, now) != 1) {
                    throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                            "上传批次已被其他操作修改");
                }
                deleteTemporaryNow(batch.getArchiveObjectKey(), "ZIP HEAD 校验失败", batchId);
                return view(batchMapper.selectById(batchId));
            }
        }

        LocalDateTime now = LocalDateTime.now(recruitmentClock);
        if (batchMapper.transition(batchId, draftId, RecruitmentUploadBatchStatus.UPLOADING,
                RecruitmentUploadBatchStatus.PROCESSING, now) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "上传批次已被其他操作修改");
        }
        if (batch.getMode() == RecruitmentUploadMode.FILES) {
            int claimed = itemMapper.claimAll(batchId, now);
            if (claimed != batch.getTotalCount()) {
                throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                        "上传条目已被其他操作修改");
            }
        }
        events.publishEvent(new RecruitmentUploadProcessor.ProcessRequested(batchId));
        return view(batchMapper.selectById(batchId));
    }

    public BatchView get(String publicId, String draftId, String batchId, String rawToken) {
        draftService.requireWritable(publicId, draftId, rawToken);
        return view(requireBatch(draftId, batchId));
    }

    private void validateCreate(CreateBatch command) {
        if (command == null || command.mode() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择上传方式");
        }
        if (command.mode() == RecruitmentUploadMode.FILES) {
            if (command.files() == null || command.files().isEmpty()
                    || command.files().size() > ImageUploadPolicy.MAX_IMAGE_COUNT) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "FILES 模式需上传 1 至 100 张图片");
            }
            command.files().forEach(this::validateFile);
            return;
        }
        if (command.archiveSize() == null || command.archiveSize() <= 0
                || command.archiveSize() > ImageUploadPolicy.MAX_ARCHIVE_BYTES) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "ZIP 不得超过 1.5 GB");
        }
        String archiveName = command.archiveFileName();
        if (archiveName == null || archiveName.isBlank()
                || archiveName.codePointCount(0, archiveName.length()) > 255
                || !archiveName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE, "请选择 ZIP 压缩包");
        }
    }

    private void validateFile(FileSpec file) {
        if (file == null || file.fileName() == null || file.fileName().isBlank()
                || file.fileName().codePointCount(0, file.fileName().length()) > 255) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "图片文件名不合法");
        }
        if (file.size() <= 0 || file.size() > ImageUploadPolicy.MAX_IMAGE_BYTES) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "单张图片不得超过 100 MiB");
        }
        if (!ImageUploadPolicy.fileNameMatchesContentType(file.fileName(), file.contentType())) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE, "仅支持 JPG 和 PNG");
        }
        if (file.sha256() == null || !file.sha256().matches("(?i)^[0-9a-f]{64}$")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "图片 SHA-256 不合法");
        }
    }

    private RecruitmentUploadBatchEntity requireBatch(String draftId, String batchId) {
        RecruitmentUploadBatchEntity batch = batchMapper.selectById(batchId);
        if (batch == null || !draftId.equals(batch.getDraftId())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "上传批次不存在");
        }
        return batch;
    }

    private BatchView view(RecruitmentUploadBatchEntity batch) {
        List<ItemView> itemViews = items(batch.getId()).stream().map(item -> new ItemView(
                item.getId(), item.getOriginalFileName(), item.getContentType(), item.getSize(),
                item.getSha256(), item.getStatus(), item.getFailureReason())).toList();
        return new BatchView(batch.getId(), batch.getMode(), batch.getStatus(),
                batch.getTotalCount(), batch.getSuccessCount(), batch.getFailureCount(),
                batch.getFailureReason(), itemViews);
    }

    private List<RecruitmentUploadItemEntity> items(String batchId) {
        return itemMapper.selectList(Wrappers.<RecruitmentUploadItemEntity>lambdaQuery()
                .eq(RecruitmentUploadItemEntity::getBatchId, batchId)
                .orderByAsc(RecruitmentUploadItemEntity::getId));
    }

    private String temporaryPrefix(String draftId, String batchId) {
        return "temporary/recruitments/" + draftId + "/" + batchId + "/";
    }

    private Duration boundedUploadTtl(LocalDateTime draftExpiry) {
        Duration configured = storageProperties.uploadUrlTtl();
        Duration remaining = Duration.between(LocalDateTime.now(recruitmentClock), draftExpiry);
        return boundedUploadTtl(configured, remaining);
    }

    static Duration boundedUploadTtl(Duration configured, Duration remaining) {
        if (remaining.isZero() || remaining.isNegative()) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "招募任务已结束");
        }
        if (configured == null || configured.isZero() || configured.isNegative()) {
            throw new IllegalStateException("上传签名有效期必须大于零");
        }
        Duration capped = configured.compareTo(RecruitmentTimeConfig.MAX_UPLOAD_URL_TTL) > 0
                ? RecruitmentTimeConfig.MAX_UPLOAD_URL_TTL : configured;
        return remaining.compareTo(capped) < 0 ? remaining : capped;
    }

    private LocalDateTime expiresAt(Instant instant) {
        return LocalDateTime.ofInstant(instant, RecruitmentTimeConfig.ZONE);
    }

    private void deleteTemporaryNow(String objectKey, String reason, String resourceId) {
        try {
            storage.delete(objectKey);
        } catch (RuntimeException exception) {
            log.warn("{}后立即删除临时对象失败，将在签名过期后重试: resourceId={}, objectKey={}",
                    reason, resourceId, objectKey, exception);
        }
        // Deliberately retain the database key until upload_url_expires_at. A
        // still-valid presigned PUT could recreate the object after this delete.
    }

    public record CreateBatch(RecruitmentUploadMode mode, String archiveFileName,
                              Long archiveSize, List<FileSpec> files) {
    }

    public record FileSpec(String fileName, String contentType, long size, String sha256) {
    }

    public record ItemTicket(Long itemId, String fileName, String uploadUrl, String method,
                             String contentType, Instant expiresAt) {
    }

    public record BatchTicket(String batchId, RecruitmentUploadMode mode, List<ItemTicket> tickets) {
    }

    public record ItemView(Long id, String fileName, String contentType, Long size, String sha256,
                           RecruitmentUploadItemStatus status, String failureReason) {
    }

    public record BatchView(String id, RecruitmentUploadMode mode, RecruitmentUploadBatchStatus status,
                            int totalCount, int successCount, int failureCount, String failureReason,
                            List<ItemView> items) {
    }
}
