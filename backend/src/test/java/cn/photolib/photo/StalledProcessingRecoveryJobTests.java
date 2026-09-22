package cn.photolib.photo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * 停在 {@code PROCESSING}、却已经没有任务在处理的照片：以前没有任何任务看这个状态，
 * JVM 重启之后图库里就永远挂着一张"处理中"。
 *
 * <p>最要紧的两条护栏：<b>本进程还在处理（或排队）的照片不能碰</b>，
 * <b>重提交次数到了上限就不再提交</b>——把 JVM 带走的往往就是这张图本身。</p>
 */
@SpringBootTest
@Transactional
class StalledProcessingRecoveryJobTests {
    private static final long UPLOADER_ID = 88_601L;
    private static final long STALE_MINUTES = 15;
    private static final String BATCH_ID = "stalled-recovery-batch";

    @Autowired private PhotoProcessingService processingService;
    @Autowired private JdbcClient jdbc;
    @Autowired private TransactionTemplate transactions;
    @Autowired private Clock clock;

    private PhotoProcessingService processing;

    @BeforeEach
    void isolateCandidateSet() {
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, campus_id, enabled,
                     must_change_password, version, deleted)
                VALUES (:id, 'stalled-recovery-user', 'hash', '恢复测试上传者', 'MINISTER',
                        null, TRUE, FALSE, 1, FALSE)
                """).param("id", UPLOADER_ID).update();
        // 候选集是全库的，先把既有行藏起来；随测试事务一起回滚。
        jdbc.sql("UPDATE photo SET deleted = 1 WHERE deleted = 0").update();
        // 真的交给处理池的话，异步线程看不到这个还没提交的测试事务，只会白跑一趟；
        // 这里只关心"有没有提交、提交了谁"。
        processing = spy(processingService);
        doReturn(CompletableFuture.completedFuture(null)).when(processing).submit(anyLong());
    }

    @Test
    void aStalledPhotoIsClaimedAndResubmitted() {
        insertPhoto(88_611L, "PROCESSING", 0, minutesAgo(STALE_MINUTES + 1));

        StalledProcessingRecoveryJob.RecoveryResult result = job(1).recover();

        assertThat(result.resubmitted()).isOne();
        assertThat(result.failed()).isZero();
        verify(processing).submit(88_611L);
        var row = row(88_611L);
        assertThat(row.status()).isEqualTo("PROCESSING");
        assertThat(row.recoveries()).isOne();
        // 认领时 version +1：与它并发的原任务如果还活着，它的完成 CAS 会落空，反之亦然。
        assertThat(row.version()).isEqualTo(2);
        // 刷新了 updated_at：下一轮不会在它还在排队时再提交一次。
        assertThat(row.updatedAt()).isAfter(minutesAgo(1));
        assertThat(auditDetail(88_611L)).contains("RESUBMITTED");
    }

    @Test
    void aPhotoThatAlreadyUsedUpItsResubmitsIsMarkedFailedTogetherWithItsBatch() {
        // 重启之后又卡住：很可能就是这张图把 JVM 带走的，不能再提交一次。
        insertPhoto(88_612L, "PROCESSING", 1, minutesAgo(STALE_MINUTES + 1));
        insertBatchItem(88_612L);

        StalledProcessingRecoveryJob.RecoveryResult result = job(1).recover();

        assertThat(result.failed()).isOne();
        assertThat(result.resubmitted()).isZero();
        verify(processing, never()).submit(anyLong());
        var row = row(88_612L);
        // 打回 UPLOADING 并写上原因，与处理失败同一个语义（前端显示"处理失败"）。
        assertThat(row.status()).isEqualTo("UPLOADING");
        assertThat(row.failureReason()).isEqualTo(StalledProcessingRecoveryJob.FAILURE_REASON);
        assertThat(auditDetail(88_612L)).contains("MARKED_FAILED");
        // 批次不能一直停在"处理中"。
        assertThat(jdbc.sql("SELECT status FROM photo_upload_item WHERE batch_id = :id")
                .param("id", BATCH_ID).query(String.class).single()).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT status FROM photo_upload_batch WHERE id = :id")
                .param("id", BATCH_ID).query(String.class).single()).isEqualTo("PARTIALLY_SUCCEEDED");
        assertThat(jdbc.sql("SELECT failure_count FROM photo_upload_batch WHERE id = :id")
                .param("id", BATCH_ID).query(Integer.class).single()).isOne();
    }

    @Test
    void withResubmitsDisabledAStalledPhotoIsFailedStraightAway() {
        insertPhoto(88_613L, "PROCESSING", 0, minutesAgo(STALE_MINUTES + 1));

        StalledProcessingRecoveryJob.RecoveryResult result = job(0).recover();

        assertThat(result.failed()).isOne();
        verify(processing, never()).submit(anyLong());
        assertThat(row(88_613L).status()).isEqualTo("UPLOADING");
    }

    @Test
    void aPhotoStillQueuedOrRunningInThisProcessIsLeftAlone() {
        // 活动当天处理池里排着几百张，排队时间超过阈值很正常。
        insertPhoto(88_614L, "PROCESSING", 0, minutesAgo(STALE_MINUTES * 4));
        doReturn(true).when(processing).isInFlight(88_614L);

        StalledProcessingRecoveryJob.RecoveryResult result = job(1).recover();

        assertThat(result.resubmitted()).isZero();
        assertThat(result.failed()).isZero();
        verify(processing, never()).submit(anyLong());
        assertThat(row(88_614L).version()).isOne();
    }

    @Test
    void aRecentlyUpdatedPhotoIsNotEvenLookedAt() {
        // complete 提交事务和交给处理池之间有一小段空档，时间阈值就是给它留的。
        insertPhoto(88_615L, "PROCESSING", 0, minutesAgo(STALE_MINUTES - 5));

        assertThat(job(1).recover()).isEqualTo(new StalledProcessingRecoveryJob.RecoveryResult(0, 0));
        verify(processing, never()).submit(anyLong());
    }

    @Test
    void photosInOtherStatesAreNotThisJobsBusiness() {
        insertPhoto(88_616L, "UPLOADING", 5, minutesAgo(600));
        insertPhoto(88_617L, "AVAILABLE", 5, minutesAgo(600));

        assertThat(job(1).recover()).isEqualTo(new StalledProcessingRecoveryJob.RecoveryResult(0, 0));
        assertThat(row(88_616L).status()).isEqualTo("UPLOADING");
        assertThat(row(88_617L).status()).isEqualTo("AVAILABLE");
    }

    @Test
    void theInFlightSetIsReleasedWhenATaskFinishes() throws Exception {
        // 真的走一遍 submit：照片不存在，process() 立刻返回；跑完之后必须从在途集合里放掉，
        // 否则这张图以后卡住了也永远不会被接手。
        processingService.submit(88_699L).get();

        assertThat(processingService.isInFlight(88_699L)).isFalse();
    }

    private StalledProcessingRecoveryJob job(int maxResubmits) {
        return new StalledProcessingRecoveryJob(processing, jdbc, transactions, clock,
                STALE_MINUTES, maxResubmits, true);
    }

    private LocalDateTime minutesAgo(long minutes) {
        return LocalDateTime.now(clock).minusMinutes(minutes);
    }

    private void insertPhoto(long id, String status, int recoveries, LocalDateTime updatedAt) {
        jdbc.sql("""
                INSERT INTO photo
                    (id, photographer_student_id, photographer_name, uploaded_by, taken_at, size,
                     content_type, object_key, original_object_key, sha256, status,
                     processing_recoveries, title, version, deleted, created_at, updated_at)
                VALUES (:id, '20230001', '张三', :uploader, :takenAt, 1024, 'image/jpeg',
                        :objectKey, :originalKey, :sha, :status, :recoveries,
                        '恢复测试图片', 1, FALSE, :updatedAt, :updatedAt)
                """)
                .param("id", id).param("uploader", UPLOADER_ID)
                .param("takenAt", LocalDateTime.now(clock).minusDays(1))
                .param("objectKey", "photos/stalled/" + id + ".jpg")
                .param("originalKey", "temporary/photos/stalled-" + id + ".jpg")
                .param("sha", String.format("%064x", id))
                .param("status", status).param("recoveries", recoveries)
                .param("updatedAt", updatedAt)
                .update();
    }

    private void insertBatchItem(long photoId) {
        jdbc.sql("""
                INSERT INTO photo_upload_batch
                    (id, mode, created_by, status, total_count, success_count, failure_count)
                VALUES (:id, 'ZIP', :userId, 'PROCESSING', 1, 0, 0)
                """).param("id", BATCH_ID).param("userId", UPLOADER_ID).update();
        jdbc.sql("""
                INSERT INTO photo_upload_item
                    (batch_id, original_file_name, temp_object_key, content_type, size, status, photo_id)
                VALUES (:batchId, 'stalled.jpg', 'temporary/photos/stalled.jpg', 'image/jpeg', 1024,
                        'PROCESSING', :photoId)
                """).param("batchId", BATCH_ID).param("photoId", photoId).update();
    }

    private Row row(long id) {
        return jdbc.sql("""
                        SELECT status, failure_reason, processing_recoveries, version, updated_at
                        FROM photo WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, n) -> new Row(rs.getString("status"), rs.getString("failure_reason"),
                        rs.getInt("processing_recoveries"), rs.getInt("version"),
                        rs.getTimestamp("updated_at").toLocalDateTime()))
                .single();
    }

    private String auditDetail(long photoId) {
        return jdbc.sql("""
                SELECT detail_json FROM audit_log
                WHERE action = 'PHOTO_PROCESSING_RECOVERY' AND resource_type = 'PHOTO'
                  AND resource_id = :id AND operator_id IS NULL
                """).param("id", String.valueOf(photoId)).query(String.class).single();
    }

    private record Row(String status, String failureReason, int recoveries, int version,
                       LocalDateTime updatedAt) {
    }
}
