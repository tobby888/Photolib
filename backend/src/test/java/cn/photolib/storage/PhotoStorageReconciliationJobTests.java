package cn.photolib.storage;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PhotoStorageReconciliationJobTests {
    private static final LocalDateTime FIRST = LocalDateTime.of(2026, 10, 27, 10, 0);
    private static final LocalDateTime SECOND = LocalDateTime.of(2026, 10, 27, 11, 0);

    @Test
    void firstIncrementalAfterStartupFallsBackToAFullPass() {
        PhotoStorageReconciliationService service = mock(PhotoStorageReconciliationService.class);
        when(service.databaseNow()).thenReturn(FIRST);
        PhotoStorageReconciliationJob job = new PhotoStorageReconciliationJob(service);

        job.runIncremental();

        verify(service).reconcile();
        verify(service, never()).reconcileChangedSince(any());
        assertThat(job.watermark()).isEqualTo(FIRST);
    }

    @Test
    void laterIncrementalsLookBackFromThePreviousStartWithAnOverlap() {
        PhotoStorageReconciliationService service = mock(PhotoStorageReconciliationService.class);
        when(service.databaseNow()).thenReturn(FIRST, SECOND);
        PhotoStorageReconciliationJob job = new PhotoStorageReconciliationJob(service);

        job.runFull();
        job.runIncremental();

        verify(service).reconcileChangedSince(FIRST.minus(PhotoStorageReconciliationJob.OVERLAP));
        assertThat(job.watermark()).isEqualTo(SECOND);
    }

    @Test
    void schedulerThreadReturnsImmediatelyAndDuplicateRequestsDoNotPileUp() throws Exception {
        PhotoStorageReconciliationService service = mock(PhotoStorageReconciliationService.class);
        java.util.concurrent.CountDownLatch running = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(service.databaseNow()).thenReturn(FIRST);
        when(service.reconcile()).thenAnswer(invocation -> {
            running.countDown();
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return new PhotoStorageReconciliationService.ReconciliationResult(0, 0, 0);
        });
        PhotoStorageReconciliationJob job = new PhotoStorageReconciliationJob(service);
        try {
            long started = System.nanoTime();
            job.scheduleFull();
            assertThat(running.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            // 正在跑的时候：再投一次全量排一个，再投更多也只排这一个。
            job.scheduleFull();
            job.scheduleFull();
            job.scheduleFull();
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(2));
            release.countDown();
        } finally {
            job.shutdown();
        }
        verify(service, org.mockito.Mockito.atMost(2)).reconcile();
    }

    // ---------------------------------------------------------------- 定时全量：没新图就跳过

    /** 04:10 那次全量：上一次全量在前一天同一时刻。 */
    private static final LocalDateTime LAST_FULL = LocalDateTime.of(2026, 10, 27, 4, 10);

    private PhotoStorageReconciliationJob jobAfterAFullPass(PhotoStorageReconciliationService service,
                                                            int maxSkipDays, LocalDateTime now) {
        when(service.databaseNow()).thenReturn(LAST_FULL, now);
        PhotoStorageReconciliationJob job = new PhotoStorageReconciliationJob(service, maxSkipDays);
        job.runFull();
        org.mockito.Mockito.clearInvocations(service);
        return job;
    }

    @Test
    void aQuietDayWithoutNewPhotosOrOpenAlertsSkipsTheFullPass() {
        PhotoStorageReconciliationService service = mock(PhotoStorageReconciliationService.class);
        PhotoStorageReconciliationJob job = jobAfterAFullPass(service, 7, LAST_FULL.plusDays(1));

        assertThat(job.runScheduledFull()).isFalse();

        verify(service, never()).reconcile();
        // 「新图」从上次全量开始时往前多看一个 OVERLAP，和增量同一个理由。
        verify(service).hasNewPhotosSince(LAST_FULL.minus(PhotoStorageReconciliationJob.OVERLAP));
        assertThat(job.lastFull()).isEqualTo(LAST_FULL);
    }

    @Test
    void newPhotosSinceTheLastFullPassRunIt() {
        PhotoStorageReconciliationService service = mock(PhotoStorageReconciliationService.class);
        PhotoStorageReconciliationJob job = jobAfterAFullPass(service, 7, LAST_FULL.plusDays(1));
        when(service.hasNewPhotosSince(any())).thenReturn(true);

        assertThat(job.runScheduledFull()).isTrue();

        verify(service).reconcile();
        assertThat(job.lastFull()).isEqualTo(LAST_FULL.plusDays(1));
    }

    /** 缺失 / HEAD 失败告警只有全量能标为已解决：有它们在，没新图也得跑。 */
    @Test
    void openStorageAlertsKeepTheFullPassRunning() {
        PhotoStorageReconciliationService service = mock(PhotoStorageReconciliationService.class);
        PhotoStorageReconciliationJob job = jobAfterAFullPass(service, 7, LAST_FULL.plusDays(1));
        when(service.hasOpenStorageAlerts()).thenReturn(true);

        assertThat(job.runScheduledFull()).isTrue();
        verify(service).reconcile();
    }

    /** 桶里丢图与有没有新图无关：连续跳过满 N 天必须跑一次。触发时刻差几分钟不该让它多拖一天。 */
    @Test
    void theBackstopForcesAFullPassAfterMaxSkipDays() {
        PhotoStorageReconciliationService service = mock(PhotoStorageReconciliationService.class);
        PhotoStorageReconciliationJob early = jobAfterAFullPass(service, 7, LAST_FULL.plusDays(6));
        assertThat(early.runScheduledFull()).isFalse();

        PhotoStorageReconciliationService again = mock(PhotoStorageReconciliationService.class);
        PhotoStorageReconciliationJob due = jobAfterAFullPass(again, 7, LAST_FULL.plusDays(7).minusMinutes(3));
        assertThat(due.runScheduledFull()).isTrue();
        verify(again).reconcile();
        // 到期就不必再问库里有没有新图、有没有告警。
        verify(again, never()).hasNewPhotosSince(any());
    }

    @Test
    void zeroMaxSkipDaysTurnsSkippingOff() {
        PhotoStorageReconciliationService service = mock(PhotoStorageReconciliationService.class);
        PhotoStorageReconciliationJob job = jobAfterAFullPass(service, 0, LAST_FULL.plusDays(1));

        assertThat(job.runScheduledFull()).isTrue();
        verify(service).reconcile();
        verify(service, never()).hasNewPhotosSince(any());
    }

    /** 进度只在内存里：重启后本进程还没做过全量，定时全量照跑，不问库。 */
    @Test
    void theFirstScheduledFullAfterStartupNeverSkips() {
        PhotoStorageReconciliationService service = mock(PhotoStorageReconciliationService.class);
        when(service.databaseNow()).thenReturn(FIRST);
        PhotoStorageReconciliationJob job = new PhotoStorageReconciliationJob(service, 7);

        assertThat(job.runScheduledFull()).isTrue();
        verify(service).reconcile();
        verify(service, never()).hasNewPhotosSince(any());
    }

    /** 增量巡检触发的那次「启动后全量」也算数：之后的定时全量可以据此跳过。 */
    @Test
    void theStartupFullTriggeredByAnIncrementalCountsAsTheLastFullPass() {
        PhotoStorageReconciliationService service = mock(PhotoStorageReconciliationService.class);
        when(service.databaseNow()).thenReturn(FIRST, FIRST.plusHours(18));
        PhotoStorageReconciliationJob job = new PhotoStorageReconciliationJob(service, 7);

        job.runIncremental();
        assertThat(job.lastFull()).isEqualTo(FIRST);
        assertThat(job.runScheduledFull()).isFalse();
        verify(service, times(1)).reconcile();
    }

    @Test
    void aFailedFullPassMeansTheNextScheduledOneCannotSkip() {
        PhotoStorageReconciliationService service = mock(PhotoStorageReconciliationService.class);
        PhotoStorageReconciliationJob job = jobAfterAFullPass(service, 7, LAST_FULL.plusDays(1));
        when(service.reconcile()).thenThrow(new IllegalStateException("OSS 不可用"));
        when(service.hasNewPhotosSince(any())).thenReturn(true);
        assertThatThrownBy(job::runScheduledFull).isInstanceOf(IllegalStateException.class);

        // 第二天依然没新图，但上一次全量失败了，照跑。
        org.mockito.Mockito.reset(service);
        when(service.databaseNow()).thenReturn(LAST_FULL.plusDays(2));
        assertThat(job.lastFull()).isNull();
        assertThat(job.runScheduledFull()).isTrue();
        verify(service).reconcile();
    }

    @Test
    void maxSkipDaysOutsideZeroToThirtyIsRejected() {
        PhotoStorageReconciliationService service = mock(PhotoStorageReconciliationService.class);
        assertThatThrownBy(() -> new PhotoStorageReconciliationJob(service, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PhotoStorageReconciliationJob(service, 31))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFailedPassKeepsTheOldWatermarkSoNothingIsSkipped() {
        PhotoStorageReconciliationService service = mock(PhotoStorageReconciliationService.class);
        when(service.databaseNow()).thenReturn(FIRST, SECOND, SECOND);
        when(service.reconcileChangedSince(any())).thenThrow(new IllegalStateException("OSS 不可用"));
        PhotoStorageReconciliationJob job = new PhotoStorageReconciliationJob(service);
        job.runFull();

        assertThatThrownBy(job::runIncremental).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(job::runIncremental).isInstanceOf(IllegalStateException.class);

        assertThat(job.watermark()).isEqualTo(FIRST);
        verify(service, times(2)).reconcileChangedSince(FIRST.minus(PhotoStorageReconciliationJob.OVERLAP));
    }
}
