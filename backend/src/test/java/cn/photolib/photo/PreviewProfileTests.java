package cn.photolib.photo;

import cn.photolib.storage.ObjectStorageService;
import cn.photolib.storage.StorageProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PreviewProfileTests {
    @Test
    void normalizesRatioAndBuildsFormatSpecificObjectMetadata() {
        PreviewProfile profile = PreviewProfile.configured(0.6000001d);

        assertThat(profile.ratioText()).isEqualTo("0.6000");
        assertThat(profile.objectMetadata("image/jpeg", "a".repeat(64)))
                .containsEntry(PreviewProfile.METADATA_RATIO, "0.6000")
                .containsEntry(PreviewProfile.METADATA_EFFECTIVE_QUALITY, "60")
                .containsEntry(PreviewProfile.METADATA_GENERATOR,
                        PreviewProfile.CURRENT_GENERATOR_FINGERPRINT)
                .containsEntry(PreviewProfile.METADATA_SHA256, "a".repeat(64));
        assertThat(profile.objectMetadata("image/png", "b".repeat(64)))
                .containsEntry(PreviewProfile.METADATA_EFFECTIVE_QUALITY, "lossless");
        assertThat(PreviewProfile.CURRENT_GENERATOR_FINGERPRINT.length())
                .isLessThanOrEqualTo(128);
    }

    @Test
    void rejectsRawRatioOutsideBoundsBeforeFourDecimalNormalization() {
        assertThatThrownBy(() -> new PreviewProfile(
                new BigDecimal("1.00001"), PreviewProfile.CURRENT_GENERATOR_FINGERPRINT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不超过 1");
        assertThatThrownBy(() -> new PreviewProfile(
                new BigDecimal("0.00001"), PreviewProfile.CURRENT_GENERATOR_FINGERPRINT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("大于 0");
    }

    @Test
    void rejectsMissingOrDifferentOssProfileMetadata() {
        PreviewProfile expected = PreviewProfile.configured(0.6);
        ObjectStorageService.ObjectInfo missing =
                new ObjectStorageService.ObjectInfo(10, "image/jpeg", Map.of());
        ObjectStorageService.ObjectInfo otherRatio = new ObjectStorageService.ObjectInfo(
                10, "image/jpeg",
                PreviewProfile.configured(0.7).objectMetadata("image/jpeg", "a".repeat(64)));

        assertThat(expected.matches(missing, "image/jpeg")).isFalse();
        assertThat(expected.matches(otherRatio, "image/jpeg")).isFalse();
        assertThat(expected.matches(new ObjectStorageService.ObjectInfo(
                        10, "image/jpeg",
                        expected.objectMetadata("image/jpeg", "a".repeat(64))),
                "image/jpeg", "a".repeat(64))).isTrue();
    }

    @Test
    void runningPolicyRereadsDatabaseAndNeverFallsBackToEnvironment() {
        StorageProperties properties = mock(StorageProperties.class);
        PreviewProfileRepository repository = mock(PreviewProfileRepository.class);
        when(properties.previewCompressionRatio()).thenReturn(0.6d);
        PreviewProfilePolicy policy = new PreviewProfilePolicy(properties, repository);

        assertThat(policy.profileForNewPreview().compressionRatio())
                .isEqualByComparingTo(new BigDecimal("0.6000"));
        verifyNoInteractions(repository);

        PreviewProfile startup = PreviewProfile.configured(0.6);
        when(repository.requireCurrent()).thenReturn(startup);
        policy.completeBootstrap(startup);

        PreviewProfile databaseChanged = PreviewProfile.configured(0.7);
        when(repository.requireCurrent()).thenReturn(databaseChanged);
        assertThat(policy.profileForNewPreview()).isEqualTo(databaseChanged);

        when(repository.requireCurrent()).thenThrow(
                new IllegalStateException("database profile missing"));
        assertThatThrownBy(policy::profileForNewPreview)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database profile missing");
    }

    @Test
    void bootstrapCommitPermitUsesEnvironmentAndCapturesDatabaseGuardState() {
        StorageProperties properties = mock(StorageProperties.class);
        PreviewProfileRepository repository = mock(PreviewProfileRepository.class);
        when(properties.previewCompressionRatio()).thenReturn(0.6d);
        PreviewProfileRepository.StoredProfile oldDatabase =
                new PreviewProfileRepository.StoredProfile(
                        new BigDecimal("0.5000"), "legacy/unknown");
        when(repository.findStored()).thenReturn(java.util.Optional.of(oldDatabase));
        PreviewProfilePolicy policy = new PreviewProfilePolicy(properties, repository);

        PreviewProfilePolicy.CommitPermit permit = policy.permitForNewPreview();

        assertThat(permit.profile()).isEqualTo(PreviewProfile.configured(0.6));
        assertThat(permit.bootstrapping()).isTrue();
        assertThat(permit.observedDatabaseProfile()).isEqualTo(oldDatabase);
        assertThat(permit.observedCompressionRatioOrTarget())
                .isEqualByComparingTo(new BigDecimal("0.5000"));
        assertThat(permit.observedGeneratorFingerprintOrTarget())
                .isEqualTo("legacy/unknown");
    }
}
