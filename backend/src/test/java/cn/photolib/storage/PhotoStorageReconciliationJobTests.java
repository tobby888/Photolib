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
