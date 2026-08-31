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
import java.util.stream.Stream;

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
            log.info("启动三方核对完成：环境、数据库及 {} 个 OSS 预览对象使用 profile {}，"
                            + "修复 {} 张，回退成品图 {} 张",
                    photos.size(), configured.fingerprint(), repaired.regeneratedCount(),
                    repaired.fallbackCount());
            return new Result(repaired.regenerated(), abandonedDeleted + repaired.deletedCount(),
                    repaired.regeneratedCount(), repaired.fallbackCount());
        }

        Result result = regenerateChangedProfile(configured, stored, loadPhotos(), listener);
        profilePolicy.completeBootstrap(configured);
        return result;
    }

    private Result regenerateChangedProfile(PreviewProfile configured,
                                            PreviewProfileRepository.StoredProfile stored,
                                            List<PreviewPhoto> photos, ProgressListener listener) {
        // An encoder change normally touches one preview format, and the object
        // metadata records the encoder identity per format. Every preview whose
        // object already matches the new profile IS the new generation, so
        // re-encoding it would spend CPU, bandwidth and object-store writes to
        // produce the same bytes. One HEAD per photo separates the two groups;
        // an ambiguous HEAD aborts the round (auditPreviewObjects throws)
        // instead of quietly promoting the photo into a full rebuild.
        List<PreviewPhoto> affected =
                auditPreviewObjects(photos, configured, ProgressListener.NONE);
        Set<Long> affectedIds = new LinkedHashSet<>();
        for (PreviewPhoto photo : affected) affectedIds.add(photo.id());
        List<PreviewPhoto> unchanged = photos.stream()
                .filter(photo -> !affectedIds.contains(photo.id()))
                .toList();
        if (!unchanged.isEmpty()) {
            log.info("预览图代际切换跳过 {} 张已符合新 profile 的照片（无需重新编码与上传），"
                    + "仅重建 {} 张", unchanged.size(), affected.size());
        }

        listener.started(affected.size());
        String profile = configured.fingerprint();
        String generation = generationId(configured);
        StageReconciliation reconciliation = reconcileStages(profile, affected);
        List<GeneratedPreview> staged = new ArrayList<>(affected.size());
        List<PreviewPhoto> unusable = new ArrayList<>();
        RuntimeException firstFailure = null;

        for (int index = 0; index < affected.size(); index++) {
            PreviewPhoto photo = affected.get(index);
            StagedPreview checkpoint = reconciliation.reusable().get(photo.id());
            try {
                if (checkpoint != null) {
                    staged.add(checkpoint.toGenerated(photo));
                } else {
                    staged.add(generateAndPersist(photo, generation,
                            configured, profile));
                }
            } catch (PreviewSourceUnusableException unusableSource) {
                // One undecodable source must not block the whole library. The
                // switch below clears this photo's preview reference in the same
                // transaction so it renders the finished object, and the
                // scheduled reconciliation retries it later.
                unusable.add(photo);
                log.warn("原图无法生成新一代预览图，该照片将回退为直接使用成品图展示：photoId={}",
                        photo.id(), unusableSource);
            } catch (RuntimeException exception) {
                if (firstFailure == null) firstFailure = exception;
                log.error("暂存新一代预览图失败，继续保存其他照片的检查点：photoId={}",
                        photo.id(), exception);
            } finally {
                listener.progressed(index + 1, affected.size());
            }
        }
        if (firstFailure != null) {
            throw new IllegalStateException(
                    "部分预览图暂存失败；已成功暂存的照片将在下次任务中直接复用", firstFailure);
        }

        // Every checkpoint, including objects uploaded by this invocation, is
        // HEAD-checked and streamed for an exact digest before it can
        // participate in the database switch.
        staged = validateStagedPreviews(configured, generation, staged, unusable);

        List<GeneratedPreview> ready = List.copyOf(staged);
        List<PreviewPhoto> cleared = List.copyOf(unusable);
        List<GeneratedPreview> activated = executeAtomic(
                status -> switchGeneration(configured, stored, profile, ready, cleared,
                        unchanged));
        if (activated == null) {
            throw new IllegalStateException("预览图数据库切换未返回结果；暂存检查点已保留");
        }

        List<PreviewPhoto> replaced = new ArrayList<>(cleared);
        activated.forEach(preview -> replaced.add(preview.photo()));
        int deleted = reconciliation.deletedCount() + cleanupReplacedPreviews(replaced,
                activated.stream().map(GeneratedPreview::objectKey)
                        .collect(java.util.stream.Collectors.toSet()));
        log.info("预览图已原子切换到新版本 {}：压缩比率 {}，激活 {} 张，沿用 {} 张，"
                        + "回退成品图 {} 张，清理对象 {} 个",
                generation, configured.ratioText(), activated.size(), unchanged.size(),
                cleared.size(), deleted);
        return new Result(true, deleted, activated.size(), cleared.size());
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
            return new Result(false, 0, 0, 0);
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
            return new Result(false, 0, 0, 0);
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
        // Judge the object by its own MIME, not by photo.content_type. That column
        // describes the source and can be wrong — legacy migration copies the old
        // system's mime_type verbatim and falls back to application/octet-stream —
        // while the preview format follows the finished object's real bytes. Using
        // the column would mark a perfectly good preview unhealthy on every pass.
        String objectContentType = object.get().contentType();
        if (!supportedPreviewContentType(objectContentType)) return false;
        return object.get().size() == photo.thumbnailSize()
                && object.get().size() <= MAX_STAGED_PREVIEW_BYTES
                && expected.matches(object.get(), objectContentType);
    }

    private Result repairUnhealthyPreviews(PreviewProfile expected, List<PreviewPhoto> unhealthy,
                                           ProgressListener listener) {
        listener.started(unhealthy.size());
        if (unhealthy.isEmpty()) {
            listener.progressed(0, 0);
            return new Result(false, 0, 0, 0);
        }
        String generation = generationId(expected);
        List<GeneratedPreview> activated = new ArrayList<>();
        int stagedDeleted = 0;
        int fallbackCount = 0;
        int failureCount = 0;
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
                // A single failure must not abort the round. Aborting used to
                // leave the startup audit without completeBootstrap(), pinning
                // the profile policy in BOOTSTRAPPING for the whole process.
                failureCount++;
                if (dropReferenceToDeadPreview(photo, expected)) fallbackCount++;
                log.error("定向修复预览图失败，该照片按现状展示并等待后续重试：photoId={}",
                        photo.id(), exception);
            } finally {
                listener.progressed(index + 1, unhealthy.size());
            }
        }

        int oldDeleted = cleanupReplacedPreviews(
                activated.stream().map(GeneratedPreview::photo).toList(),
                activated.stream().map(GeneratedPreview::objectKey)
                        .collect(java.util.stream.Collectors.toSet()));
        if (failureCount > 0) {
            log.warn("预览图定向修复有 {} 张失败（其中 {} 张已改为回退成品图展示）；"
                            + "成功修复的照片不会回滚，失败的会在后续对账中重试",
                    failureCount, fallbackCount);
        }
        log.info("预览图元数据定向修复完成：目标 {} 张，激活 {} 张，回退成品图 {} 张，清理对象 {} 个",
                unhealthy.size(), activated.size(), fallbackCount, stagedDeleted + oldDeleted);
        return new Result(!activated.isEmpty(), stagedDeleted + oldDeleted, activated.size(),
                fallbackCount);
    }

    /**
     * Clears a preview reference whose object is <em>confirmed</em> absent so the
     * gallery falls back to the finished object instead of rendering a broken
     * image. A reference whose object still exists is kept even when its profile
     * metadata is stale: a slightly off-ratio thumbnail beats downloading the
     * full finished object on every gallery tile. An ambiguous HEAD keeps the
     * reference too — only a definite not-found justifies the downgrade.
     */
    private boolean dropReferenceToDeadPreview(PreviewPhoto photo, PreviewProfile expected) {
        try {
            if (StringUtils.hasText(photo.thumbnailObjectKey())
                    && storage.find(photo.thumbnailObjectKey()).isPresent()) {
                return false;
            }
        } catch (RuntimeException headFailure) {
            log.warn("无法确认预览对象是否存在，保留原引用等待下次对账：photoId={}, objectKey={}",
                    photo.id(), photo.thumbnailObjectKey(), headFailure);
            return false;
        }
        if (!StringUtils.hasText(photo.thumbnailObjectKey()) && photo.thumbnailSize() == null) {
            // Already the fallback state; nothing to clear.
            return true;
        }
        boolean cleared = Boolean.TRUE.equals(executeAtomic(status -> {
            if (!profiles.matches(expected)) return false;
            return clearPreview(photo);
        }));
        if (!cleared) {
            log.warn("清空失效预览引用未命中（并发变化或 profile 已切换），保留现状：photoId={}",
                    photo.id());
        }
        return cleared;
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
            List<GeneratedPreview> staged, List<PreviewPhoto> unusable) {
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
            } catch (PreviewSourceUnusableException sourceFailure) {
                // The checkpoint object is gone and the source has since become
                // undecodable. Without this branch every later round would abort
                // on the same photo forever.
                unusable.add(preview.photo());
                log.warn("暂存预览对象缺失且原图已无法重新编码，该照片将回退为直接使用成品图展示："
                        + "photoId={}", preview.photo().id(), sourceFailure);
                continue;
            } catch (RuntimeException exception) {
                throw new IllegalStateException(
                        "暂存预览对象明确缺失或校验异常，安全重建失败；数据库尚未切换，photoId="
                                + preview.photo().id(), exception);
            }
            if (!stagedObjectValid(expected, replacement)) {
                // Do NOT fall back here. Now that the preview MIME is judged by
                // the object's own bytes, a freshly generated object can only
                // fail this check for storage-side reasons (metadata dropped,
                // bytes corrupted, object missing right after PUT) — which affect
                // every photo, not this one. Keep the checkpoint and abort so a
                // storage misconfiguration cannot silently clear the whole
                // library's preview references. The reason is in the log above.
                throw new IllegalStateException(
                        "重建后的暂存预览对象再次校验失败；检查点已保留且数据库尚未切换，photoId="
                                + preview.photo().id());
            }
            ready.add(replacement);
        }
        return ready;
    }

    /**
     * Definitive verdict on one staged preview object. Returns {@code false} only
     * when the object is provably unacceptable; every ambiguous outcome (HEAD or
     * read failure) throws instead, so callers may safely treat {@code false} as
     * "this object will never become valid on its own".
     *
     * <p>Each rejection is logged with its reason — a silent boolean here once
     * cost a production log hunt to explain a permanently stalled rebuild.</p>
     */
    private boolean stagedObjectValid(PreviewProfile expected, GeneratedPreview preview) {
        StagedPreview checkpoint = toCheckpoint(expected, preview);
        long photoId = preview.photo().id();
        if (!StringUtils.hasText(checkpoint.stagedObjectKey())
                || !checkpoint.stagedObjectKey().startsWith("thumbnails/generations/")) {
            log.warn("暂存预览对象 key 不在预览代际命名空间内：photoId={}, key={}",
                    photoId, checkpoint.stagedObjectKey());
            return false;
        }
        if (checkpoint.stagedSize() <= 0
                || checkpoint.stagedSize() > MAX_STAGED_PREVIEW_BYTES) {
            log.warn("暂存预览对象大小超出安全范围：photoId={}, size={}",
                    photoId, checkpoint.stagedSize());
            return false;
        }
        // Only require a supported preview MIME. Comparing against a MIME derived
        // from photo.content_type used to be the check here, and it deadlocked the
        // whole rebuild: that column describes the source and is not trustworthy
        // (legacy migration copies the old system's mime_type verbatim, falling
        // back to application/octet-stream), while the preview format is decided
        // by the finished object's real bytes. A legacy PNG whose column said
        // anything other than exactly image/png could never pass, and regenerating
        // produced the same MIME, so every retry failed identically forever.
        if (!supportedPreviewContentType(checkpoint.stagedContentType())) {
            log.warn("暂存预览对象 MIME 不是受支持的预览类型：photoId={}, contentType={}",
                    photoId, checkpoint.stagedContentType());
            return false;
        }

        Optional<ObjectStorageService.ObjectInfo> object;
        try {
            object = storage.find(checkpoint.stagedObjectKey());
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "HEAD 暂存预览对象失败；检查点已保留且数据库尚未切换，photoId="
                            + photoId, exception);
        }
        if (object.isEmpty()) {
            log.warn("暂存预览对象不存在：photoId={}, key={}", photoId,
                    checkpoint.stagedObjectKey());
            return false;
        }
        if (object.get().size() != checkpoint.stagedSize()
                || object.get().size() <= 0
                || object.get().size() > MAX_STAGED_PREVIEW_BYTES) {
            log.warn("暂存预览对象实际大小与检查点不一致：photoId={}, actual={}, checkpoint={}",
                    photoId, object.get().size(), checkpoint.stagedSize());
            return false;
        }
        try {
            if (!expected.matches(object.get(), checkpoint.stagedContentType(),
                    checkpoint.stagedSha256())) {
                log.warn("暂存预览对象的 MIME 或 profile 元数据与本轮 profile 不一致："
                                + "photoId={}, objectContentType={}, stagedContentType={}, "
                                + "expectedRatio={}",
                        photoId, object.get().contentType(),
                        checkpoint.stagedContentType(), expected.ratioText());
                return false;
            }
        } catch (RuntimeException malformedCheckpoint) {
            log.warn("暂存预览对象 profile 元数据格式非法：photoId={}", photoId, malformedCheckpoint);
            return false;
        }

        // An open/read failure is ambiguous (transport failure or an object
        // changing after HEAD). Preserve the checkpoint and stop instead of
        // deleting a potentially valid object or switching the database.
        if (!checkpoint.stagedSha256().equalsIgnoreCase(sha256(checkpoint))) {
            log.warn("暂存预览对象内容摘要与检查点不一致：photoId={}, key={}",
                    photoId, checkpoint.stagedObjectKey());
            return false;
        }
        return true;
    }

    /** The only format the preview encoder emits. */
    private boolean supportedPreviewContentType(String contentType) {
        return PreviewProfile.PREVIEW_CONTENT_TYPE.equals(contentType);
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
        } catch (PreviewSourceUnusableException unusable) {
            throw unusable;
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
        } catch (PreviewSourceUnusableException unusable) {
            throw unusable;
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

            // Only decoding and encoding this one source is allowed to degrade a
            // single photo to "no preview". Everything outside this block talks
            // to the object store, and a storage outage must abort the whole
            // round instead of clearing preview references library-wide.
            String contentType;
            ImageCompressor.FileResult preview;
            try {
                // The sniffed type describes the SOURCE and decides how it is
                // decoded; the preview is always re-encoded into
                // PreviewProfile.PREVIEW_CONTENT_TYPE.
                contentType = normalizedContentType(source, photo.contentType());
                Path output = workspace.taskFile(taskDirectory,
                        "preview" + ImageCompressor.PREVIEW_EXTENSION);
                preview = compressor.thumbnail(source, output, contentType, MAX_DIMENSION,
                        expected.compressionRatio().doubleValue());
                if (preview.size() <= 0 || preview.size() > MAX_STAGED_PREVIEW_BYTES) {
                    throw new IllegalArgumentException("生成的预览图超过 20 MiB 安全上限");
                }
            } catch (Exception encodingFailure) {
                throw new PreviewSourceUnusableException(photo.id(), encodingFailure);
            }

            String previewSha256 = sha256(preview.path());
            String previewKey = previewKey(generation, photo.id());
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
                                                    List<GeneratedPreview> generated,
                                                    List<PreviewPhoto> cleared,
                                                    List<PreviewPhoto> unchanged) {
        // Lock/CAS the singleton profile row before inspecting the live photo
        // set. A RUNNING instance that still holds the old profile must now
        // wait and its upload-completion guard will fail after this transaction
        // commits. If it committed just before we obtained the lock, the set
        // check below detects the newly eligible photo and rolls this switch
        // back so the next checkpointed retry includes it.
        profiles.save(configured, stored);
        requireSameEligiblePhotoSet(generated, cleared, unchanged);
        lockAndValidateSwitchStages(configured, profile, generated);

        List<GeneratedPreview> activated = new ArrayList<>();
        for (GeneratedPreview preview : generated) {
            activated.add(switchOneWithVersionRetry(preview, false, null)
                    .orElseThrow(() -> concurrentSwitchFailure(preview.photo().id())));
        }
        // Undecodable sources join the same atomic switch with a null preview so
        // the library never mixes generations: they render the finished object
        // until a later repair pass succeeds.
        for (PreviewPhoto photo : cleared) {
            clearPreviewWithVersionRetry(photo);
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

    /**
     * Every live photo must be accounted for before the switch, so the library
     * can never end up straddling two generations. A photo counts as accounted
     * when this round regenerated it, cleared it, or verified by HEAD that its
     * existing object already carries the new profile ({@code unchanged}).
     */
    private void requireSameEligiblePhotoSet(List<GeneratedPreview> generated,
                                             List<PreviewPhoto> cleared,
                                             List<PreviewPhoto> unchanged) {
        List<Long> accountedPhotoIds = Stream.concat(
                        Stream.concat(
                                generated.stream().map(preview -> preview.photo().id()),
                                cleared.stream().map(PreviewPhoto::id)),
                        unchanged.stream().map(PreviewPhoto::id))
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
        if (!livePhotoIds.equals(accountedPhotoIds)) {
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

    /**
     * Drops a photo's preview reference under the same CAS discipline as {@link
     * #switchOne}. The gallery then renders the finished object until a later
     * repair pass produces a real preview.
     */
    private boolean clearPreview(PreviewPhoto photo) {
        return jdbc.sql("""
                UPDATE photo
                SET thumbnail_object_key=NULL, thumbnail_size=NULL,
                    version=version+1, updated_at=CURRENT_TIMESTAMP
                WHERE id=:id AND deleted=0 AND version=:version
                  AND object_key=:objectKey AND status=:status
                """)
                .param("id", photo.id())
                .param("version", photo.version())
                .param("objectKey", photo.objectKey())
                .param("status", photo.status().name())
                .update() == 1;
    }

    private void clearPreviewWithVersionRetry(PreviewPhoto photo) {
        PreviewPhoto candidate = photo;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (clearPreview(candidate)) return;

            PreviewPhoto current = loadPhoto(photo.id());
            if (current == null
                    || !Objects.equals(photo.objectKey(), current.objectKey())) {
                throw concurrentSwitchFailure(photo.id());
            }
            if (!StringUtils.hasText(current.thumbnailObjectKey())
                    && current.thumbnailSize() == null) {
                // Another instance already cleared it; the intended end state
                // holds, so the switch may continue.
                return;
            }
            candidate = current;
        }
        throw concurrentSwitchFailure(photo.id());
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

    /**
     * Deletes the previews that {@code replaced} photos referenced before the
     * switch. Callers pass every photo whose reference changed — both photos
     * that received a new preview and photos whose reference was cleared.
     *
     * @param activeKeys keys just activated by this switch; never deleted
     */
    private int cleanupReplacedPreviews(List<PreviewPhoto> replaced, Set<String> activeKeys) {
        Set<String> oldKeys = new LinkedHashSet<>();
        for (PreviewPhoto photo : replaced) {
            String oldKey = photo.thumbnailObjectKey();
            if (!StringUtils.hasText(oldKey)) continue;
            if (!oldKey.startsWith("thumbnails/")) {
                log.warn("跳过清理非 thumbnails/ 命名空间的旧预览对象：{}", oldKey);
                continue;
            }
            oldKeys.add(oldKey);
        }
        if (oldKeys.isEmpty()) return 0;

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

    private String previewKey(String generation, long photoId) {
        return "thumbnails/generations/" + generation + "/" + photoId
                + ImageCompressor.PREVIEW_EXTENSION;
    }

    /**
     * Raised when this one source cannot be decoded or re-encoded. It is the
     * only failure that may degrade a single photo to "no preview"; the gallery
     * then falls back to the finished object. Storage failures must never be
     * reported as this type — they abort the whole round and keep checkpoints.
     */
    static final class PreviewSourceUnusableException extends RuntimeException {
        private PreviewSourceUnusableException(long photoId, Throwable cause) {
            super("预览图来源无法解码或编码，该照片将回退为直接使用成品图展示，photoId=" + photoId,
                    cause);
        }
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

    /**
     * @param fallbackCount photos left without a preview because their source
     *                      could not be encoded. They render the finished object
     *                      until a later repair pass succeeds.
     */
    public record Result(boolean regenerated, int deletedCount, int regeneratedCount,
                         int fallbackCount) {
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
