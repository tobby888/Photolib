package cn.photolib.uploadlimit;

import cn.photolib.backup.BackupProperties;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.upload.SafeImageZipExtractor;
import cn.photolib.recruitment.upload.RecruitmentUploadProperties;
import cn.photolib.storage.StorageProperties;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 不起 Spring 上下文：真实跑一遍 Flyway 建表，直接用 JdbcClient 驱动服务。 */
class UploadLimitServiceTests {
    private static final long MIB = 1024L * 1024;

    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:upload_limits_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(dataSource);
    }

    @Test
    void withoutAdministratorChangesEveryLimitIsItsDefault() {
        UploadLimitService service = service(100 * MIB, new RecruitmentUploadProperties(null, null, null, null),
                DataSize.ofMegabytes(512));

        assertThat(service.value(UploadLimit.PHOTO_IMAGE_MAX_BYTES)).isEqualTo(100 * MIB);
        assertThat(service.value(UploadLimit.PHOTO_ZIP_MAX_BYTES)).isEqualTo(1_500_000_000L);
        assertThat(service.count(UploadLimit.PHOTO_ZIP_MAX_IMAGES)).isEqualTo(100);
        assertThat(service.value(UploadLimit.RECRUITMENT_IMAGE_MAX_BYTES)).isEqualTo(20 * MIB);
        assertThat(service.value(UploadLimit.RECRUITMENT_ZIP_MAX_BYTES)).isEqualTo(200 * MIB);
        assertThat(service.count(UploadLimit.RECRUITMENT_MAX_IMAGES)).isEqualTo(20);
        assertThat(service.value(UploadLimit.FORM_FILE_MAX_BYTES)).isEqualTo(50 * MIB);
        assertThat(service.value(UploadLimit.INLINE_IMAGE_MAX_BYTES)).isEqualTo(5 * MIB);
        assertThat(service.value(UploadLimit.AVATAR_MAX_BYTES)).isEqualTo(MIB);
        assertThat(service.value(UploadLimit.DATABASE_BACKUP_MAX_BYTES)).isEqualTo(512 * MIB);
        assertThat(service.describe(UploadLimit.PHOTO_IMAGE_MAX_BYTES)).isEqualTo("100 MiB");
        assertThat(service.describe(UploadLimit.PHOTO_ZIP_MAX_IMAGES)).isEqualTo("100 张");
        assertThat(service.currentValues()).containsKeys("PHOTO_ZIP_MAX_IMAGES", "FORM_FILE_MAX_BYTES")
                .hasSize(UploadLimit.values().length);
        assertThat(service.settings()).noneMatch(UploadLimitService.LimitView::customized);
    }

    @Test
    void configurationStillSeedsTheDefaultsItUsedToOwn() {
        UploadLimitService service = service(50 * MIB, new RecruitmentUploadProperties(30, 10 * MIB, 100 * MIB, null),
                DataSize.ofMegabytes(256));

        // storage.image-max-bytes 是处理管线的上限，站内单张既不能默认超过它，也不能被调到它之上。
        UploadLimitService.LimitView photo = view(service, UploadLimit.PHOTO_IMAGE_MAX_BYTES);
        assertThat(photo.defaultValue()).isEqualTo(50 * MIB);
        assertThat(photo.max()).isEqualTo(50 * MIB);
        assertThat(service.count(UploadLimit.RECRUITMENT_MAX_IMAGES)).isEqualTo(30);
        assertThat(service.value(UploadLimit.RECRUITMENT_IMAGE_MAX_BYTES)).isEqualTo(10 * MIB);
        assertThat(service.value(UploadLimit.RECRUITMENT_ZIP_MAX_BYTES)).isEqualTo(100 * MIB);
        assertThat(service.value(UploadLimit.DATABASE_BACKUP_MAX_BYTES)).isEqualTo(256 * MIB);
        assertThatThrownBy(() -> service.update(Map.of("PHOTO_IMAGE_MAX_BYTES", 60 * MIB), 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void administratorChangesTakeEffectImmediatelyAndSurviveARestart() {
        UploadLimitService service = defaultService();

        Map<String, UploadLimitService.Change> changes = service.update(Map.of(
                "PHOTO_ZIP_MAX_IMAGES", 300L,
                "PHOTO_ZIP_MAX_BYTES", 3_000_000_000L,
                "INLINE_IMAGE_MAX_BYTES", 5 * MIB), 7L);

        // 没变的项（正文插图本来就是 5 MiB）不进审计详情。
        assertThat(changes).containsOnlyKeys("PHOTO_ZIP_MAX_IMAGES", "PHOTO_ZIP_MAX_BYTES");
        assertThat(changes.get("PHOTO_ZIP_MAX_IMAGES").from()).isEqualTo(100);
        assertThat(changes.get("PHOTO_ZIP_MAX_IMAGES").to()).isEqualTo(300);
        assertThat(service.count(UploadLimit.PHOTO_ZIP_MAX_IMAGES)).isEqualTo(300);
        assertThat(service.value(UploadLimit.PHOTO_ZIP_MAX_BYTES)).isEqualTo(3_000_000_000L);
        assertThat(view(service, UploadLimit.PHOTO_ZIP_MAX_IMAGES).customized()).isTrue();
        assertThat(jdbc.sql("SELECT updated_by FROM upload_limit_setting WHERE limit_key='PHOTO_ZIP_MAX_IMAGES'")
                .query(Long.class).single()).isEqualTo(7L);

        UploadLimitService restarted = defaultService();
        assertThat(restarted.count(UploadLimit.PHOTO_ZIP_MAX_IMAGES)).isEqualTo(300);

        service.update(Map.of("PHOTO_ZIP_MAX_IMAGES", 120L), 7L);
        assertThat(service.count(UploadLimit.PHOTO_ZIP_MAX_IMAGES)).isEqualTo(120);
    }

    @Test
    void settingTheDefaultValueAgainDropsTheOverride() {
        UploadLimitService service = defaultService();
        service.update(Map.of("FORM_FILE_MAX_BYTES", 200 * MIB), 1L);

        service.update(Map.of("FORM_FILE_MAX_BYTES", 50 * MIB), 1L);

        assertThat(jdbc.sql("SELECT COUNT(*) FROM upload_limit_setting").query(Long.class).single()).isZero();
        assertThat(view(service, UploadLimit.FORM_FILE_MAX_BYTES).customized()).isFalse();
    }

    @Test
    void anOutOfRangeValueRejectsTheWholeBatch() {
        UploadLimitService service = defaultService();
        Map<String, Long> request = new LinkedHashMap<>();
        request.put("PHOTO_ZIP_MAX_IMAGES", 200L);
        request.put("PHOTO_ZIP_MAX_BYTES", 6_000_000_000L);

        assertThatThrownBy(() -> service.update(request, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ZIP 压缩包");
        assertThatThrownBy(() -> service.update(Map.of("PHOTO_ZIP_MAX_IMAGES", 0L), 1L))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.update(Map.of("NOT_A_LIMIT", 1L), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未知");

        assertThat(service.count(UploadLimit.PHOTO_ZIP_MAX_IMAGES)).isEqualTo(100);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM upload_limit_setting").query(Long.class).single()).isZero();
    }

    @Test
    void storedValuesOutsideTheCurrentRangeAreClampedAndUnknownKeysIgnored() {
        jdbc.sql("INSERT INTO upload_limit_setting (limit_key, limit_value) VALUES ('PHOTO_IMAGE_MAX_BYTES', :value)")
                .param("value", 500 * MIB).update();
        jdbc.sql("INSERT INTO upload_limit_setting (limit_key, limit_value) VALUES ('RETIRED_LIMIT', 1)").update();

        UploadLimitService service = defaultService();

        assertThat(service.value(UploadLimit.PHOTO_IMAGE_MAX_BYTES)).isEqualTo(100 * MIB);
        assertThat(service.currentValues()).doesNotContainKey("RETIRED_LIMIT");
    }

    @Test
    void zipQuotaFollowsTheManagedCountAndPerImageSize() {
        UploadLimitService service = defaultService();

        SafeImageZipExtractor.Limits gallery = service.galleryZipLimits();
        assertThat(gallery.maxImageCount()).isEqualTo(100);
        assertThat(gallery.maxImageBytes()).isEqualTo(100 * MIB);
        assertThat(gallery.maxExpandedBytes()).isEqualTo(100 * 100 * MIB);

        SafeImageZipExtractor.Limits recruitment = service.recruitmentZipLimits();
        assertThat(recruitment.maxImageCount()).isEqualTo(20);
        assertThat(recruitment.maxExpandedBytes()).isEqualTo(400 * MIB);

        // 张数调到 1000 时，解压总量仍被防压缩炸弹的 10 GiB 兜底封住。
        service.update(Map.of("PHOTO_ZIP_MAX_IMAGES", 1000L), 1L);
        SafeImageZipExtractor.Limits raised = service.galleryZipLimits();
        assertThat(raised.maxImageCount()).isEqualTo(1000);
        assertThat(raised.maxExpandedBytes()).isEqualTo(10L * 1024 * MIB);
    }

    @Test
    void countRefusesByteLimits() {
        assertThatThrownBy(() -> defaultService().count(UploadLimit.PHOTO_IMAGE_MAX_BYTES))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private UploadLimitService defaultService() {
        return service(100 * MIB, new RecruitmentUploadProperties(null, null, null, null), DataSize.ofMegabytes(512));
    }

    private UploadLimitService service(long imageMaxBytes, RecruitmentUploadProperties recruitment,
                                       DataSize backupMax) {
        StorageProperties storage = new StorageProperties("local", null, null, null, null, null, null, null,
                null, null, Duration.ofMinutes(15), Duration.ofMinutes(15), Duration.ZERO, Duration.ofDays(30),
                10 * MIB, imageMaxBytes, 0.6);
        BackupProperties backup = new BackupProperties(false, "0 0 0 * * *", Duration.ofDays(30), 7,
                backupMax, DataSize.ofGigabytes(2));
        return new UploadLimitService(jdbc, storage, recruitment, backup);
    }

    private static UploadLimitService.LimitView view(UploadLimitService service, UploadLimit limit) {
        return service.settings().stream().filter(view -> view.key().equals(limit.name())).findFirst().orElseThrow();
    }
}
