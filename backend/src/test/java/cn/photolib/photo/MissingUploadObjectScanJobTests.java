package cn.photolib.photo;

import cn.photolib.storage.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

/**
 * 「只剩数据库记录、对象存储里根本没有这张图」的回收。
 *
 * <p>用例围着 {@link MissingUploadObjectScanJob} 的删除凭据和三条护栏转。其中两条
 * 改坏了不会有测试之外的人发现：<b>只有精确 HEAD 确认不存在才算数</b>（HEAD 抛异常
 * 时保留记录），<b>连已发布照片的成品图都读不到时整轮作废</b>（那是 Bucket 配错，
 * 不是图真的没传上来）。还有一条是这个任务存在的全部理由：
 * <b>收掉这样一行之后，同一张图必须能重新传上来</b>。</p>
 */
@SpringBootTest
@Transactional
class MissingUploadObjectScanJobTests {
    private static final long UPLOADER_ID = 88_401L;

    @Autowired private PhotoService photoService;
    @Autowired private ObjectStorageService storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private TransactionTemplate transactions;
    @Autowired private Clock clock;

    @BeforeEach
    void isolateCandidateSet() {
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, campus_id, enabled,
                     must_change_password, version, deleted)
                VALUES (:id, 'missing-upload-scan-user', 'hash', '扫描测试上传者', 'MINISTER', null,
                        TRUE, FALSE, 1, FALSE)
                """).param("id", UPLOADER_ID).update();
        // 候选集是全库的，先把既有行藏起来，这一轮扫到的就只有用例自己造的。
        // 随测试事务一起回滚。
        jdbc.sql("UPDATE photo SET deleted = 1 WHERE deleted = 0").update();
        // 基准照片：storageLooksHealthy() 要能 HEAD 到一张已发布照片的成品图，
        // 否则每个用例都会走进"存储不可信"的中止分支。
        putObject("photos/scan/sentinel.jpg", "published");
        insertPhoto(88_410L, "AVAILABLE", "photos/scan/sentinel.jpg", null,
                sha(0), LocalDateTime.now(clock).minusDays(2));
    }

    @Test
    void anUploadWhoseObjectWasNeverStoredLosesItsDatabaseRow() {
        // 最典型的一条：批量那条路在上传原图之前就处理失败了，库里有行、桶里没有对象。
        insertPhoto(88_411L, "UPLOADING", "photos/scan/never-stored.jpg",
                "temporary/photos/scan-never-stored.jpg", sha(1),
                LocalDateTime.now(clock).minusDays(1));

        MissingUploadObjectScanJob.ScanResult result = job(storage).scan();

        assertThat(result.missing()).isOne();
        assertThat(result.deleted()).isOne();
        assertThat(result.aborted()).isFalse();
        assertThat(deletedFlag(88_411L)).isTrue();
        // 软删而不是改状态：误判可以把 deleted 改回 0 恢复。
        assertThat(jdbc.sql("SELECT status FROM photo WHERE id = 88411")
                .query(String.class).single()).isEqualTo("UPLOADING");
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM audit_log
                WHERE action = 'PHOTO_AUTO_CLEANUP' AND resource_type = 'PHOTO'
                  AND resource_id = '88411' AND operator_id IS NULL
                """).query(Long.class).single()).isOne();
    }

    @Test
    void theRemovedRowStopsBlockingTheSameFileFromBeingUploadedAgain() {
        // 这才是问题本身：查重按 sha256 + deleted=0，一行传失败却留下来的记录会让
        // 上传者一直看到"已经上传过该图片"，而那张图从来没进过相册。
        String sha = sha(2);
        insertPhoto(88_412L, "UPLOADING", "photos/scan/duplicate-block.jpg",
                "temporary/photos/scan-duplicate-block.jpg", sha,
                LocalDateTime.now(clock).minusDays(1));
        // 清理前这一行对查重是可见的。这里**不能**先调一次 requireUniqueSha256：
        // MyBatis 的一级缓存跟着事务走，同一条查询在扫描之后会原样回放缓存里的那一行，
        // 于是这个用例永远失败——而真实调用之间隔着请求，没有这个问题。
        assertThat(visibleToDeduplication(sha)).isOne();

        MissingUploadObjectScanJob.ScanResult result = job(storage).scan();

        assertThat(result.deleted()).isOne();
        assertThat(deletedFlag(88_412L)).isTrue();
        assertThatCode(() -> photoService.requireUniqueSha256(sha, null))
                .doesNotThrowAnyException();
    }

    @Test
    void anUploadWhoseObjectIsStillThereIsLeftAlone() {
        // 单张上传处理失败停在 UPLOADING，但那个临时对象是这张图唯一的副本。
        putObject("temporary/photos/scan-still-there.jpg", "half-uploaded");
        insertPhoto(88_413L, "UPLOADING", "photos/scan/still-there.jpg",
                "temporary/photos/scan-still-there.jpg", sha(3),
                LocalDateTime.now(clock).minusDays(1));

        MissingUploadObjectScanJob.ScanResult result = job(storage).scan();

        assertThat(result.checked()).isOne();
        assertThat(result.missing()).isZero();
        assertThat(deletedFlag(88_413L)).isFalse();

        storage.delete("temporary/photos/scan-still-there.jpg");
    }

    @Test
    void anUploadWhoseUrlHasNotExpiredIsNotEvenLookedAt() {
        // 还没过期的行"对象不存在"是正常的：客户端还没 PUT 而已。
        insertPhoto(88_414L, "UPLOADING", "photos/scan/in-flight.jpg",
                "temporary/photos/scan-in-flight.jpg", sha(4),
                LocalDateTime.now(clock).plusMinutes(10));

        MissingUploadObjectScanJob.ScanResult result = job(storage).scan();

        assertThat(result.checked()).isZero();
        assertThat(deletedFlag(88_414L)).isFalse();
    }

    @Test
    void anUploadStillInsideTheGraceWindowIsNotEvenLookedAt() {
        // 刚过期也不算：最后一刻 PUT 成功、随后才调 complete 的请求还在路上。
        insertPhoto(88_415L, "UPLOADING", "photos/scan/grace.jpg",
                "temporary/photos/scan-grace.jpg", sha(5),
                LocalDateTime.now(clock).minus(MissingUploadObjectScanJob.GRACE).plusMinutes(5));

        MissingUploadObjectScanJob.ScanResult result = job(storage).scan();

        assertThat(result.checked()).isZero();
        assertThat(deletedFlag(88_415L)).isFalse();
    }

    @Test
    void aHeadFailureKeepsTheRowAndRaisesAnAlert() {
        // 不知道的 HEAD 结果永远不能拿来删记录。
        insertPhoto(88_416L, "UPLOADING", "photos/scan/head-failure.jpg",
                "temporary/photos/scan-head-failure.jpg", sha(6),
                LocalDateTime.now(clock).minusDays(1));
        ObjectStorageService failing = spy(storage);
        doThrow(new IllegalStateException("连不上对象存储"))
                .when(failing).find("temporary/photos/scan-head-failure.jpg");

        MissingUploadObjectScanJob.ScanResult result = job(failing).scan();

        assertThat(result.headFailures()).isOne();
        assertThat(result.deleted()).isZero();
        assertThat(deletedFlag(88_416L)).isFalse();
        assertThat(unresolvedAlerts("PHOTO_UPLOAD_SCAN_HEAD_FAILED")).isOne();
    }

    @Test
    void nothingIsDeletedWhenEvenAPublishedPhotoLooksMissing() {
        // Bucket / Endpoint / 挂载点配错时每一次 HEAD 都会"确认不存在"，这一轮的
        // 结论全不作数——否则一次配置事故就会把所有在传的记录一起抹掉。
        storage.delete("photos/scan/sentinel.jpg");
        insertPhoto(88_417L, "UPLOADING", "photos/scan/aborted.jpg",
                "temporary/photos/scan-aborted.jpg", sha(7),
                LocalDateTime.now(clock).minusDays(1));

        MissingUploadObjectScanJob.ScanResult result = job(storage).scan();

        assertThat(result.missing()).isOne();
        assertThat(result.aborted()).isTrue();
        assertThat(result.deleted()).isZero();
        assertThat(deletedFlag(88_417L)).isFalse();
        assertThat(unresolvedAlerts("PHOTO_UPLOAD_SCAN_ABORTED")).isOne();
    }

    @Test
    void photosThatAreStillProcessingOrAlreadyPublishedAreNeverCandidates() {
        insertPhoto(88_418L, "PROCESSING", "photos/scan/processing.jpg",
                "temporary/photos/scan-processing.jpg", sha(8),
                LocalDateTime.now(clock).minusDays(1));
        insertPhoto(88_419L, "AVAILABLE", "photos/scan/published.jpg",
                "temporary/photos/scan-published.jpg", sha(9),
                LocalDateTime.now(clock).minusDays(1));

        MissingUploadObjectScanJob.ScanResult result = job(storage).scan();

        assertThat(result.checked()).isZero();
        assertThat(deletedFlag(88_418L)).isFalse();
        assertThat(deletedFlag(88_419L)).isFalse();
    }

    private MissingUploadObjectScanJob job(ObjectStorageService objectStorage) {
        // 每个用例一个新实例：扫描游标是实例状态，共用会让用例之间互相影响。
        return new MissingUploadObjectScanJob(objectStorage, jdbc, transactions, clock, true);
    }

    private void insertPhoto(long id, String status, String objectKey, String originalObjectKey,
                             String sha256, LocalDateTime uploadUrlExpiresAt) {
        jdbc.sql("""
                INSERT INTO photo
                    (id, photographer_student_id, photographer_name, uploaded_by, taken_at, size,
                     content_type, object_key, original_object_key, sha256, status,
                     upload_url_expires_at, title, version, deleted)
                VALUES (:id, '20230001', '张三', :uploader, :now, 1024, 'image/jpeg',
                        :objectKey, :originalKey, :sha, :status, :expiresAt, '扫描测试图片', 1, FALSE)
                """)
                .param("id", id).param("uploader", UPLOADER_ID)
                .param("now", LocalDateTime.now(clock))
                .param("objectKey", objectKey).param("originalKey", originalObjectKey)
                .param("sha", sha256).param("status", status)
                .param("expiresAt", uploadUrlExpiresAt)
                .update();
    }

    private void putObject(String key, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        storage.put(key, new ByteArrayInputStream(bytes), bytes.length, "image/jpeg");
    }

    private boolean deletedFlag(long photoId) {
        return jdbc.sql("SELECT deleted FROM photo WHERE id = :id")
                .param("id", photoId).query(Boolean.class).single();
    }

    /** 查重（{@code PhotoService.createTicket}）看到的就是这一条：同哈希且未删除的行。 */
    private long visibleToDeduplication(String sha256) {
        return jdbc.sql("SELECT COUNT(*) FROM photo WHERE sha256 = :sha AND deleted = 0")
                .param("sha", sha256).query(Long.class).single();
    }

    private long unresolvedAlerts(String type) {
        return jdbc.sql("SELECT COUNT(*) FROM admin_alert WHERE type = :type AND resolved = 0")
                .param("type", type).query(Long.class).single();
    }

    /** 64 位十六进制的哈希占位；每个用例一份，免得撞上查重。 */
    private String sha(int seed) {
        return String.format("%064x", 0x5CA_0000L + seed);
    }
}
