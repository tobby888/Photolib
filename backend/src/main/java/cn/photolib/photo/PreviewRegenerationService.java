package cn.photolib.photo;

import cn.photolib.photo.model.PhotoStatus;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.storage.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class PreviewRegenerationService {
    private static final int MAX_DIMENSION = 480;
    private static final long MAX_STAGED_PREVIEW_BYTES = 20L * 1024 * 1024;
    static final String GENERATOR_FINGERPRINT = PreviewProfile.CURRENT_GENERATOR_FINGERPRINT;

    private final JdbcClient jdbc;
    private final ObjectStorageService storage;
    private final StorageProperties properties;
    private final ImageCompressor compressor;
    private final PhotoProcessingWorkspace workspace;
    private final TransactionTemplate transactions;
    private final PreviewProfileRepository profiles;
    private final PreviewProfilePolicy profilePolicy;
    private final PreviewMaintenanceLock maintenanceLock;

    PreviewRegenerationService(JdbcClient jdbc, ObjectStorageService storage,
                               StorageProperties properties, ImageCompressor compressor,
                               PhotoProcessingWorkspace workspace,
                               TransactionTemplate transactions) {
        PreviewProfileRepository repository = new PreviewProfileRepository(jdbc);
        this.jdbc = jdbc;
        this.storage = storage;
        this.properties = properties;
        this.compressor = compressor;
        this.workspace = workspace;
        this.transactions = transactions;
        this.profiles = repository;
        this.profilePolicy = new PreviewProfilePolicy(properties, repository);
        this.maintenanceLock = new PreviewMaintenanceLock();
    }

    public Result synchronizeCompressionRatio() {
        return synchronizeCompressionRatio(ProgressListener.NONE);
    }

    public Result synchronizeCompressionRatio(ProgressListener listener) {
        return maintenanceLock.exclusively(() -> bootstrap(listener));
    }

    private Result bootstrap(ProgressListener listener) {
        requireNoAmbientTransaction();
        Objects.requireNonNull(listener, "listener");
        PreviewProfile configured = profilePolicy.bootstrapTarget();
        PreviewProfileRepository.StoredProfile stored = profiles.findStored().orElse(null);
        boolean profileChanged = stored == null || !stored.matches(configured);
        if (!profileChanged) {
            int abandonedDeleted = cleanupAbandonedStages();
            List<PreviewPhoto> photos = loadPhotos();
            List<PreviewPhoto> unhealthy = auditPreviewObjects(photos, configured, listener);
            Result repaired = repairUnhealthyPreviews(configured, unhealthy, ProgressListener.NONE);
            profilePolicy.completeBootstrap(configured);
            log.info("启动三方核对完成：环境、数据库及 {} 个 OSS 预览对象使用 profile {}，修复 {} 张",
                    photos.size(), configured.fingerprint(), repaired.regeneratedCount());
            return new Result(repaired.regenerated(), abandonedDeleted + repaired.deletedCount(),
                    repaired.regeneratedCount());
        }

        Result result = regenerateChangedProfile(configured, stored, loadPhotos(), listener);
        profilePolicy.completeBootstrap(configured);
        return result;
    }

    private Result regenerateChangedProfile(PreviewProfile configured,
                                            PreviewProfileRepository.StoredProfile stored,
                                            List<PreviewPhoto> photos, ProgressListener listener) {
        listener.started(photos.size());
        String profile = configured.fingerprint();
        String generation = generationId(configured);
        StageReconciliation reconciliation = reconcileStages(profile, photos);
        List<GeneratedPreview> staged = new ArrayList<>(photos.size());
        RuntimeException firstFailure = null;

        for (int index = 0; index < photos.size(); index++) {
            PreviewPhoto photo = photos.get(index);
            StagedPreview checkpoint = reconciliation.reusable().get(photo.id());
            try {
                if (checkpoint != null) {
                    staged.add(checkpoint.toGenerated(photo));
                } else {
                    staged.add(generateAndPersist(photo, generation,
                            configured, profile));
                }
            } catch (RuntimeException exception) {
                if (firstFailure == null) firstFailure = exception;
                log.error("暂存新一代预览图失败，继续保存其他照片的检查点：photoId={}",
                        photo.id(), exception);
            } finally {
                listener.progressed(index + 1, photos.size());
            }
        }
        if (firstFailure != null) {
            throw new IllegalStateException(
                    "部分预览图暂存失败；已成功暂存的照片将在下次任务中直接复用", firstFailure);
        }

        // Every checkpoint, including objects uploaded by this invocation, is
        // HEAD-checked and streamed for an exact digest before it can
        // participate in the database switch.
        staged = validateStagedPreviews(configured, generation, staged);

        List<GeneratedPreview> ready = List.copyOf(staged);
        List<GeneratedPreview> activated = executeAtomic(
                status -> switchGeneration(configured, stored, profile, ready));
        if (activated == null) {
            throw new IllegalStateException("预览图数据库切换未返回结果；暂存检查点已保留");
        }

        int deleted = reconciliation.deletedCount() + cleanupReplacedPreviews(activated);
        log.info("预览图已原子切换到新版本 {}：压缩比率 {}，激活 {} 张，清理对象 {} 个",
                generation, configured.ratioText(), activated.size(), deleted);
        return new Result(true, deleted, activated.size());
    }

    public Result repairPreviews(PreviewRepairRequestedEvent event, ProgressListener listener) {
        Objects.requireNonNull(event, "event");
        return maintenanceLock.exclusively(() -> {
            PreviewProfile current = profilePolicy.requireRunningProfile();
            if (!current.equals(event.expectedProfile())) {
                throw new IllegalStateException(
                        "巡检后数据库预览图 profile 已变化，拒绝按过期 profile 修复");
            }
            return repairPreviewsLocked(event.photoIds(), current, listener);
        });
    }

    public Result repairPreviews(List<Long> photoIds, ProgressListener listener) {
        return maintenanceLock.exclusively(() -> repairPreviewsLocked(
                photoIds, profilePolicy.requireRunningProfile(), listener));
    }

    private Result repairPreviewsLocked(List<Long> photoIds, PreviewProfile current,
                                        ProgressListener listener) {
        requireNoAmbientTransaction();
        Objects.requireNonNull(photoIds, "photoIds");
        Objects.requireNonNull(listener, "listener");
        if (photoIds.isEmpty()) {
            listener.started(0);
            listener.progressed(0, 0);
            return new Result(false, 0, 0);
        }
        List<PreviewPhoto> unhealthy = new ArrayList<>();
        for (PreviewPhoto photo : loadPhotosByIds(photoIds)) {
            if (!previewObjectHealthy(photo, current)) {
                unhealthy.add(photo);
            }
        }
        if (unhealthy.isEmpty()) {
            listener.started(0);
            listener.progressed(0, 0);
            return new Result(false, 0, 0);
        }
        return repairUnhealthyPreviews(current, unhealthy, listener);
    }

    private List<PreviewPhoto> auditPreviewObjects(List<PreviewPhoto> photos,
                                                   PreviewProfile expected,
                                                   ProgressListener listener) {
        listener.started(photos.size());
        List<PreviewPhoto> unhealthy = new ArrayList<>();
        for (int index = 0; index < photos.size(); index++) {
            PreviewPhoto photo = photos.get(index);
            try {
                if (!previewObjectHealthy(photo, expected)) {
                    unhealthy.add(photo);
                }
            } catch (RuntimeException exception) {
                throw new IllegalStateException(
                        "启动时无法 HEAD 核验 OSS 预览对象；保留数据库引用，photoId=" + photo.id(),
                        exception);
            } finally {
                listener.progressed(index + 1, photos.size());
            }
        }
        return List.copyOf(unhealthy);
    }

    private boolean previewObjectHealthy(PreviewPhoto photo, PreviewProfile expected) {
        if (!hasCompletePreviewMetadata(photo)) return false;
        Optional<ObjectStorageService.ObjectInfo> object = storage.find(photo.thumbnailObjectKey());
        if (object.isEmpty()) return false;
        return object.get().size() == photo.thumbnailSize()
                && object.get().size() <= MAX_STAGED_PREVIEW_BYTES
                && expected.matches(object.get(), expectedPreviewContentType(photo));
    }

    private Result repairUnhealthyPreviews(PreviewProfile expected, List<PreviewPhoto> unhealthy,
                                           ProgressListener listener) {
        listener.started(unhealthy.size());
        if (unhealthy.isEmpty()) {
            listener.progressed(0, 0);
            return new Result(false, 0, 0);
        }
        String generation = generationId(expected);
        List<GeneratedPreview> activated = new ArrayList<>();
        int stagedDeleted = 0;
        RuntimeException firstFailure = null;
        for (int index = 0; index < unhealthy.size(); index++) {
            PreviewPhoto photo = unhealthy.get(index);
            GeneratedPreview generated = null;
            try {
                generated = generate(photo, generation, expected);
                if (!stagedObjectValid(expected, generated)) {
                    throw new IllegalStateException(
                            "新生成的定向修复预览对象复验失败，拒绝切换数据库引用，photoId="
                                    + photo.id());
                }
                GeneratedPreview staged = generated;
                Optional<GeneratedPreview> switched = executeAtomic(
                        status -> switchOneWithVersionRetry(staged, true, expected));
                if (switched == null) {
                    throw new IllegalStateException("预览图定向修复数据库切换未返回结果");
                }
                if (switched.isPresent()) {
                    activated.add(switched.get());
                } else {
                    stagedDeleted += cleanupGenerated(List.of(generated));
                    log.info("照片已被其他任务修复或不再需要预览，清理本次暂存对象：photoId={}", photo.id());
                }
            } catch (RuntimeException exception) {
                if (generated != null) {
                    stagedDeleted += cleanupGenerated(List.of(generated));
                }
                if (firstFailure == null) firstFailure = exception;
                log.error("定向修复预览图失败，继续处理其余照片：photoId={}", photo.id(), exception);
            } finally {
                listener.progressed(index + 1, unhealthy.size());
            }
        }

        int oldDeleted = cleanupReplacedPreviews(activated);
        if (firstFailure != null) {
            throw new IllegalStateException("部分预览图定向修复失败；已成功修复的照片不会回滚", firstFailure);
        }
        log.info("预览图元数据定向修复完成：目标 {} 张，激活 {} 张，清理对象 {} 个",
                unhealthy.size(), activated.size(), stagedDeleted + oldDeleted);
        return new Result(true, stagedDeleted + oldDeleted, activated.size());
    }

    private StageReconciliation reconcileStages(String profile, List<PreviewPhoto> photos) {
        Map<Long, PreviewPhoto> eligible = new HashMap<>();
        for (PreviewPhoto photo : photos) eligible.put(photo.id(), photo);

        Map<Long, StagedPreview> reusable = new HashMap<>();
        int deleted = 0;
        RuntimeException firstFailure = null;
        for (StagedPreview stage : loadStages()) {
            PreviewPhoto photo = eligible.get(stage.photoId());
            boolean valid = photo != null
                    && stage.cleanupToken() == null
                    && profile.equals(stage.profileFingerprint())
                    && photo.objectKey().equals(stage.sourceObjectKey())
                    && StringUtils.hasText(stage.stagedObjectKey())
                    && stage.stagedObjectKey().startsWith("thumbnails/generations/")
                    && stage.stagedSize() > 0;
            if (valid) {
                reusable.put(stage.photoId(), stage);
                continue;
            }
            try {
                deleted += discardStage(stage);
            } catch (RuntimeException exception) {
                if (firstFailure == null) firstFailure = exception;
                log.warn("清理失效的预览图暂存检查点失败，保留记录等待重试：photoId={}",
                        stage.photoId(), exception);
            }
        }
        if (firstFailure != null) {
            throw new IllegalStateException(
                    "存在无法清理的旧预览图检查点；记录已保留，修复存储后可安全重试", firstFailure);
        }
        return new StageReconciliation(Map.copyOf(reusable), deleted);
    }

    private int cleanupAbandonedStages() {
        int deleted = 0;
        for (StagedPreview stage : loadStages()) {
            try {
                deleted += discardStage(stage);
            } catch (RuntimeException exception) {
                // The active preview profile is already healthy. Cleanup failure
                // must not downgrade it, and keeping the row makes the next
                // startup an exact-key retry without an object-store scan.
                log.warn("清理已放弃的预览图暂存检查点失败，保留记录等待下次启动：photoId={}",
                        stage.photoId(), exception);
            }
        }
        return deleted;
    }

    private List<GeneratedPreview> validateStagedPreviews(
            PreviewProfile expected, String generation,
            List<GeneratedPreview> staged) {
        List<GeneratedPreview> ready = new ArrayList<>(staged.size());
        for (GeneratedPreview preview : staged) {
            if (stagedObjectValid(expected, preview)) {
                ready.add(preview);
                continue;
            }

            StagedPreview checkpoint = toCheckpoint(expected, preview);
            GeneratedPreview replacement;
            try {
                discardStage(checkpoint);
                replacement = generateAndPersist(preview.photo(), generation,
                        expected, expected.fingerprint());
            } catch (RuntimeException exception) {
                throw new IllegalStateException(
                        "暂存预览对象明确缺失或校验异常，安全重建失败；数据库尚未切换，photoId="
                                + preview.photo().id(), exception);
            }
            if (!stagedObjectValid(expected, replacement)) {
                // Keep the replacement checkpoint. The next startup can retry
                // it without ever activating an object that failed validation.
                throw new IllegalStateException(
                        "重建后的暂存预览对象再次校验失败；检查点已保留且数据库尚未切换，photoId="
                                + preview.photo().id());
            }
            ready.add(replacement);
        }
        return ready;
    }

    private boolean stagedObjectValid(PreviewProfile expected, GeneratedPreview preview) {
        StagedPreview checkpoint = toCheckpoint(expected, preview);
        if (!StringUtils.hasText(checkpoint.stagedObjectKey())
                || !checkpoint.stagedObjectKey().startsWith("thumbnails/generations/")
                || checkpoint.stagedSize() <= 0
                || checkpoint.stagedSize() > MAX_STAGED_PREVIEW_BYTES
                || !Objects.equals(checkpoint.stagedContentType(),
                expectedPreviewContentType(preview.photo()))) {
            return false;
        }

        Optional<ObjectStorageService.ObjectInfo> object;
        try {
            object = storage.find(checkpoint.stagedObjectKey());
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "HEAD 暂存预览对象失败；检查点已保留且数据库尚未切换，photoId="
                            + preview.photo().id(), exception);
        }
        if (object.isEmpty()
                || object.get().size() != checkpoint.stagedSize()
                || object.get().size() <= 0
                || object.get().size() > MAX_STAGED_PREVIEW_BYTES) {
            return false;
        }
        try {
            if (!expected.matches(object.get(), checkpoint.stagedContentType(),
                    checkpoint.stagedSha256())) {
                return false;
            }
        } catch (RuntimeException malformedCheckpoint) {
            return false;
        }

        // An open/read failure is ambiguous (transport failure or an object
        // changing after HEAD). Preserve the checkpoint and stop instead of
        // deleting a potentially valid object or switching the database.
        return checkpoint.stagedSha256().equalsIgnoreCase(sha256(checkpoint));
    }

    private StagedPreview toCheckpoint(PreviewProfile expected, GeneratedPreview preview) {
        return new StagedPreview(preview.photo().id(), expected.fingerprint(),
                preview.photo().objectKey(), preview.objectKey(), preview.size(),
                preview.contentType(), preview.sha256(), null, null);
    }

    private GeneratedPreview generateAndPersist(PreviewPhoto photo, String generation,
                                                PreviewProfile expected, String profile) {
        try {
            // Commit the checkpoint before uploading. A crash can then leave a
            // row whose object is missing (safely regenerated), but never an
            // uploaded object whose cleanup key was lost from the database.
            return generateDirectly(photo, generation, expected,
                    generated -> persistStage(profile, generated));
        } catch (Exception exception) {
            throw new IllegalStateException("重新生成预览图失败，photoId=" + photo.id(), exception);
        }
    }

    private List<StagedPreview> loadStages() {
        return jdbc.sql("""
                SELECT photo_id, profile_fingerprint, source_object_key,
                       staged_object_key, staged_size, staged_content_type,
                       staged_sha256, cleanup_token, cleanup_claimed_at
                FROM preview_regeneration_stage
                ORDER BY photo_id
                """)
                .query((rs, rowNum) -> new StagedPreview(
                        rs.getLong("photo_id"),
                        rs.getString("profile_fingerprint"),
                        rs.getString("source_object_key"),
                        rs.getString("staged_object_key"),
                        rs.getLong("staged_size"),
                        rs.getString("staged_content_type"),
                        rs.getString("staged_sha256"),
                        rs.getString("cleanup_token"),
                        rs.getTimestamp("cleanup_claimed_at") == null ? null
                                : rs.getTimestamp("cleanup_claimed_at").toLocalDateTime()))
                .list();
    }

    private void persistStage(String profile, GeneratedPreview generated) {
        jdbc.sql("""
                INSERT INTO preview_regeneration_stage
                    (photo_id, profile_fingerprint, source_object_key,
                     staged_object_key, staged_size, staged_content_type,
                     staged_sha256)
                VALUES
                    (:photoId, :profile, :sourceKey, :stagedKey, :stagedSize,
                     :contentType, :sha256)
                """)
                .param("profile", profile)
                .param("sourceKey", generated.photo().objectKey())
                .param("stagedKey", generated.objectKey())
                .param("stagedSize", generated.size())
                .param("contentType", generated.contentType())
                .param("sha256", generated.sha256())
                .param("photoId", generated.photo().id())
                .update();
    }

    private String claimStageForCleanup(StagedPreview stage) {
        String token = UUID.randomUUID().toString();
        int claimed = jdbc.sql("""
                UPDATE preview_regeneration_stage
                SET cleanup_token=:token, cleanup_claimed_at=:claimedAt
                WHERE photo_id=:photoId AND profile_fingerprint=:profile
                  AND staged_object_key=:stagedKey
                  AND (
                      cleanup_token IS NULL OR cleanup_claimed_at IS NULL
                      OR cleanup_claimed_at<:staleBefore
                  )
                """)
                .param("token", token)
                .param("claimedAt", LocalDateTime.now())
                .param("staleBefore", LocalDateTime.now().minusHours(1))
                .param("photoId", stage.photoId())
                .param("profile", stage.profileFingerprint())
                .param("stagedKey", stage.stagedObjectKey())
                .update();
        return claimed == 1 ? token : null;
    }

    private boolean deleteClaimedStage(StagedPreview stage, String token) {
        return jdbc.sql("""
                DELETE FROM preview_regeneration_stage
                WHERE photo_id=:photoId AND profile_fingerprint=:profile
                  AND staged_object_key=:stagedKey
                  AND cleanup_token=:token
                """)
                .param("photoId", stage.photoId())
                .param("profile", stage.profileFingerprint())
                .param("stagedKey", stage.stagedObjectKey())
                .param("token", token)
                .update() == 1;
    }

    private void releaseStageCleanupClaim(StagedPreview stage, String token) {
        jdbc.sql("""
                UPDATE preview_regeneration_stage
                SET cleanup_token=NULL, cleanup_claimed_at=NULL
                WHERE photo_id=:photoId AND profile_fingerprint=:profile
                  AND staged_object_key=:stagedKey AND cleanup_token=:token
                """)
                .param("photoId", stage.photoId())
                .param("profile", stage.profileFingerprint())
                .param("stagedKey", stage.stagedObjectKey())
                .param("token", token)
                .update();
    }

    private int discardStage(StagedPreview stage) {
        String cleanupToken = claimStageForCleanup(stage);
        if (cleanupToken == null) {
            // A switch may have locked/deleted this checkpoint, or another
            // instance may already own cleanup. In either case this caller must
            // not touch the object.
            return 0;
        }
        String key = stage.stagedObjectKey();
        boolean deletionAttempted = false;
        try {
            if (!StringUtils.hasText(key) || !key.startsWith("thumbnails/generations/")) {
                log.warn("跳过清理不在预览代际命名空间中的暂存对象：{}", key);
                if (!deleteClaimedStage(stage, cleanupToken)) {
                    throw concurrentStageFailure(stage.photoId());
                }
                return 0;
            }
            int referenced = jdbc.sql("""
                    SELECT COUNT(*)
                    FROM photo
                    WHERE deleted=0 AND (object_key=:key OR thumbnail_object_key=:key)
                    """).param("key", key).query(Integer.class).single();
            if (referenced > 0) {
                log.info("暂存对象已被照片引用，跳过清理：{}", key);
                if (!deleteClaimedStage(stage, cleanupToken)) {
                    throw concurrentStageFailure(stage.photoId());
                }
                return 0;
            }
            deletionAttempted = true;
            storage.delete(key);
            if (!deleteClaimedStage(stage, cleanupToken)) {
                throw concurrentStageFailure(stage.photoId());
            }
            return 1;
        } catch (RuntimeException exception) {
            if (!deletionAttempted) {
                releaseStageCleanupClaim(stage, cleanupToken);
            } else {
                // A failed DeleteObject response is ambiguous: the object may
                // already be gone. Keep the durable claim so no switch can
                // activate this key until the lease expires and cleanup is
                // retried deliberately.
                log.warn("删除暂存预览对象结果不确定，保留 cleanup claim 阻止切换：{}", key);
            }
            if (exception instanceof IllegalStateException
                    && exception.getMessage() != null
                    && exception.getMessage().startsWith("预览图暂存检查点发生并发变化")) {
                throw exception;
            }
            throw new IllegalStateException("删除预览图暂存对象失败，检查点已保留：" + key,
                    exception);
        }
    }

    private IllegalStateException concurrentStageFailure(long photoId) {
        return new IllegalStateException("预览图暂存检查点发生并发变化，photoId=" + photoId);
    }

    private List<PreviewPhoto> loadPhotos() {
        return jdbc.sql("""
                SELECT id, object_key, content_type, thumbnail_object_key, thumbnail_size,
                       version, status
                FROM photo
                WHERE deleted=0
                  AND status IN ('AVAILABLE', 'ARCHIVED')
                  AND object_key IS NOT NULL
                ORDER BY id
                """)
                .query((rs, rowNum) -> new PreviewPhoto(
                        rs.getLong("id"),
                        rs.getString("object_key"),
                        rs.getString("content_type"),
                        rs.getString("thumbnail_object_key"),
                        rs.getObject("thumbnail_size", Long.class),
                        rs.getInt("version"),
                        PhotoStatus.valueOf(rs.getString("status"))))
                .list();
    }

    private List<PreviewPhoto> loadPhotosByIds(List<Long> photoIds) {
        List<Long> uniqueIds = new ArrayList<>(new LinkedHashSet<>(photoIds));
        List<PreviewPhoto> photos = new ArrayList<>();
        for (int offset = 0; offset < uniqueIds.size(); offset += 500) {
            List<Long> chunk = uniqueIds.subList(offset, Math.min(offset + 500, uniqueIds.size()));
            photos.addAll(jdbc.sql("""
                    SELECT id, object_key, content_type, thumbnail_object_key, thumbnail_size,
                           version, status
                    FROM photo
                    WHERE deleted=0
                      AND status IN ('AVAILABLE', 'ARCHIVED')
                      AND object_key IS NOT NULL
                      AND id IN (:ids)
                    ORDER BY id
                    """)
                    .param("ids", chunk)
                    .query((rs, rowNum) -> new PreviewPhoto(
                            rs.getLong("id"),
                            rs.getString("object_key"),
                            rs.getString("content_type"),
                            rs.getString("thumbnail_object_key"),
                            rs.getObject("thumbnail_size", Long.class),
                            rs.getInt("version"),
                            PhotoStatus.valueOf(rs.getString("status"))))
                    .list());
        }
        return photos;
    }

    private PreviewPhoto loadPhoto(long photoId) {
        return jdbc.sql("""
                SELECT id, object_key, content_type, thumbnail_object_key, thumbnail_size,
                       version, status
                FROM photo
                WHERE id=:id AND deleted=0
                  AND status IN ('AVAILABLE', 'ARCHIVED')
                  AND object_key IS NOT NULL
                """)
                .param("id", photoId)
                .query((rs, rowNum) -> new PreviewPhoto(
                        rs.getLong("id"),
                        rs.getString("object_key"),
                        rs.getString("content_type"),
                        rs.getString("thumbnail_object_key"),
                        rs.getObject("thumbnail_size", Long.class),
                        rs.getInt("version"),
                        PhotoStatus.valueOf(rs.getString("status"))))
                .optional()
                .orElse(null);
    }

    private boolean hasCompletePreviewMetadata(PreviewPhoto photo) {
        return StringUtils.hasText(photo.thumbnailObjectKey())
                && photo.thumbnailSize() != null && photo.thumbnailSize() > 0;
    }

    private GeneratedPreview generate(PreviewPhoto photo, String generation,
                                      PreviewProfile expected) {
        try {
            return generateDirectly(photo, generation, expected, null);
        } catch (Exception exception) {
            throw new IllegalStateException("重新生成预览图失败，photoId=" + photo.id(), exception);
        }
    }

    private GeneratedPreview generateDirectly(PreviewPhoto photo, String generation,
                                              PreviewProfile expected,
                                              Consumer<GeneratedPreview> beforeUpload) throws Exception {
        Path taskDirectory = workspace.createTaskDirectory(photo.id());
        try {
            Path source = workspace.taskFile(taskDirectory, "source.img");
            ObjectStorageService.ObjectInfo info = storage.stat(photo.objectKey());
            if (info.size() <= 0 || info.size() > properties.imageMaxBytes()) {
                throw new IllegalArgumentException("预览图来源文件超过图片大小上限");
            }
            try (InputStream input = storage.open(photo.objectKey());
                 OutputStream output = Files.newOutputStream(source)) {
                copyLimited(input, output, properties.imageMaxBytes());
            }
            String contentType = normalizedContentType(source, photo.contentType());
            Path output = workspace.taskFile(taskDirectory,
                    "preview" + ("image/png".equals(contentType) ? ".png" : ".jpg"));
            ImageCompressor.FileResult preview = compressor.thumbnail(
                    source, output, contentType, MAX_DIMENSION,
                    expected.compressionRatio().doubleValue());
            if (preview.size() <= 0 || preview.size() > MAX_STAGED_PREVIEW_BYTES) {
                throw new IllegalArgumentException("生成的预览图超过 20 MiB 安全上限");
            }
            String previewSha256 = sha256(preview.path());
            String previewKey = previewKey(generation, photo.id(), contentType);
            GeneratedPreview generated = new GeneratedPreview(photo, previewKey, preview.size(),
                    preview.contentType(), previewSha256);
            if (beforeUpload != null) beforeUpload.accept(generated);
            try {
                try (InputStream input = Files.newInputStream(preview.path())) {
                    storage.put(previewKey, input, preview.size(), preview.contentType(),
                            expected.objectMetadata(preview.contentType(), previewSha256));
                }
            } catch (Exception exception) {
                cleanupKeys(List.of(previewKey));
                throw exception;
            }
            return generated;
        } finally {
            cleanupTaskDirectory(taskDirectory);
        }
    }

    private void cleanupTaskDirectory(Path directory) {
        try {
            workspace.deleteRecursively(directory);
        } catch (RuntimeException exception) {
            log.warn("清理预览图处理辅助目录失败: {}", directory, exception);
        }
    }

    private List<GeneratedPreview> switchGeneration(PreviewProfile configured,
                                                    PreviewProfileRepository.StoredProfile stored,
                                                    String profile,
                                                    List<GeneratedPreview> generated) {
        // Lock/CAS the singleton profile row before inspecting the live photo
        // set. A RUNNING instance that still holds the old profile must now
        // wait and its upload-completion guard will fail after this transaction
        // commits. If it committed just before we obtained the lock, the set
        // check below detects the newly eligible photo and rolls this switch
        // back so the next checkpointed retry includes it.
        profiles.save(configured, stored);
        requireSameEligiblePhotoSet(generated);
        lockAndValidateSwitchStages(configured, profile, generated);

        List<GeneratedPreview> activated = new ArrayList<>();
        for (GeneratedPreview preview : generated) {
            activated.add(switchOneWithVersionRetry(preview, false, null)
                    .orElseThrow(() -> concurrentSwitchFailure(preview.photo().id())));
        }
        for (GeneratedPreview preview : generated) {
            int deleted = jdbc.sql("""
                    DELETE FROM preview_regeneration_stage
                    WHERE photo_id=:photoId AND profile_fingerprint=:profile
                      AND staged_object_key=:stagedKey AND cleanup_token IS NULL
                    """)
                    .param("photoId", preview.photo().id())
                    .param("profile", profile)
                    .param("stagedKey", preview.objectKey())
                    .update();
            if (deleted != 1) {
                throw new IllegalStateException(
                        "原子切换时预览图暂存检查点发生并发变化，photoId="
                                + preview.photo().id());
            }
        }
        return List.copyOf(activated);
    }

    private void requireSameEligiblePhotoSet(List<GeneratedPreview> generated) {
        List<Long> stagedPhotoIds = generated.stream()
                .map(preview -> preview.photo().id())
                .sorted()
                .toList();
        List<Long> livePhotoIds = jdbc.sql("""
                SELECT id
                FROM photo
                WHERE deleted=0
                  AND status IN ('AVAILABLE', 'ARCHIVED')
                  AND object_key IS NOT NULL
                ORDER BY id
                """).query(Long.class).list();
        if (!livePhotoIds.equals(stagedPhotoIds)) {
            throw new IllegalStateException(
                    "预览图生成快照后可用照片集合发生变化，数据库切换已回滚并将在后台重试");
        }
    }

    private void lockAndValidateSwitchStages(PreviewProfile expectedProfile, String profile,
                                             List<GeneratedPreview> generated) {
        List<StagedPreview> expected = generated.stream()
                .map(preview -> toCheckpoint(expectedProfile, preview))
                .sorted(java.util.Comparator.comparingLong(StagedPreview::photoId))
                .toList();
        List<StagedPreview> locked = jdbc.sql("""
                SELECT photo_id, profile_fingerprint, source_object_key,
                       staged_object_key, staged_size, staged_content_type,
                       staged_sha256, cleanup_token, cleanup_claimed_at
                FROM preview_regeneration_stage
                WHERE profile_fingerprint=:profile AND cleanup_token IS NULL
                ORDER BY photo_id
                FOR UPDATE
                """)
                .param("profile", profile)
                .query((rs, rowNum) -> new StagedPreview(
                        rs.getLong("photo_id"),
                        rs.getString("profile_fingerprint"),
                        rs.getString("source_object_key"),
                        rs.getString("staged_object_key"),
                        rs.getLong("staged_size"),
                        rs.getString("staged_content_type"),
                        rs.getString("staged_sha256"),
                        rs.getString("cleanup_token"),
                        rs.getTimestamp("cleanup_claimed_at") == null ? null
                                : rs.getTimestamp("cleanup_claimed_at").toLocalDateTime()))
                .list();
        if (!locked.equals(expected)) {
            throw new IllegalStateException(
                    "预览图暂存检查点在复验后被其他实例清理或认领，数据库切换已回滚");
        }
    }

    private <T> T executeAtomic(TransactionCallback<T> callback) {
        TransactionTemplate atomic = new TransactionTemplate(
                Objects.requireNonNull(transactions.getTransactionManager(),
                        "transactionManager"));
        // Public entry points reject ambient transactions because object-store
        // writes cannot participate in a database rollback. The short database
        // switch therefore commits before any replaced object is deleted.
        atomic.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return atomic.execute(callback);
    }

    private void requireNoAmbientTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "预览图生成不能在外层数据库事务中执行；请在事务提交后异步触发");
        }
    }

    private boolean switchOne(GeneratedPreview preview, PreviewProfile requiredDatabaseProfile) {
        PreviewPhoto photo = preview.photo();
        String profileGuard = requiredDatabaseProfile == null ? "" : """
                  AND EXISTS (
                      SELECT 1 FROM preview_setting ps
                      WHERE ps.id=1 AND ps.compression_ratio=:requiredRatio
                        AND ps.generator_fingerprint=:requiredGenerator
                  )
                """;
        JdbcClient.StatementSpec statement = jdbc.sql("""
                UPDATE photo
                SET thumbnail_object_key=:key, thumbnail_size=:size,
                    version=version+1, updated_at=CURRENT_TIMESTAMP
                WHERE id=:id AND deleted=0 AND version=:version
                  AND object_key=:objectKey AND status=:status
                """ + profileGuard)
                .param("key", preview.objectKey())
                .param("size", preview.size())
                .param("id", photo.id())
                .param("version", photo.version())
                .param("objectKey", photo.objectKey())
                .param("status", photo.status().name());
        if (requiredDatabaseProfile != null) {
            statement = statement
                    .param("requiredRatio", requiredDatabaseProfile.compressionRatio())
                    .param("requiredGenerator", requiredDatabaseProfile.generatorFingerprint());
        }
        return statement.update() == 1;
    }

    private Optional<GeneratedPreview> switchOneWithVersionRetry(
            GeneratedPreview original, boolean resolveWhenHealthyOrIneligible,
            PreviewProfile requiredDatabaseProfile) {
        GeneratedPreview candidate = original;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (switchOne(candidate, requiredDatabaseProfile)) return Optional.of(candidate);

            if (requiredDatabaseProfile != null && !profiles.matches(requiredDatabaseProfile)) {
                throw new IllegalStateException(
                        "数据库预览图 profile 在定向修复期间发生变化");
            }

            PreviewPhoto current = loadPhoto(original.photo().id());
            if (current == null) {
                if (resolveWhenHealthyOrIneligible) return Optional.empty();
                throw concurrentSwitchFailure(original.photo().id());
            }
            if (resolveWhenHealthyOrIneligible
                    && previewObjectHealthy(current, requiredDatabaseProfile)) {
                return Optional.empty();
            }
            if (!Objects.equals(original.photo().objectKey(), current.objectKey())) {
                throw concurrentSwitchFailure(original.photo().id());
            }
            if (!resolveWhenHealthyOrIneligible
                    && (!Objects.equals(original.photo().thumbnailObjectKey(), current.thumbnailObjectKey())
                    || !Objects.equals(original.photo().thumbnailSize(), current.thumbnailSize()))) {
                throw concurrentSwitchFailure(original.photo().id());
            }
            candidate = new GeneratedPreview(current, original.objectKey(), original.size(),
                    original.contentType(), original.sha256());
        }
        throw concurrentSwitchFailure(original.photo().id());
    }

    private IllegalStateException concurrentSwitchFailure(long photoId) {
        return new IllegalStateException(
                "预览图原子切换遇到持续并发变化，数据库更新已回滚，photoId=" + photoId);
    }

    private int cleanupGenerated(List<GeneratedPreview> generated) {
        return cleanupKeys(generated.stream().map(GeneratedPreview::objectKey).toList());
    }

    private int cleanupReplacedPreviews(List<GeneratedPreview> activated) {
        Set<String> oldKeys = new LinkedHashSet<>();
        for (GeneratedPreview preview : activated) {
            String oldKey = preview.photo().thumbnailObjectKey();
            if (!StringUtils.hasText(oldKey)) continue;
            if (!oldKey.startsWith("thumbnails/")) {
                log.warn("跳过清理非 thumbnails/ 命名空间的旧预览对象：{}", oldKey);
                continue;
            }
            oldKeys.add(oldKey);
        }
        if (oldKeys.isEmpty()) return 0;

        Set<String> activeKeys = activated.stream()
                .map(GeneratedPreview::objectKey)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> referencedKeys = new LinkedHashSet<>(jdbc.sql("""
                SELECT object_key AS referenced_key
                FROM photo
                WHERE deleted=0 AND object_key IS NOT NULL
                UNION
                SELECT thumbnail_object_key AS referenced_key
                FROM photo
                WHERE deleted=0 AND thumbnail_object_key IS NOT NULL
                """).query(String.class).list());
        oldKeys.removeAll(activeKeys);
        oldKeys.removeAll(referencedKeys);
        return cleanupKeys(oldKeys);
    }

    private int cleanupKeys(Iterable<String> keys) {
        int deleted = 0;
        for (String key : keys) {
            try {
                storage.delete(key);
                deleted++;
            } catch (RuntimeException exception) {
                log.warn("清理预览对象失败；启动流程不会自动重试，需要人工或孤儿对象维护任务处理：{}",
                        key, exception);
            }
        }
        return deleted;
    }

    private String normalizedContentType(Path source, String declared) throws Exception {
        byte[] bytes = new byte[8];
        int length;
        try (InputStream input = Files.newInputStream(source)) {
            length = input.read(bytes);
        }
        boolean jpeg = length >= 3 && (bytes[0] & 0xff) == 0xff
                && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
        boolean png = length >= 8 && (bytes[0] & 0xff) == 0x89
                && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47
                && bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a;
        if (jpeg) return "image/jpeg";
        if (png) return "image/png";
        if ("image/jpeg".equals(declared) || "image/png".equals(declared)) return declared;
        throw new IllegalArgumentException("不支持的图片格式");
    }

    private String sha256(Path source) throws Exception {
        try (InputStream input = Files.newInputStream(source)) {
            return sha256(input, Files.size(source));
        }
    }

    private String sha256(StagedPreview stage) {
        try (InputStream input = storage.open(stage.stagedObjectKey())) {
            return sha256(input, stage.stagedSize());
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "读取暂存预览对象进行 SHA-256 校验失败，检查点已保留："
                            + stage.stagedObjectKey(), exception);
        }
    }

    private String sha256(InputStream source, long expectedSize) throws Exception {
        if (expectedSize <= 0 || expectedSize > MAX_STAGED_PREVIEW_BYTES) {
            throw new IllegalArgumentException("暂存预览对象大小超出安全范围");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (DigestInputStream input = new DigestInputStream(source, digest)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > expectedSize || total > MAX_STAGED_PREVIEW_BYTES) {
                    throw new IllegalArgumentException("暂存预览对象实际大小超过检查点记录");
                }
            }
        }
        if (total != expectedSize) {
            throw new IllegalArgumentException("暂存预览对象实际大小与检查点记录不一致");
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void copyLimited(InputStream input, OutputStream output, long limit) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) continue;
            total += read;
            if (total > limit) throw new IllegalArgumentException("图片超过大小上限");
            output.write(buffer, 0, read);
        }
    }

    private String generationId(PreviewProfile profile) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        return timestamp + "-q" + profile.ratioText().replace('.', '-') + "-"
                + UUID.randomUUID().toString().substring(0, 8);
    }

    private String expectedPreviewContentType(PreviewPhoto photo) {
        return "image/png".equals(photo.contentType()) ? "image/png" : "image/jpeg";
    }

    private String previewKey(String generation, long photoId, String contentType) {
        return "thumbnails/generations/" + generation + "/" + photoId
                + ("image/png".equals(contentType) ? ".png" : ".jpg");
    }

    private record PreviewPhoto(Long id, String objectKey, String contentType,
                                String thumbnailObjectKey, Long thumbnailSize,
                                int version, PhotoStatus status) {
    }

    private record StagedPreview(long photoId, String profileFingerprint,
                                 String sourceObjectKey, String stagedObjectKey,
                                 long stagedSize, String stagedContentType,
                                 String stagedSha256, String cleanupToken,
                                 LocalDateTime cleanupClaimedAt) {
        private GeneratedPreview toGenerated(PreviewPhoto photo) {
            return new GeneratedPreview(photo, stagedObjectKey, stagedSize,
                    stagedContentType, stagedSha256);
        }
    }

    private record StageReconciliation(Map<Long, StagedPreview> reusable, int deletedCount) {
    }

    private record GeneratedPreview(PreviewPhoto photo, String objectKey, long size,
                                    String contentType, String sha256) {
    }

    public record Result(boolean regenerated, int deletedCount, int regeneratedCount) {
    }

    public interface ProgressListener {
        ProgressListener NONE = new ProgressListener() {
            @Override
            public void started(int total) {
            }

            @Override
            public void progressed(int processed, int total) {
            }
        };

        void started(int total);

        void progressed(int processed, int total);
    }
}
