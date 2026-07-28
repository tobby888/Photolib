package cn.photolib.photo;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PreviewProfileRepository {
    static final int SETTING_ID = 1;

    private final JdbcClient jdbc;

    public Optional<StoredProfile> findStored() {
        return findStored("");
    }

    public Optional<StoredProfile> findStoredForUpdate() {
        return findStored(" FOR UPDATE");
    }

    private Optional<StoredProfile> findStored(String lockClause) {
        return jdbc.sql("""
                SELECT compression_ratio, generator_fingerprint
                FROM preview_setting
                WHERE id=:id
                """ + lockClause)
                .param("id", SETTING_ID)
                .query((rs, rowNum) -> new StoredProfile(
                        rs.getBigDecimal("compression_ratio"),
                        rs.getString("generator_fingerprint")))
                .optional();
    }

    public PreviewProfile requireCurrent() {
        StoredProfile stored = findStored().orElseThrow(() ->
                new IllegalStateException("数据库尚未保存预览图 profile"));
        PreviewProfile profile;
        try {
            profile = stored.toProfile();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("数据库预览图 profile 非法", exception);
        }
        if (!profile.isCurrentGenerator()) {
            throw new IllegalStateException(
                    "数据库预览图生成器与当前程序不一致，必须先完成启动重建");
        }
        return profile;
    }

    public boolean matches(PreviewProfile expected) {
        return findStored().map(stored -> stored.matches(expected)).orElse(false);
    }

    public void save(PreviewProfile profile, StoredProfile previous) {
        if (previous == null) {
            jdbc.sql("""
                    INSERT INTO preview_setting (id, compression_ratio, generator_fingerprint)
                    VALUES (:id, :ratio, :fingerprint)
                    """)
                    .param("id", SETTING_ID)
                    .param("ratio", profile.compressionRatio())
                    .param("fingerprint", profile.generatorFingerprint())
                    .update();
            return;
        }
        int updated = jdbc.sql("""
                UPDATE preview_setting
                SET compression_ratio=:ratio, generator_fingerprint=:fingerprint,
                    updated_at=CURRENT_TIMESTAMP
                WHERE id=:id AND compression_ratio=:previousRatio
                  AND generator_fingerprint=:previousFingerprint
                """)
                .param("id", SETTING_ID)
                .param("ratio", profile.compressionRatio())
                .param("fingerprint", profile.generatorFingerprint())
                .param("previousRatio", previous.compressionRatio())
                .param("previousFingerprint", previous.generatorFingerprint())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("数据库预览图 profile 在切换期间发生并发变化");
        }
    }

    public record StoredProfile(BigDecimal compressionRatio, String generatorFingerprint) {
        public PreviewProfile toProfile() {
            return new PreviewProfile(compressionRatio, generatorFingerprint);
        }

        public boolean matches(PreviewProfile expected) {
            if (compressionRatio == null || generatorFingerprint == null) return false;
            try {
                return PreviewProfile.normalizeRatio(compressionRatio)
                        .compareTo(expected.compressionRatio()) == 0
                        && generatorFingerprint.equals(expected.generatorFingerprint());
            } catch (RuntimeException exception) {
                return false;
            }
        }
    }
}
