package cn.photolib.photo;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.photo.mapper.PhotoMapper;
import cn.photolib.photo.model.PhotoEntity;
import cn.photolib.photo.model.PhotoStatus;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.storage.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * 选片页里的「裁切 / 旋转」保存（issue #94）。
 *
 * <p>编辑在浏览器里用 canvas 完成，这里只负责把交上来的新字节变成这张照片的成品图。
 * 按需求，编辑是**就地替换**：不产生第二条图片记录，被引记录、精选条目、分享链接
 * 和相册归属全都继续指向同一个 photo id。</p>
 *
 * <h2>为什么必须换一批对象 key</h2>
 * <p>替换的是成品图的字节，而成品图的地址是按固定签名窗口签出来的
 * （AGENTS.md §2.3），同一个 key 在窗口内签出的 URL 逐字节相同，浏览器和
 * 中间缓存里正躺着旧画面。复用 key 的结果是「保存成功了，但看到的还是旧图」，
 * 而且要等缓存过期才自愈。所以成品图、预览图各拿一个全新的 UUID key，
 * 提交成功之后再删掉旧对象。</p>
 *
 * <h2>失败时照片必须原封不动</h2>
 * <p>整个流程只有最后一步碰 {@code photo} 那一行，而且是一次带版本条件的 CAS。
 * 在那之前（下载、魔数校验、哈希复核、压缩、生成预览、上传）任何一步抛出，
 * 数据库和旧对象都没被动过，用户看到一句错误、照片还是原来那张。
 * 反过来，如果先把 {@code object_key} 改成新 key 再去处理，处理一失败照片就
 * 指向一个不存在的对象——相册里出现一张打不开的图，而且没有任何东西会去修它。</p>
 *
 * <h2>为什么是同步等待</h2>
 * <p>压缩仍旧排在全站唯一的 {@link NativeImageTaskPool} 单线程上（不绕开它是为了
 * 不让几张相机原图同时解码撑爆小内存机器），但这里等它跑完再返回：选片是交互动作，
 * 用户点了「保存」就要立刻看到新图。等待上限 {@link #APPLY_TIMEOUT}，超时只意味着
 * 「前面排的队太长」——任务仍会跑完并提交，所以提示里让用户稍后刷新，而不是重试。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PhotoImageEditService {
    /** 浏览器直传的落点前缀。与单张上传的 {@code temporary/photos/} 分开，便于排查和清理。 */
    static final String SOURCE_PREFIX = "temporary/photo-edits/";
    /**
     * 只接受本服务自己签发过的形状。key 由客户端回传（省掉一张只为记住它的表），
     * 因此必须按形状收口：没有这条正则，调用方就能把任意对象指成编辑来源。
     */
    private static final Pattern SOURCE_KEY = Pattern.compile(
            "^" + SOURCE_PREFIX + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png)$");
    private static final Pattern SHA256 = Pattern.compile("^[a-fA-F0-9]{64}$");
    private static final Duration APPLY_TIMEOUT = Duration.ofMinutes(3);

    private final PhotoService photos;
    private final PhotoMapper photoMapper;
    private final ObjectStorageService storage;
    private final StorageProperties properties;
    private final NativeImageTaskPool nativeTasks;
    private final PhotoProcessingWorkspace workspace;
    private final PreviewProfilePolicy previewProfiles;
    private final TransactionTemplate transactions;

    /**
     * 为一次编辑签发直传地址。
     *
     * <p>不落库：{@link #apply} 会重新校验体积、魔数和哈希，中途放弃的编辑只会在
     * {@code temporary/} 下留一个孤儿对象，与放弃的普通上传同一性质。</p>
     */
    public EditTicket ticket(Long photoId, String contentType, long size, AuthenticatedUser user) {
        PhotoEntity photo = requireEditable(photoId);
        String extension = extensionOf(contentType);
        photos.requireSupportedImage("edited." + extension, contentType, size);
        String sourceKey = SOURCE_PREFIX + UUID.randomUUID() + "." + extension;
        ObjectStorageService.SignedUrl signed =
                storage.presignPut(sourceKey, contentType, properties.uploadUrlTtl());
        log.debug("签发图片编辑上传地址: photoId={}, operator={}", photo.getId(), user.id());
        return new EditTicket(photo.getId(), sourceKey, signed.url().toString(), signed.method(),
                contentType, signed.expiresAt());
    }

    /** 用已经直传上去的编辑结果替换成品图，返回替换之后的图片视图。 */
    public PhotoService.PhotoView apply(Long photoId, ApplyEdit command, AuthenticatedUser user) {
        PhotoEntity photo = requireEditable(photoId);
        if (!SOURCE_KEY.matcher(command.sourceObjectKey() == null ? "" : command.sourceObjectKey()).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "编辑来源地址无效，请重新保存一次");
        }
        if (command.sha256() == null || !SHA256.matcher(command.sha256()).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "编辑结果的校验值无效");
        }
        String extension = extensionOf(command.contentType());
        if (!command.sourceObjectKey().endsWith("." + extension)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "编辑来源与声明的图片类型不一致");
        }
        photos.requireSupportedImage("edited." + extension, command.contentType(), command.size());
        String sha256 = command.sha256().toLowerCase();
        photos.requireUniqueSha256(sha256, photo.getId());

        ObjectStorageService.ObjectInfo info = storage.stat(command.sourceObjectKey());
        if (info.size() <= 0 || info.size() > properties.imageMaxBytes()) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "编辑结果为空或超过 100 MiB");
        }

        try {
            nativeTasks.submit(compressor -> {
                replace(photo, command, sha256, extension, compressor);
                return null;
            }).get(APPLY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "图片处理排队较久，编辑仍在后台完成，请稍后刷新查看");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "编辑保存被中断，请重试");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof BusinessException business) throw business;
            log.error("图片编辑保存失败: photoId={}", photo.getId(), cause);
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "编辑结果没能保存：" + (cause.getMessage() == null ? "图片处理失败" : cause.getMessage()));
        }
        return photos.viewOf(photos.requireEntity(photo.getId()), user);
    }

    /**
     * 在原生线程上完成「下载 → 校验 → 压缩 → 生成预览 → 上传 → CAS 提交 → 删旧对象」。
     * 提交之前不碰数据库，提交失败不删任何旧对象。
     */
    private void replace(PhotoEntity photo, ApplyEdit command, String sha256, String extension,
                         ImageCompressor compressor) throws Exception {
        Path taskDirectory = null;
        try {
            taskDirectory = workspace.createTaskDirectory(photo.getId());
            Path source = workspace.taskFile(taskDirectory, "edited." + extension);
            download(command.sourceObjectKey(), source);
            long sourceSize = Files.size(source);
            if (sourceSize <= 0 || sourceSize > properties.imageMaxBytes()) {
                throw new IllegalArgumentException("编辑结果为空或超过 100 MiB");
            }
            PhotoProcessingService.validateMagic(source, command.contentType());
            if (!PhotoProcessingService.sha256(source).equalsIgnoreCase(sha256)) {
                throw new IllegalArgumentException("编辑结果 SHA-256 校验失败");
            }

            Path processedPath = workspace.taskFile(taskDirectory, "processed." + extension);
            ImageCompressor.FileResult result = compressor.compress(
                    source, processedPath, command.contentType(), properties.imageTargetBytes());
            if (result.size() > properties.imageTargetBytes()) {
                throw new IllegalArgumentException("编辑结果无法在保留最低可用尺寸的同时压缩至 10 MiB");
            }
            // 成品图哈希记的是「进入压缩之前的字节」，与单张上传一致（那里记的是浏览器
            // 提交的原图哈希），所以全库查重和这一列对得上。
            String finishedKey = "photos/" + LocalDateTime.now().getYear() + "/"
                    + UUID.randomUUID() + "." + extension;
            upload(finishedKey, result.path(), result.size(), result.contentType());

            PreviewProfilePolicy.CommitPermit permit = previewProfiles.permitForNewPreview();
            Path thumbnailPath = workspace.taskFile(taskDirectory,
                    "thumbnail" + ImageCompressor.PREVIEW_EXTENSION);
            String previewKey = null;
            Long previewSize = null;
            try {
                ImageCompressor.FileResult thumbnail = compressor.thumbnail(
                        result.path(), thumbnailPath, result.contentType(), 480,
                        permit.profile().compressionRatio().doubleValue());
                // 前缀必须是 thumbnails/generations/，预览对账和定向修复都按它识别预览对象。
                String candidateKey = "thumbnails/generations/edits/" + UUID.randomUUID()
                        + ImageCompressor.PREVIEW_EXTENSION;
                upload(candidateKey, thumbnail.path(), thumbnail.size(), thumbnail.contentType(),
                        permit.profile().objectMetadata(thumbnail.contentType(),
                                PhotoProcessingService.sha256(thumbnail.path())));
                previewKey = candidateKey;
                previewSize = thumbnail.size();
            } catch (Exception previewFailure) {
                // 与上传流程同样的取舍：成品图已经就位，照片可用；预览留空，
                // 图库回退成品图展示，预览修复流程随后补上。
                log.error("编辑结果的预览图生成失败，该照片将回退为直接使用成品图展示: photoId={}",
                        photo.getId(), previewFailure);
            }

            String oldFinishedKey = photo.getObjectKey();
            String oldPreviewKey = photo.getThumbnailObjectKey();
            String oldOriginalKey = photo.getOriginalObjectKey();
            String newPreviewKey = previewKey;
            Long newPreviewSize = previewSize;
            Integer updated = transactions.execute(status -> {
                if (!previewProfiles.lockAndValidateForCommit(permit)) return 0;
                return photoMapper.replaceFinishedImageWithProfileGuard(
                        photo.getId(), photo.getVersion(), finishedKey, command.sourceObjectKey(),
                        command.contentType(), sha256, result.size(), result.width(), result.height(),
                        newPreviewKey, newPreviewSize,
                        LocalDateTime.now().plus(properties.originalRetention()),
                        permit.profile().compressionRatio(), permit.profile().generatorFingerprint(),
                        permit.bootstrappingFlag(), permit.observedDatabaseProfileFlag(),
                        permit.observedCompressionRatioOrTarget(),
                        permit.observedGeneratorFingerprintOrTarget(), LocalDateTime.now());
            });
            if (updated == null || updated != 1) {
                // 提交没成，刚传上去的两个对象就是垃圾，顺手清掉；照片一个字节都没改。
                deleteQuietly(photo.getId(), "新成品图", finishedKey);
                deleteQuietly(photo.getId(), "新预览图", newPreviewKey);
                throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                        "图片或预览设置在保存期间发生了变化，请刷新后重新编辑");
            }
            // 到这里数据库已经指向新对象，旧对象删失败只是留下垃圾，不能让整次编辑报错。
            deleteQuietly(photo.getId(), "旧成品图", oldFinishedKey);
            deleteQuietly(photo.getId(), "旧预览图", oldPreviewKey);
            deleteQuietly(photo.getId(), "旧原图", oldOriginalKey);
            log.info("图片编辑已替换成品图: photoId={}, objectKey={}", photo.getId(), finishedKey);
        } finally {
            cleanupTaskDirectory(taskDirectory);
        }
    }

    private PhotoEntity requireEditable(Long photoId) {
        PhotoEntity photo = photos.requireEntity(photoId);
        if (photo.getStatus() != PhotoStatus.AVAILABLE) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "只有可用状态的图片可以编辑");
        }
        return photo;
    }

    private String extensionOf(String contentType) {
        if ("image/png".equals(contentType)) return "png";
        if ("image/jpeg".equals(contentType)) return "jpg";
        throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE, "仅支持 JPG 和 PNG");
    }

    private void download(String objectKey, Path destination) throws Exception {
        try (InputStream input = storage.open(objectKey);
             OutputStream output = Files.newOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > properties.imageMaxBytes()) {
                    throw new IllegalArgumentException("编辑结果超过 100 MiB");
                }
                output.write(buffer, 0, read);
            }
        }
    }

    private void upload(String objectKey, Path source, long size, String contentType) throws Exception {
        upload(objectKey, source, size, contentType, java.util.Map.of());
    }

    private void upload(String objectKey, Path source, long size, String contentType,
                        java.util.Map<String, String> userMetadata) throws Exception {
        try (InputStream input = Files.newInputStream(source)) {
            storage.put(objectKey, input, size, contentType, userMetadata);
        }
    }

    private void deleteQuietly(Long photoId, String what, String objectKey) {
        if (!StringUtils.hasText(objectKey)) return;
        try {
            storage.delete(objectKey);
        } catch (RuntimeException exception) {
            log.warn("图片编辑后清理{}失败（photoId={}, objectKey={}），需要人工或对账任务处理",
                    what, photoId, objectKey, exception);
        }
    }

    private void cleanupTaskDirectory(Path directory) {
        if (directory == null) return;
        try {
            workspace.deleteRecursively(directory);
        } catch (RuntimeException exception) {
            log.warn("清理图片编辑辅助目录失败: {}", directory, exception);
        }
    }

    /** @param sourceObjectKey 浏览器把编辑结果直传到这里，{@link #apply} 要原样回传 */
    public record EditTicket(Long photoId, String sourceObjectKey, String uploadUrl, String method,
                             String contentType, java.time.Instant expiresAt) {
    }

    public record ApplyEdit(String sourceObjectKey, String contentType, long size, String sha256) {
    }
}
