package cn.photolib.photo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PreviewRegenerationCoordinatorTests {
    @Mock
    private PreviewRegenerationService regeneration;
    @Mock
    private TaskScheduler scheduler;

    private final Deque<Runnable> submitted = new ArrayDeque<>();

    @Test
    void reportsProgressAndCompletionWithoutHoldingApplicationStartup() {
        when(regeneration.synchronizeCompressionRatio(any())).thenAnswer(invocation -> {
            PreviewRegenerationService.ProgressListener listener = invocation.getArgument(0);
            listener.started(4);
            listener.progressed(2, 4);
            listener.progressed(4, 4);
            return new PreviewRegenerationService.Result(true, 3, 4, 0);
        });
        PreviewRegenerationCoordinator coordinator = coordinator();

        assertThat(coordinator.status().status())
                .isEqualTo(PreviewRegenerationCoordinator.State.PENDING);

        coordinator.regenerateAfterApplicationReady();
        verifyNoInteractions(regeneration);
        assertThat(submitted).hasSize(1);
        runNext();

        assertThat(coordinator.status().status())
                .isEqualTo(PreviewRegenerationCoordinator.State.SUCCEEDED);
        assertThat(coordinator.status().processed()).isEqualTo(4);
        assertThat(coordinator.status().total()).isEqualTo(4);
        assertThat(coordinator.status().percentage()).isEqualTo(100);
    }

    @Test
    void reportsFailureButDoesNotRethrowIt() {
        when(regeneration.synchronizeCompressionRatio(any()))
                .thenThrow(new IllegalStateException("OSS unavailable"));
        PreviewRegenerationCoordinator coordinator = coordinator();

        coordinator.regenerateAfterApplicationReady();
        runNext();

        assertThat(coordinator.status().status())
                .isEqualTo(PreviewRegenerationCoordinator.State.FAILED);
        assertThat(coordinator.status().errorMessage()).isNotBlank();
    }

    @Test
    void rerunsAfterStorageReconciliationRequestsARepair() {
        when(regeneration.repairPreviews(
                any(PreviewRepairRequestedEvent.class), any()))
                .thenReturn(new PreviewRegenerationService.Result(true, 0, 1, 0));
        when(regeneration.synchronizeCompressionRatio(any()))
                .thenReturn(new PreviewRegenerationService.Result(false, 0, 0, 0));
        PreviewRegenerationCoordinator coordinator = coordinator();

        PreviewRepairRequestedEvent event = new PreviewRepairRequestedEvent(
                java.util.List.of(42L), PreviewProfile.configured(0.6));
        coordinator.regenerateAfterApplicationReady();
        coordinator.regenerateAfterStorageReconciliation(event);
        assertThat(submitted).hasSize(1);
        runNext();

        assertThat(coordinator.status().status())
                .isEqualTo(PreviewRegenerationCoordinator.State.SUCCEEDED);
        verify(regeneration).repairPreviews(
                org.mockito.ArgumentMatchers.eq(event), any());
    }

    @Test
    void retriesFailedBootstrapWithoutBlockingSchedulerOrFloodingExecutorQueue() {
        when(regeneration.synchronizeCompressionRatio(any()))
                .thenThrow(new IllegalStateException("OSS unavailable"))
                .thenReturn(new PreviewRegenerationService.Result(false, 0, 0, 0));
        PreviewRegenerationCoordinator coordinator = coordinator();

        coordinator.regenerateAfterApplicationReady();
        coordinator.regenerateAfterApplicationReady();
        assertThat(submitted).hasSize(1);

        runNext();
        assertThat(coordinator.status().status())
                .isEqualTo(PreviewRegenerationCoordinator.State.FAILED);
        ArgumentCaptor<Runnable> retry = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(retry.capture(), any(Instant.class));
        verify(regeneration, times(1)).synchronizeCompressionRatio(any());

        retry.getValue().run();
        assertThat(submitted).hasSize(1);
        verify(regeneration, times(1)).synchronizeCompressionRatio(any());

        runNext();
        assertThat(coordinator.status().status())
                .isEqualTo(PreviewRegenerationCoordinator.State.SUCCEEDED);
        verify(regeneration, times(2)).synchronizeCompressionRatio(any());
    }

    @Test
    void reportsSucceededWithFallbackCountSoGalleriesStillRefresh() {
        when(regeneration.synchronizeCompressionRatio(any())).thenAnswer(invocation -> {
            PreviewRegenerationService.ProgressListener listener = invocation.getArgument(0);
            listener.started(3);
            listener.progressed(3, 3);
            return new PreviewRegenerationService.Result(true, 1, 2, 1);
        });
        PreviewRegenerationCoordinator coordinator = coordinator();

        coordinator.regenerateAfterApplicationReady();
        runNext();

        assertThat(coordinator.status().status())
                .isEqualTo(PreviewRegenerationCoordinator.State.SUCCEEDED);
        assertThat(coordinator.status().message()).contains("1 张暂时回退为原图");
        assertThat(coordinator.status().errorMessage()).isNull();
    }

    private PreviewRegenerationCoordinator coordinator() {
        return new PreviewRegenerationCoordinator(
                regeneration, submitted::addLast, scheduler, Duration.ofSeconds(5));
    }

    private void runNext() {
        Runnable task = submitted.removeFirst();
        task.run();
    }
}
