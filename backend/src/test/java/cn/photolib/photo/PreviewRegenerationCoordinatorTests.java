package cn.photolib.photo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PreviewRegenerationCoordinatorTests {
    @Mock
    private PreviewRegenerationService regeneration;

    @Test
    void reportsProgressAndCompletionWithoutHoldingApplicationStartup() {
        when(regeneration.synchronizeCompressionRatio(any())).thenAnswer(invocation -> {
            PreviewRegenerationService.ProgressListener listener = invocation.getArgument(0);
            listener.started(4);
            listener.progressed(2, 4);
            listener.progressed(4, 4);
            return new PreviewRegenerationService.Result(true, 3, 4);
        });
        PreviewRegenerationCoordinator coordinator = new PreviewRegenerationCoordinator(regeneration);

        assertThat(coordinator.status().status())
                .isEqualTo(PreviewRegenerationCoordinator.State.PENDING);

        coordinator.regenerateAfterApplicationReady();

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
        PreviewRegenerationCoordinator coordinator = new PreviewRegenerationCoordinator(regeneration);

        coordinator.regenerateAfterApplicationReady();

        assertThat(coordinator.status().status())
                .isEqualTo(PreviewRegenerationCoordinator.State.FAILED);
        assertThat(coordinator.status().errorMessage()).isNotBlank();
    }
}
