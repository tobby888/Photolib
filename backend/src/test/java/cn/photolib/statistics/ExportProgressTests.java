package cn.photolib.statistics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** 打包进度的换算、节流，和看门狗对中断任务的处理。 */
@SpringBootTest
@Transactional
class ExportProgressTests {
    private static final long OWNER_ID = 93_001L;

    @Autowired private JdbcClient jdbc;
    @Autowired private ExportJobWatchdog watchdog;

    @BeforeEach
    void owner() {
        jdbc.sql("""
                INSERT INTO app_user (id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (:id, 'export-progress', 'x', '打包测试', 'ADMIN', TRUE, FALSE)
                """).param("id", OWNER_ID).update();
    }

    private void job(String id, String status, LocalDateTime updatedAt) {
        jdbc.sql("""
                INSERT INTO export_job (id, type, status, progress, created_by, created_at, updated_at)
                VALUES (:id, 'PHOTO_BATCH', :status, 0, :owner, :at, :at)
                """).param("id", id).param("status", status).param("owner", OWNER_ID).param("at", updatedAt).update();
    }

    private ExportJobEntity read(String id) {
        return jdbc.sql("SELECT * FROM export_job WHERE id=:id").param("id", id)
                .query(ExportJobEntity.class).single();
    }

    @Test
    void packingProgressFollowsBytesAndUploadingFillsTheRest() {
        job("PROG1", "PENDING", LocalDateTime.now());
        ExportProgress progress = new ExportProgress(jdbc, "PROG1");

        progress.packing(4, 1_000L, 1, 500, true);
        ExportJobEntity half = read("PROG1");
        assertThat(half.getStatus()).isEqualTo("PROCESSING");
        assertThat(half.getPhase()).isEqualTo("PACKING");
        assertThat(half.getProgress()).isEqualTo(ExportProgress.PACKING_SHARE / 2);
        assertThat(half.getDoneItems()).isEqualTo(1);
        assertThat(half.getTotalItems()).isEqualTo(4);
        assertThat(half.getDoneBytes()).isEqualTo(500);
        assertThat(half.getTotalBytes()).isEqualTo(1_000);

        // 库里记的大小比实际小：读多了也不能越过打包阶段的份额。
        progress.packing(4, 1_000L, 4, 1_400, true);
        assertThat(read("PROG1").getProgress()).isEqualTo(ExportProgress.PACKING_SHARE);

        progress.uploading(4, 2_000, 1_000, true);
        ExportJobEntity uploading = read("PROG1");
        assertThat(uploading.getPhase()).isEqualTo("UPLOADING");
        assertThat(uploading.getProgress())
                .isEqualTo(ExportProgress.PACKING_SHARE + (ExportProgress.UPLOADING_END - ExportProgress.PACKING_SHARE) / 2);
        progress.uploading(4, 2_000, 2_000, true);
        assertThat(read("PROG1").getProgress()).isEqualTo(ExportProgress.UPLOADING_END);
    }

    @Test
    void withoutRecordedSizesProgressCountsPhotos() {
        job("PROG2", "PENDING", LocalDateTime.now());
        new ExportProgress(jdbc, "PROG2").packing(3, null, 1, 12_345, true);

        assertThat(read("PROG2").getProgress()).isEqualTo(ExportProgress.PACKING_SHARE / 3);
    }

    @Test
    void writesAreThrottledUnlessForced() {
        job("PROG3", "PENDING", LocalDateTime.now());
        AtomicLong clock = new AtomicLong(0);
        ExportProgress progress = new ExportProgress(jdbc, "PROG3", clock::get);

        progress.packing(10, 1_000L, 0, 100, false);
        clock.addAndGet(ExportProgress.MIN_INTERVAL_NANOS / 2);
        progress.packing(10, 1_000L, 0, 200, false);
        assertThat(read("PROG3").getDoneBytes()).as("间隔太短，第二次不写").isEqualTo(100);

        progress.packing(10, 1_000L, 1, 300, true);
        assertThat(read("PROG3").getDoneBytes()).as("强制写不看间隔").isEqualTo(300);

        clock.addAndGet(ExportProgress.MIN_INTERVAL_NANOS);
        progress.packing(10, 1_000L, 1, 400, false);
        assertThat(read("PROG3").getDoneBytes()).isEqualTo(400);
    }

    @Test
    void neverRevivesAFinishedOrInterruptedJob() {
        job("PROG4", "FAILED", LocalDateTime.now());
        new ExportProgress(jdbc, "PROG4").packing(2, 10L, 1, 5, true);

        assertThat(read("PROG4").getStatus()).isEqualTo("FAILED");
    }

    @Test
    void uploadCounterReportsEveryByteReadThrough() throws Exception {
        List<Long> seen = new ArrayList<>();
        try (var input = new ExportProgress.CountingInputStream(new ByteArrayInputStream(new byte[10_000]), seen::add)) {
            input.read();
            input.readNBytes(4_999);
            input.skip(1_000);
            input.readAllBytes();
        }
        assertThat(seen).first().isEqualTo(1L);
        assertThat(seen).last().isEqualTo(10_000L);
        assertThat(seen).isSorted();
    }

    @Test
    void watchdogFailsJobsThatStoppedMakingProgress() {
        LocalDateTime now = LocalDateTime.now();
        job("DOG1", "PROCESSING", now.minus(ExportJobWatchdog.STALLED_PROCESSING).minusMinutes(1));
        job("DOG2", "PROCESSING", now.minusMinutes(1));
        job("DOG3", "PENDING", now.minus(ExportJobWatchdog.STALLED_PENDING).minusMinutes(1));
        // 排队十几分钟是正常的（线程池满了），还不能判死。
        job("DOG4", "PENDING", now.minus(ExportJobWatchdog.STALLED_PROCESSING).minusMinutes(5));
        job("DOG5", "SUCCEEDED", now.minusDays(2));

        assertThat(watchdog.failStalled(now)).isEqualTo(2);

        assertThat(read("DOG1").getStatus()).isEqualTo("FAILED");
        assertThat(read("DOG1").getErrorMessage()).isEqualTo(ExportJobWatchdog.INTERRUPTED_MESSAGE);
        assertThat(read("DOG2").getStatus()).isEqualTo("PROCESSING");
        assertThat(read("DOG3").getStatus()).isEqualTo("FAILED");
        assertThat(read("DOG4").getStatus()).isEqualTo("PENDING");
        assertThat(read("DOG5").getStatus()).isEqualTo("SUCCEEDED");
    }
}
