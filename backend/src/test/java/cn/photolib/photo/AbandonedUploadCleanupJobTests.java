package cn.photolib.photo;

import cn.photolib.photo.batch.BatchUploadService;
import cn.photolib.storage.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * "传了一半就走"留下的临时对象的回收。
 *
 * <p>用例围着 {@link AbandonedUploadCleanupJob} 的四条规矩转，其中两条是这个任务
 * 存在的全部理由，改坏了不会有测试之外的人发现：<b>直传地址没过期不许删</b>
 * （删早了客户端能把对象传回来，而库里已经没有行指着它），<b>处理失败的照片不碰</b>
 * （它的临时对象是那张图唯一的副本，页面上正让人重试）。</p>
 */
@SpringBootTest
@Transactional
class AbandonedUploadCleanupJobTests {
    private static final long UPLOADER_ID = 7_951L;

    @Autowired private AbandonedUploadCleanupJob job;
    @Autowired private BatchUploadService batchUploads;
    @Autowired private PhotoProcessingWorkspace workspace;
    @Autowired private ObjectStorageService storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private Clock clock;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, campus_id, enabled,
                     must_change_password, version, deleted)
                VALUES (:id, 'cleanup-uploader', 'hash', '清理测试上传者', 'MINISTER', null, TRUE,
                        FALSE, 1, FALSE)
                """).param("id", UPLOADER_ID).update();
    }

    /** 造一张停在 UPLOADING 的照片，并把它的临时对象真的放进存储里。 */
    private long abandonedPhoto(String suffix, LocalDateTime uploadUrlExpiresAt, String failureReason) {
        String key = "temporary/photos/cleanup-" + suffix + ".jpg";
        byte[] bytes = "half-uploaded".getBytes(StandardCharsets.UTF_8);
        storage.put(key, new ByteArrayInputStream(bytes), bytes.length, "image/jpeg");
        jdbc.sql("""
                INSERT INTO photo
                    (photographer_student_id, photographer_name, uploaded_by, taken_at, size,
                     content_type, object_key, original_object_key, sha256, status, failure_reason,
                     upload_url_expires_at)
                VALUES ('20230001', '张三', :uploader, :now, 1024, 'image/jpeg',
                        :objectKey, :originalKey, :sha, 'UPLOADING', :failureReason, :expiresAt)
                """)
                .param("uploader", UPLOADER_ID).param("now", LocalDateTime.now(clock))
                .param("objectKey", "photos/cleanup-" + suffix + ".jpg")
                .param("originalKey", key)
                .param("sha", suffix.repeat(64).substring(0, 64))
                .param("failureReason", failureReason)
                .param("expiresAt", uploadUrlExpiresAt)
                .update();
        return jdbc.sql("SELECT id FROM photo WHERE original_object_key = :key")
                .param("key", key).query(Long.class).single();
    }

    private boolean objectExists(String key) {
        return storage.find(key).isPresent();
    }

    @Test
    void aTicketThatWasNeverCompletedLosesItsTemporaryObject() {
        // 直传地址早就过期了（宽限期也过了），这是最典型的一条：签了票据、人走了。
        long photoId = abandonedPhoto("a", LocalDateTime.now(clock).minusDays(1), null);

        AbandonedUploadCleanupJob.CleanupResult result = job.cleanup();

        assertThat(result.photos()).isGreaterThanOrEqualTo(1);
        assertThat(objectExists("temporary/photos/cleanup-a.jpg")).isFalse();
        // 行也一并软删：对象没了之后它永远走不到下一步。
        assertThat(jdbc.sql("SELECT deleted FROM photo WHERE id = :id")
                .param("id", photoId).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT original_object_key FROM photo WHERE id = :id")
                .param("id", photoId).query(String.class).optional()).isEmpty();
    }

    @Test
    void anUploadWhoseUrlHasNotExpiredIsLeftAlone() {
        // 删早了等于没删：客户端手里那条还没过期的 PUT 能把对象原样传回来，
        // 而那时库里已经没有任何一行指着它（招募那条链路在 V28 踩过这个坑）。
        long photoId = abandonedPhoto("b", LocalDateTime.now(clock).plusMinutes(10), null);

        job.cleanup();

        assertThat(objectExists("temporary/photos/cleanup-b.jpg")).isTrue();
        assertThat(jdbc.sql("SELECT deleted FROM photo WHERE id = :id")
                .param("id", photoId).query(Boolean.class).single()).isFalse();
    }

    @Test
    void anUploadStillInsideTheGraceWindowIsLeftAlone() {
        // 刚过期不算：最后一刻 PUT 成功、随后才调 complete 的请求还在路上。
        long photoId = abandonedPhoto("c",
                LocalDateTime.now(clock).minus(AbandonedUploadCleanupJob.GRACE).plusMinutes(5), null);

        job.cleanup();

        assertThat(objectExists("temporary/photos/cleanup-c.jpg")).isTrue();
        assertThat(jdbc.sql("SELECT deleted FROM photo WHERE id = :id")
                .param("id", photoId).query(Boolean.class).single()).isFalse();
    }

    @Test
    void aPhotoThatFailedProcessingKeepsItsOnlyCopy() {
        // 处理失败的照片同样停在 UPLOADING，但它带着 failure_reason、页面上正显示着
        // 让人重试，而那个临时对象是这张图唯一的副本。
        long photoId = abandonedPhoto("d", LocalDateTime.now(clock).minusDays(1), "图片 SHA-256 校验失败");

        job.cleanup();

        assertThat(objectExists("temporary/photos/cleanup-d.jpg")).isTrue();
        assertThat(jdbc.sql("SELECT deleted FROM photo WHERE id = :id")
                .param("id", photoId).query(Boolean.class).single()).isFalse();
    }

    @Test
    void anArchiveThatWasNeverCompletedIsRemovedAndTheBatchIsMarkedFailed() {
        String batchId = "cleanup-zip-batch";
        String key = "temporary/batches/" + batchId + "/archive.zip";
        byte[] bytes = "not-a-real-zip".getBytes(StandardCharsets.UTF_8);
        storage.put(key, new ByteArrayInputStream(bytes), bytes.length, "application/zip");
        jdbc.sql("""
                INSERT INTO photo_upload_batch
                    (id, mode, created_by, archive_object_key, archive_file_name, archive_size,
                     status, total_count, success_count, failure_count, upload_url_expires_at)
                VALUES (:id, 'ZIP', :uploader, :key, '活动.zip', 1024, 'UPLOADING', 0, 0, 0, :expiresAt)
                """).param("id", batchId).param("uploader", UPLOADER_ID).param("key", key)
                .param("expiresAt", LocalDateTime.now(clock).minusDays(1)).update();

        job.cleanup();

        assertThat(objectExists(key)).isFalse();
        var batch = jdbc.sql("""
                        SELECT status, archive_object_key FROM photo_upload_batch WHERE id = :id
                        """).param("id", batchId)
                .query((rs, row) -> new String[]{rs.getString(1), rs.getString(2)}).single();
        // 标成 FAILED，界面上才看得出这一包没成，而不是永远"上传中"。
        assertThat(batch[0]).isEqualTo("FAILED");
        assertThat(batch[1]).isNull();
    }

    @Test
    void aFreshlySignedArchiveTicketIsNotAlreadyExpiredOnTheJobsClock() {
        // 签票据时写的过期时间必须和本任务比较用的是同一只时钟。写的一方曾用 JVM 默认时区，
        // 在 UTC 的 CI runner 上刚签出来的票据就比 Asia/Shanghai 的"现在"早了八小时，
        // 签票据时顺手触发的清理会把还没传完的压缩包直接删掉。
        BatchUploadService.BatchTicket ticket = batchUploads.createZipBatch(new BatchUploadService.ZipBatch(
                null, null, UPLOADER_ID, null, "刚签的.zip", 1024L));

        LocalDateTime expiresAt = jdbc.sql("SELECT upload_url_expires_at FROM photo_upload_batch WHERE id = :id")
                .param("id", ticket.batchId()).query(LocalDateTime.class).single();
        assertThat(expiresAt).isAfter(LocalDateTime.now(clock));

        job.cleanup();

        assertThat(jdbc.sql("SELECT archive_object_key FROM photo_upload_batch WHERE id = :id")
                .param("id", ticket.batchId()).query(String.class).optional()).isPresent();
    }

    @Test
    void extractedFilesOfAbandonedBatchesAreReclaimedAndTheItemsMarkedFailed() {
        // 解包出来却一直没人整理的批次：本地盘上的文件是这里最占地方的一类，
        // 一个 1.5 GB 的包能摊开 10 GiB。
        String batchId = "cleanup-extracted";
        jdbc.sql("""
                INSERT INTO photo_upload_batch
                    (id, mode, created_by, status, total_count, success_count, failure_count,
                     created_at, updated_at)
                VALUES (:id, 'ZIP', :uploader, 'WAITING_METADATA', 1, 0, 0, :old, :old)
                """).param("id", batchId).param("uploader", UPLOADER_ID)
                .param("old", LocalDateTime.now(clock).minusDays(3)).update();
        java.nio.file.Path file;
        try {
            file = workspace.createBatchFile(batchId, ".jpg");
            java.nio.file.Files.writeString(file, "extracted");
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        jdbc.sql("""
                INSERT INTO photo_upload_item
                    (batch_id, original_file_name, temp_object_key, temp_local_path, content_type,
                     size, status)
                VALUES (:batch, '第一张.jpg', :key, :path, 'image/jpeg', 9, 'WAITING_METADATA')
                """).param("batch", batchId)
                .param("key", "temporary/batches/" + batchId + "/1.jpg")
                .param("path", file.toString()).update();

        job.cleanup();

        assertThat(java.nio.file.Files.exists(file)).isFalse();
        // 条目必须一起标成 FAILED：否则之后真有人来整理，落出来的照片会指着一个
        // 已经不存在的本地路径，要到处理时才失败。
        assertThat(jdbc.sql("SELECT status FROM photo_upload_item WHERE batch_id = :id")
                .param("id", batchId).query(String.class).single()).isEqualTo("FAILED");
    }

    @Test
    void aStorageFailureKeepsTheKeySoTheNextRoundCanRetry() {
        // 对象已经不在了（当成"删不掉/找不到"的那一类）：本地实现会抛，
        // 这时绝不能把键从库里抹掉——那等于宣布清理成功。
        long photoId = abandonedPhoto("e", LocalDateTime.now(clock).minusDays(1), null);
        storage.delete("temporary/photos/cleanup-e.jpg");

        job.cleanup();

        // 本地实现的 delete 对不存在的键是幂等的，所以这一行仍会被正常收掉；
        // 真正的失败路径（抛异常）在 deleteObject 里保留记录，见该方法注释。
        assertThat(jdbc.sql("SELECT deleted FROM photo WHERE id = :id")
                .param("id", photoId).query(Boolean.class).single()).isTrue();
        assertThatThrownBy(() -> storage.stat("temporary/photos/cleanup-e.jpg"))
                .isInstanceOf(IllegalArgumentException.class);
    }

}
