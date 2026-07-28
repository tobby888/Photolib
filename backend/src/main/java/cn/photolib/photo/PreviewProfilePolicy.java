package cn.photolib.photo;

import cn.photolib.storage.StorageProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Defines which of the three profile sources is authoritative. During startup
 * the normalized environment profile is authoritative. Once the startup audit
 * has atomically synchronized objects and the database, every runtime caller
 * must read the database and must never fall back to the environment.
 */
@Component
@RequiredArgsConstructor
public class PreviewProfilePolicy {
    private final StorageProperties properties;
    private final PreviewProfileRepository repository;
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.BOOTSTRAPPING);

    public PreviewProfile bootstrapTarget() {
        return PreviewProfile.configured(properties.previewCompressionRatio());
    }

    public PreviewProfile profileForNewPreview() {
        if (phase.get() == Phase.BOOTSTRAPPING) {
            return bootstrapTarget();
        }
        return repository.requireCurrent();
    }

    /**
     * Captures both the profile used to encode a new preview and the database
     * state that is allowed when that preview is finally published. Encoding
     * happens outside a database transaction, so callers must use the returned
     * permit in the final photo-row CAS instead of trusting an earlier read.
     *
     * <p>While bootstrapping, the environment target remains authoritative. A
     * commit is therefore valid while the database is still at the state seen
     * here, or after another instance has already switched it to that target.
     * Once running, only the exact database profile read here is valid.</p>
     */
    public CommitPermit permitForNewPreview() {
        if (phase.get() == Phase.BOOTSTRAPPING) {
            return CommitPermit.bootstrapping(
                    bootstrapTarget(), repository.findStored().orElse(null));
        }
        return CommitPermit.running(repository.requireCurrent());
    }

    /** Must be called inside the short transaction that publishes the photo. */
    public boolean lockAndValidateForCommit(CommitPermit permit) {
        Objects.requireNonNull(permit, "permit");
        PreviewProfileRepository.StoredProfile current =
                repository.findStoredForUpdate().orElse(null);
        return permit.allows(current);
    }

    public PreviewProfile requireRunningProfile() {
        if (phase.get() != Phase.RUNNING) {
            throw new IllegalStateException("预览图 profile 尚未完成启动核对");
        }
        return repository.requireCurrent();
    }

    public void completeBootstrap(PreviewProfile expected) {
        PreviewProfile stored = repository.requireCurrent();
        if (!stored.equals(expected)) {
            throw new IllegalStateException("启动核对后的数据库预览图 profile 与环境配置不一致");
        }
        phase.set(Phase.RUNNING);
    }

    Phase phase() {
        return phase.get();
    }

    enum Phase {
        BOOTSTRAPPING,
        RUNNING
    }

    public record CommitPermit(PreviewProfile profile, boolean bootstrapping,
                               PreviewProfileRepository.StoredProfile observedDatabaseProfile) {
        private static CommitPermit bootstrapping(
                PreviewProfile profile,
                PreviewProfileRepository.StoredProfile observedDatabaseProfile) {
            return new CommitPermit(profile, true, observedDatabaseProfile);
        }

        private static CommitPermit running(PreviewProfile profile) {
            return new CommitPermit(profile, false, null);
        }

        public int bootstrappingFlag() {
            return bootstrapping ? 1 : 0;
        }

        public int observedDatabaseProfileFlag() {
            return observedDatabaseProfile == null ? 0 : 1;
        }

        public BigDecimal observedCompressionRatioOrTarget() {
            return observedDatabaseProfile == null
                    ? profile.compressionRatio()
                    : observedDatabaseProfile.compressionRatio();
        }

        public String observedGeneratorFingerprintOrTarget() {
            return observedDatabaseProfile == null
                    ? profile.generatorFingerprint()
                    : observedDatabaseProfile.generatorFingerprint();
        }

        boolean allows(PreviewProfileRepository.StoredProfile current) {
            if (current != null && current.matches(profile)) return true;
            if (!bootstrapping) return false;
            if (observedDatabaseProfile == null || current == null) {
                return observedDatabaseProfile == current;
            }
            BigDecimal observedRatio = observedDatabaseProfile.compressionRatio();
            BigDecimal currentRatio = current.compressionRatio();
            boolean sameRatio = observedRatio == null || currentRatio == null
                    ? observedRatio == currentRatio
                    : observedRatio.compareTo(currentRatio) == 0;
            return sameRatio && Objects.equals(
                    observedDatabaseProfile.generatorFingerprint(),
                    current.generatorFingerprint());
        }
    }
}
