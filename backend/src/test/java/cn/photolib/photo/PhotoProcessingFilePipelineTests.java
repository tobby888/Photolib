package cn.photolib.photo;

import cn.photolib.audit.AuditLogMapper;
import cn.photolib.common.upload.UnprocessableImageException;
import cn.photolib.photo.batch.PhotoUploadBatchMapper;
import cn.photolib.photo.batch.PhotoUploadItemMapper;
import cn.photolib.photo.mapper.PhotoMapper;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.storage.StorageProperties;
import cn.photolib.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class PhotoProcessingFilePipelineTests {
    private static final long USER_ID = 93901L;
    private static final long PHOTO_ID = 93902L;
    private static final String BATCH_ID = "batch-file-pipeline";
    private static final String ORIGINAL_KEY = "temporary/batches/batch-file-pipeline/original.jpg";
    private static final String PHOTO_KEY = "photos/2026/file-pipeline.jpg";
    private static final String THUMBNAIL_KEY = "thumbnails/generations/uploads/93902.webp";

    @Autowired
    private PhotoProcessingService processing;
    @Autowired
    private PhotoProcessingWorkspace workspace;
    @Autowired
    private ObjectStorageService storage;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ImageCompressor compressor;
    @Autowired
    private PhotoMapper photoMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private StorageProperties properties;
    @Autowired
    private PhotoUploadItemMapper batchItemMapper;
    @Autowired
    private PhotoUploadBatchMapper batchMapper;
    @Autowired
    private PreviewProfilePolicy previewProfiles;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private AuditLogMapper auditLogMapper;
    @Autowired
    @Qualifier("photoProcessingExecutor")
    private ThreadPoolTaskExecutor processingExecutor;

    @Test
    void processesZipLocalFileUploadsAllObjectsAndDeletesAuxiliaryFiles() throws Exception {
        Path source = workspace.createBatchFile(BATCH_ID, ".jpg");
        writeJpeg(source);
        long sourceSize = Files.size(source);
        insertRows(source, sourceSize);

        try {
            processing.submit(PHOTO_ID).get(30, TimeUnit.SECONDS);

            var photo = jdbc.sql("""
                    SELECT status, size, width, height, thumbnail_size, original_delete_after, failure_reason
                    FROM photo WHERE id=:id
                    """).param("id", PHOTO_ID)
                    .query((rs, rowNum) -> new Object[]{
                            rs.getString("status"), rs.getLong("size"),
                            rs.getInt("width"), rs.getInt("height"),
                            rs.getLong("thumbnail_size"), rs.getTimestamp("original_delete_after"),
                            rs.getString("failure_reason")})
                    .single();
            assertThat(photo[0]).as("failure_reason=%s", photo[6]).isEqualTo("AVAILABLE");
            assertThat((long) photo[1]).isEqualTo(storage.stat(PHOTO_KEY).size());
            assertThat((int) photo[2]).isEqualTo(1200);
            assertThat((int) photo[3]).isEqualTo(800);
            ObjectStorageService.ObjectInfo thumbnail = storage.stat(THUMBNAIL_KEY);
            assertThat((long) photo[4]).isEqualTo(thumbnail.size());
            assertThat(thumbnail.userMetadata())
                    .containsEntry(PreviewProfile.METADATA_RATIO, "0.6000")
                    .containsEntry(PreviewProfile.METADATA_EFFECTIVE_QUALITY, "60")
                    .containsEntry(PreviewProfile.METADATA_GENERATOR,
                            PreviewProfile.WEBP_OBJECT_GENERATOR);
            assertThat(thumbnail.userMetadata().get(PreviewProfile.METADATA_SHA256))
                    .matches("[0-9a-f]{64}");
            assertThat(photo[5]).isNotNull();
            assertThat(storage.stat(ORIGINAL_KEY).size()).isEqualTo(sourceSize);
            assertJpeg(PHOTO_KEY);
            // The finished object keeps the source format; the preview is WebP.
            assertWebp(THUMBNAIL_KEY);

            var item = jdbc.sql("""
                    SELECT status, temp_local_path FROM photo_upload_item
                    WHERE batch_id=:batchId
                    """).param("batchId", BATCH_ID)
                    .query((rs, rowNum) -> new String[]{
                            rs.getString("status"), rs.getString("temp_local_path")})
                    .single();
            assertThat(item[0]).isEqualTo("SUCCEEDED");
            assertThat(item[1]).isNull();
            assertThat(Files.exists(source)).isFalse();
            assertThat(jdbc.sql("SELECT status FROM photo_upload_batch WHERE id=:id")
                    .param("id", BATCH_ID).query(String.class).single()).isEqualTo("SUCCEEDED");
            try (Stream<Path> files = Files.walk(workspace.root().resolve("tasks"))) {
                assertThat(files.filter(Files::isRegularFile).toList()).isEmpty();
            }
        } finally {
            deleteObject(THUMBNAIL_KEY);
            deleteObject(PHOTO_KEY);
            deleteObject(ORIGINAL_KEY);
            jdbc.sql("DELETE FROM photo_upload_item WHERE batch_id=:id").param("id", BATCH_ID).update();
            jdbc.sql("DELETE FROM photo_upload_batch WHERE id=:id").param("id", BATCH_ID).update();
            jdbc.sql("DELETE FROM photo WHERE id=:id").param("id", PHOTO_ID).update();
            jdbc.sql("DELETE FROM app_user WHERE id=:id").param("id", USER_ID).update();
            if (Files.exists(source)) workspace.deleteBatchFile(source);
        }
    }

    @Test
    void ossUploadSchedulesPreviewBeforeNativeCompressionAndPublishesWithoutWaiting() throws Exception {
        Path source = workspace.createBatchFile(BATCH_ID, ".jpg");
        writeJpeg(source);
        long size = Files.size(source);
        insertRows(source, size);
        StorageProperties cloud = spy(properties);
        when(cloud.usesOss()).thenReturn(true);
        PreviewProfilePolicy cloudPolicy = new PreviewProfilePolicy(cloud, new PreviewProfileRepository(jdbc));
        OssThumbnailJobs previewJobs = mock(OssThumbnailJobs.class);
        var pending = new OssThumbnailJobs.Pending(new CompletableFuture<>());
        when(previewJobs.begin(eq(PHOTO_ID), any(Path.class), eq("image/jpeg"), eq(size), any()))
                .thenReturn(pending);
        ImageCompressor finishedEncoder = spy(compressor);
        doAnswer(call -> {
            verify(previewJobs).begin(eq(PHOTO_ID), any(Path.class), eq("image/jpeg"), eq(size), any());
            assertThat(pending.committed()).isNotCompleted();
            return call.callRealMethod();
        }).when(finishedEncoder).compress(any(Path.class), any(Path.class), anyString(), anyLong());
        PhotoProcessingService service = new PhotoProcessingService(
                photoMapper, userMapper, storage, cloud,
                new NativeImageTaskPool(processingExecutor, finishedEncoder), workspace,
                batchItemMapper, batchMapper, cloudPolicy, transactions, previewJobs);

        try {
            service.submit(PHOTO_ID).get(10, TimeUnit.SECONDS);

            var photo = photoMapper.selectById(PHOTO_ID);
            assertThat(photo.getStatus()).isEqualTo(cn.photolib.photo.model.PhotoStatus.AVAILABLE);
            assertThat(photo.getThumbnailObjectKey()).isNull();
            assertThat(pending.committed().join().key()).isEqualTo(PHOTO_KEY);
            verify(finishedEncoder, never()).thumbnail(any(), any(), anyString(), anyInt(), anyDouble());

            String preview = OssAvifPreview.key("uploads", PHOTO_ID);
            assertThat(photoMapper.attachCloudThumbnail(PHOTO_ID, "photos/replaced.jpg", preview, 73,
                    LocalDateTime.now())).isZero();
            assertThat(photoMapper.attachCloudThumbnail(PHOTO_ID, PHOTO_KEY, preview, 73,
                    LocalDateTime.now())).isEqualTo(1);
            assertThat(photoMapper.attachCloudThumbnail(PHOTO_ID, PHOTO_KEY, "thumbnails/other.avif", 80,
                    LocalDateTime.now())).isZero();
            assertThat(photoMapper.selectById(PHOTO_ID).getThumbnailObjectKey()).isEqualTo(preview);
        } finally {
            deleteObject(PHOTO_KEY);
            deleteObject(ORIGINAL_KEY);
            jdbc.sql("DELETE FROM photo_upload_item WHERE batch_id=:id").param("id", BATCH_ID).update();
            jdbc.sql("DELETE FROM photo_upload_batch WHERE id=:id").param("id", BATCH_ID).update();
            jdbc.sql("DELETE FROM photo WHERE id=:id").param("id", PHOTO_ID).update();
            jdbc.sql("DELETE FROM app_user WHERE id=:id").param("id", USER_ID).update();
            if (Files.exists(source)) workspace.deleteBatchFile(source);
        }
    }

    @Test
    void publishesThePhotoWithoutAPreviewWhenThumbnailEncodingFails() throws Exception {
        Path source = workspace.createBatchFile(BATCH_ID, ".jpg");
        writeJpeg(source);
        long sourceSize = Files.size(source);
        insertRows(source, sourceSize);

        ImageCompressor failingThumbnails = spy(compressor);
        doThrow(new java.io.IOException("原生图片处理失败: synthetic"))
                .when(failingThumbnails).thumbnail(any(Path.class), any(Path.class),
                        anyString(), anyInt(), anyDouble());
        PhotoProcessingService isolated = new PhotoProcessingService(
                photoMapper, userMapper, storage, properties,
                new NativeImageTaskPool(processingExecutor, failingThumbnails),
                workspace, batchItemMapper, batchMapper, previewProfiles, transactions, null);

        try {
            isolated.submit(PHOTO_ID).get(30, TimeUnit.SECONDS);

            var photo = jdbc.sql("""
                    SELECT status, failure_reason, thumbnail_object_key, thumbnail_size
                    FROM photo WHERE id=:id
                    """).param("id", PHOTO_ID)
                    .query((rs, rowNum) -> new Object[]{
                            rs.getString("status"), rs.getString("failure_reason"),
                            rs.getString("thumbnail_object_key"),
                            rs.getObject("thumbnail_size", Long.class)})
                    .single();
            // The finished object uploaded fine, so the upload itself succeeded.
            // Only the preview is missing, and the gallery falls back to the
            // finished object until a repair pass regenerates it.
            assertThat(photo[0]).as("failure_reason=%s", photo[1]).isEqualTo("AVAILABLE");
            assertThat(photo[1]).isNull();
            assertThat(photo[2]).isNull();
            assertThat(photo[3]).isNull();
            assertThat(storage.find(THUMBNAIL_KEY)).isEmpty();
            assertThat(storage.stat(PHOTO_KEY).size()).isPositive();
            assertThat(jdbc.sql("""
                    SELECT status FROM photo_upload_item WHERE batch_id=:batchId
                    """).param("batchId", BATCH_ID).query(String.class).single())
                    .isEqualTo("SUCCEEDED");
        } finally {
            deleteObject(THUMBNAIL_KEY);
            deleteObject(PHOTO_KEY);
            deleteObject(ORIGINAL_KEY);
            jdbc.sql("DELETE FROM photo_upload_item WHERE batch_id=:id").param("id", BATCH_ID).update();
            jdbc.sql("DELETE FROM photo_upload_batch WHERE id=:id").param("id", BATCH_ID).update();
            jdbc.sql("DELETE FROM photo WHERE id=:id").param("id", PHOTO_ID).update();
            jdbc.sql("DELETE FROM app_user WHERE id=:id").param("id", USER_ID).update();
            if (Files.exists(source)) workspace.deleteBatchFile(source);
        }
    }

    @Test
    void anErrorDuringProcessingStillMarksThePhotoFailedAndIsRethrown() throws Exception {
        // 以前只接 Exception：一个 Error 会让照片和批次永远停在"处理中"。
        Path source = workspace.createBatchFile(BATCH_ID, ".jpg");
        writeJpeg(source);
        long sourceSize = Files.size(source);
        insertRows(source, sourceSize);

        ImageCompressor brokenCompressor = spy(compressor);
        doThrow(new AssertionError("synthetic native failure"))
                .when(brokenCompressor).compress(any(Path.class), any(Path.class),
                        anyString(), anyLong());
        PhotoProcessingService isolated = new PhotoProcessingService(
                photoMapper, userMapper, storage, properties,
                new NativeImageTaskPool(processingExecutor, brokenCompressor),
                workspace, batchItemMapper, batchMapper, previewProfiles, transactions, null);

        try {
            // Error 不能被吞掉：任务的 future 以它结束，onRequested 会把它记进日志。
            assertThatThrownBy(() -> isolated.submit(PHOTO_ID).get(30, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(AssertionError.class);
            assertThat(isolated.isInFlight(PHOTO_ID)).isFalse();

            var photo = jdbc.sql("SELECT status, failure_reason FROM photo WHERE id=:id")
                    .param("id", PHOTO_ID)
                    .query((rs, rowNum) -> new String[]{
                            rs.getString("status"), rs.getString("failure_reason")})
                    .single();
            assertThat(photo[0]).isEqualTo("UPLOADING");
            // 内部错误对外只给通用提示，Error 的原文不外抬。
            assertThat(photo[1]).isNotBlank().doesNotContain("synthetic");
            assertThat(jdbc.sql("""
                    SELECT status FROM photo_upload_item WHERE batch_id=:batchId
                    """).param("batchId", BATCH_ID).query(String.class).single())
                    .isEqualTo("FAILED");
            assertThat(jdbc.sql("SELECT status FROM photo_upload_batch WHERE id=:id")
                    .param("id", BATCH_ID).query(String.class).single())
                    .isEqualTo("PARTIALLY_SUCCEEDED");
        } finally {
            deleteObject(THUMBNAIL_KEY);
            deleteObject(PHOTO_KEY);
            deleteObject(ORIGINAL_KEY);
            jdbc.sql("DELETE FROM photo_upload_item WHERE batch_id=:id").param("id", BATCH_ID).update();
            jdbc.sql("DELETE FROM photo_upload_batch WHERE id=:id").param("id", BATCH_ID).update();
            jdbc.sql("DELETE FROM photo WHERE id=:id").param("id", PHOTO_ID).update();
            jdbc.sql("DELETE FROM app_user WHERE id=:id").param("id", USER_ID).update();
            if (Files.exists(source)) workspace.deleteBatchFile(source);
        }
    }

    @Test
    void processingFailureRecordsAuditLogEntryWithFailureReason() throws Exception {
        Path source = workspace.createBatchFile(BATCH_ID, ".jpg");
        writeJpeg(source);
        long sourceSize = Files.size(source);
        insertRows(source, sourceSize);

        ImageCompressor rejectingCompressor = spy(compressor);
        doThrow(new UnprocessableImageException(
                "这张图片是分辨率很高的渐进式 JPEG，超出了服务器的解码内存上限。",
                "超大渐进式 JPEG 超出解码内存上限"))
                .when(rejectingCompressor).compress(any(Path.class), any(Path.class),
                        anyString(), anyLong());
        PhotoProcessingService isolated = new PhotoProcessingService(
                photoMapper, userMapper, storage, properties,
                new NativeImageTaskPool(processingExecutor, rejectingCompressor),
                workspace, batchItemMapper, batchMapper, previewProfiles, transactions, null);
        isolated.setAuditLogMapper(auditLogMapper);

        try {
            isolated.submit(PHOTO_ID).get(30, TimeUnit.SECONDS);

            var audit = jdbc.sql("""
                    SELECT operator_id, action, action_name, resource_type, resource_id,
                           resource_name, success, error_code, error_message, detail_json
                    FROM audit_log
                    WHERE action = :action AND resource_id = :resourceId
                    ORDER BY id DESC LIMIT 1
                    """)
                    .param("action", PhotoProcessingService.PROCESSING_FAILED_AUDIT_ACTION)
                    .param("resourceId", String.valueOf(PHOTO_ID))
                    .query((rs, rowNum) -> new Object[]{
                            rs.getLong("operator_id"), rs.getString("action"),
                            rs.getString("action_name"), rs.getString("resource_type"),
                            rs.getString("resource_id"), rs.getString("resource_name"),
                            rs.getBoolean("success"), rs.getString("error_code"),
                            rs.getString("error_message"), rs.getString("detail_json")})
                    .single();
            assertThat(audit[0]).isEqualTo(USER_ID);
            assertThat(audit[1]).isEqualTo(PhotoProcessingService.PROCESSING_FAILED_AUDIT_ACTION);
            assertThat(audit[2]).isEqualTo(PhotoProcessingService.PROCESSING_FAILED_AUDIT_NAME);
            assertThat(audit[3]).isEqualTo("PHOTOS");
            assertThat(audit[4]).isEqualTo(String.valueOf(PHOTO_ID));
            assertThat(audit[5]).isEqualTo("file pipeline");
            assertThat(audit[6]).isEqualTo(false);
            assertThat(audit[7]).isEqualTo("UNPROCESSABLE_IMAGE");
            assertThat((String) audit[8]).contains("渐进式 JPEG");
            assertThat((String) audit[9]).contains("超大渐进式 JPEG 超出解码内存上限");
        } finally {
            deleteObject(THUMBNAIL_KEY);
            deleteObject(PHOTO_KEY);
            deleteObject(ORIGINAL_KEY);
            jdbc.sql("DELETE FROM audit_log WHERE action=:action AND resource_id=:resourceId")
                    .param("action", PhotoProcessingService.PROCESSING_FAILED_AUDIT_ACTION)
                    .param("resourceId", String.valueOf(PHOTO_ID)).update();
            jdbc.sql("DELETE FROM photo_upload_item WHERE batch_id=:id").param("id", BATCH_ID).update();
            jdbc.sql("DELETE FROM photo_upload_batch WHERE id=:id").param("id", BATCH_ID).update();
            jdbc.sql("DELETE FROM photo WHERE id=:id").param("id", PHOTO_ID).update();
            jdbc.sql("DELETE FROM app_user WHERE id=:id").param("id", USER_ID).update();
            if (Files.exists(source)) workspace.deleteBatchFile(source);
        }
    }

    private void insertRows(Path source, long sourceSize) {
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled,
                     must_change_password, version, deleted)
                VALUES
                    (:id, 'file-pipeline-user', 'hash', 'file pipeline', 'ADMIN', true,
                     false, 1, false)
                """).param("id", USER_ID).update();
        jdbc.sql("""
                INSERT INTO photo
                    (id, title, photographer_student_id, photographer_name, uploaded_by,
                     taken_at, size, content_type, object_key, original_object_key,
                     sha256, status, version, deleted)
                VALUES
                    (:id, 'file pipeline', '202693901', 'file photographer', :userId,
                     :takenAt, :size, 'image/jpeg', :objectKey, :originalKey,
                     :sha256, 'PROCESSING', 1, false)
                """)
                .param("id", PHOTO_ID)
                .param("userId", USER_ID)
                .param("takenAt", LocalDateTime.now().minusMinutes(1))
                .param("size", sourceSize)
                .param("objectKey", PHOTO_KEY)
                .param("originalKey", ORIGINAL_KEY)
                .param("sha256", "0".repeat(64))
                .update();
        jdbc.sql("""
                INSERT INTO photo_upload_batch
                    (id, mode, created_by, status, total_count, success_count, failure_count)
                VALUES (:id, 'ZIP', :userId, 'PROCESSING', 1, 0, 0)
                """).param("id", BATCH_ID).param("userId", USER_ID).update();
        jdbc.sql("""
                INSERT INTO photo_upload_item
                    (batch_id, original_file_name, temp_object_key, temp_local_path,
                     content_type, size, status, photo_id)
                VALUES
                    (:batchId, 'source.jpg', :objectKey, :localPath,
                     'image/jpeg', :size, 'PROCESSING', :photoId)
                """)
                .param("batchId", BATCH_ID)
                .param("objectKey", ORIGINAL_KEY)
                .param("localPath", source.toString())
                .param("size", sourceSize)
                .param("photoId", PHOTO_ID)
                .update();
    }

    private void writeJpeg(Path path) throws Exception {
        BufferedImage image = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, new Color((x * 17 + y) & 255,
                        (x + y * 11) & 255, (x * y) & 255).getRGB());
            }
        }
        ImageIO.write(image, "jpg", path.toFile());
    }

    private void assertJpeg(String objectKey) throws Exception {
        try (InputStream input = storage.open(objectKey)) {
            byte[] magic = input.readNBytes(3);
            assertThat(magic).containsExactly((byte) 0xff, (byte) 0xd8, (byte) 0xff);
        }
    }

    /** RIFF container with a WEBP fourcc at offset 8. */
    private void assertWebp(String objectKey) throws Exception {
        try (InputStream input = storage.open(objectKey)) {
            byte[] header = input.readNBytes(12);
            assertThat(new String(header, 0, 4, java.nio.charset.StandardCharsets.US_ASCII))
                    .isEqualTo("RIFF");
            assertThat(new String(header, 8, 4, java.nio.charset.StandardCharsets.US_ASCII))
                    .isEqualTo("WEBP");
        }
    }

    private void deleteObject(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException ignored) {
            // Test cleanup is best effort when the object was never created.
        }
    }
}
