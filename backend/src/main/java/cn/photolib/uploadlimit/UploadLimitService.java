package cn.photolib.uploadlimit;

import cn.photolib.backup.BackupProperties;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.upload.ImageUploadPolicy;
import cn.photolib.common.upload.SafeImageZipExtractor;
import cn.photolib.recruitment.upload.RecruitmentUploadProperties;
import cn.photolib.storage.StorageProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 所有上传限额的唯一来源。业务代码按 {@link UploadLimit} 取当前生效值，不再各自写死常量。
 *
 * <p>数据库只存管理员改过的项；其余项用默认值。默认值大多是内置的，少数沿用配置文件
 * （图库单张上限 {@code photolib.storage.image-max-bytes}、招募上传额度
 * {@code photolib.recruitment.upload.*}、备份上传上限 {@code photolib.backup.max-upload-bytes}），
 * 这样升级上来的部署在管理员动手之前行为完全不变。</p>
 *
 * <p>读取走一份内存快照，每 {@link #CACHE_TTL_NANOS} 重新读一次库：本实例改完立即生效，
 * 多实例部署时其他实例最迟一个周期后跟上。读库失败时沿用上一份快照（首次失败则用默认值），
 * 不让一次数据库抖动把所有上传都拦掉。</p>
 */
@Service
@Slf4j
public class UploadLimitService {
    static final long CACHE_TTL_NANOS = TimeUnit.SECONDS.toNanos(30);
    /** ZIP 解压总量的绝对上限，防压缩炸弹；与单张上限 × 张数取小。 */
    static final long MAX_EXPANDED_BYTES = ImageUploadPolicy.MAX_EXPANDED_BYTES;
    private static final int GALLERY_FILE_NAME_CODE_POINTS = 255;

    private final JdbcClient jdbc;
    private final Map<UploadLimit, Range> ranges;
    private final int recruitmentFileNameLength;
    private volatile Snapshot snapshot;

    public UploadLimitService(JdbcClient jdbc, StorageProperties storage,
                              RecruitmentUploadProperties recruitment, BackupProperties backup) {
        this.jdbc = jdbc;
        Map<UploadLimit, Range> resolved = new EnumMap<>(UploadLimit.class);
        for (UploadLimit limit : UploadLimit.values()) {
            resolved.put(limit, new Range(limit.min(), limit.defaultValue(), limit.max()));
        }
        // 处理管线（解码、预览、编辑）按 storage.image-max-bytes 读原图，站内上限不能比它松。
        long photoCeiling = Math.min(storage.imageMaxBytes(), ImageUploadPolicy.MAX_IMAGE_BYTES);
        resolved.put(UploadLimit.PHOTO_IMAGE_MAX_BYTES,
                Range.of(UploadLimit.PHOTO_IMAGE_MAX_BYTES, photoCeiling, photoCeiling));
        resolved.put(UploadLimit.RECRUITMENT_IMAGE_MAX_BYTES,
                Range.of(UploadLimit.RECRUITMENT_IMAGE_MAX_BYTES, recruitment.maxImageBytes(),
                        UploadLimit.RECRUITMENT_IMAGE_MAX_BYTES.max()));
        resolved.put(UploadLimit.RECRUITMENT_ZIP_MAX_BYTES,
                Range.of(UploadLimit.RECRUITMENT_ZIP_MAX_BYTES, recruitment.maxArchiveBytes(),
                        UploadLimit.RECRUITMENT_ZIP_MAX_BYTES.max()));
        resolved.put(UploadLimit.RECRUITMENT_MAX_IMAGES,
                Range.of(UploadLimit.RECRUITMENT_MAX_IMAGES, recruitment.maxImageCount(),
                        UploadLimit.RECRUITMENT_MAX_IMAGES.max()));
        resolved.put(UploadLimit.DATABASE_BACKUP_MAX_BYTES,
                Range.of(UploadLimit.DATABASE_BACKUP_MAX_BYTES, backup.maxUploadBytes().toBytes(),
                        UploadLimit.DATABASE_BACKUP_MAX_BYTES.max()));
        this.ranges = Collections.unmodifiableMap(resolved);
        this.recruitmentFileNameLength = recruitment.maxFileNameLength();
    }

    /** 某一项当前生效的值（字节数或张数）。 */
    public long value(UploadLimit limit) {
        return current().values().get(limit);
    }

    /** 非字节类限额（张数、个数、次数、QPS、秒数）的当前值。 */
    public int count(UploadLimit limit) {
        if (limit.unit() == UploadLimit.Unit.BYTES) {
            throw new IllegalArgumentException(limit + " 是字节类限额");
        }
        return Math.toIntExact(value(limit));
    }

    /** 「单张图片不得超过 100 MiB」「每天最多 50 个」这类提示里用的写法。 */
    public String describe(UploadLimit limit) {
        return format(limit, value(limit));
    }

    /** 站内（含选题上传链接）ZIP 解包的限额。 */
    public SafeImageZipExtractor.Limits galleryZipLimits() {
        return zipLimits(UploadLimit.PHOTO_ZIP_MAX_IMAGES, UploadLimit.PHOTO_IMAGE_MAX_BYTES,
                GALLERY_FILE_NAME_CODE_POINTS);
    }

    /** 公开招募 ZIP 解包的限额。 */
    public SafeImageZipExtractor.Limits recruitmentZipLimits() {
        return zipLimits(UploadLimit.RECRUITMENT_MAX_IMAGES, UploadLimit.RECRUITMENT_IMAGE_MAX_BYTES,
                recruitmentFileNameLength);
    }

    /**
     * 解压总量按「张数 × 单张」推出来，再用 {@link #MAX_EXPANDED_BYTES} 封顶：它只是防压缩
     * 炸弹的兜底，不该比另外两项更早把一个合法的压缩包拦下。
     */
    private SafeImageZipExtractor.Limits zipLimits(UploadLimit count, UploadLimit imageBytes,
                                                   int fileNameCodePoints) {
        int maxImages = count(count);
        long maxImageBytes = value(imageBytes);
        long expanded = maxImageBytes > MAX_EXPANDED_BYTES / maxImages
                ? MAX_EXPANDED_BYTES : maxImages * maxImageBytes;
        return new SafeImageZipExtractor.Limits(maxImages, maxImageBytes, expanded, fileNameCodePoints);
    }

    /** 给前端的全部当前值，键是 {@link UploadLimit} 的名字。 */
    public Map<String, Long> currentValues() {
        Map<String, Long> values = new LinkedHashMap<>();
        current().values().forEach((limit, value) -> values.put(limit.name(), value));
        return values;
    }

    /** 管理页面用的完整视图：当前值、默认值、可调范围、说明。 */
    public List<LimitView> settings() {
        Snapshot current = current();
        List<LimitView> views = new ArrayList<>();
        for (UploadLimit limit : UploadLimit.values()) {
            Range range = ranges.get(limit);
            long value = current.values().get(limit);
            views.add(new LimitView(limit.name(), limit.group().name(), limit.group().label(),
                    limit.label(), limit.description(), limit.unit().name(), limit.unit().label(), value,
                    range.defaultValue(), range.min(), range.max(), value != range.defaultValue()));
        }
        return views;
    }

    /**
     * 批量修改。只传要改的项；值等于默认值时删掉覆盖记录，回到「跟随默认」。
     * 任何一项越界都整批拒绝，不会只改一半。
     *
     * @return 真正发生变化的项（旧值 → 新值），供审计日志记录
     */
    @Transactional
    public Map<String, Change> update(Map<String, Long> requested, Long operatorId) {
        if (requested == null || requested.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "没有要修改的上传限额");
        }
        Map<UploadLimit, Long> accepted = new EnumMap<>(UploadLimit.class);
        requested.forEach((key, value) -> {
            UploadLimit limit = parse(key);
            Range range = ranges.get(limit);
            if (value == null || value < range.min() || value > range.max()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "「" + limit.group().label() + " · " + limit.label() + "」必须在 "
                                + format(limit, range.min()) + " 到 " + format(limit, range.max()) + " 之间");
            }
            accepted.put(limit, value);
        });
        Map<UploadLimit, Long> before = load();
        Map<String, Change> changes = new LinkedHashMap<>();
        accepted.forEach((limit, value) -> {
            Range range = ranges.get(limit);
            if (value == range.defaultValue()) {
                jdbc.sql("DELETE FROM upload_limit_setting WHERE limit_key=:key")
                        .param("key", limit.name())
                        .update();
            } else {
                int updated = jdbc.sql("""
                        UPDATE upload_limit_setting
                        SET limit_value=:value, updated_by=:operator, updated_at=CURRENT_TIMESTAMP
                        WHERE limit_key=:key
                        """)
                        .param("value", value)
                        .param("operator", operatorId)
                        .param("key", limit.name())
                        .update();
                if (updated == 0) {
                    jdbc.sql("""
                            INSERT INTO upload_limit_setting (limit_key, limit_value, updated_by)
                            VALUES (:key, :value, :operator)
                            """)
                            .param("key", limit.name())
                            .param("value", value)
                            .param("operator", operatorId)
                            .update();
                }
            }
            long previous = before.get(limit);
            if (previous != value) changes.put(limit.name(), new Change(previous, value));
        });
        invalidateNowAndAfterCompletion();
        return changes;
    }

    /**
     * 本线程随后的读取要看到刚写的值，所以先清一次；事务结束（提交或回滚）后再清一次：
     * 提交前别的线程可能把旧值读进了快照，回滚时本线程读进快照的又是没生效的新值。
     */
    private void invalidateNowAndAfterCompletion() {
        snapshot = null;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    snapshot = null;
                }
            });
        }
    }

    private Snapshot current() {
        Snapshot cached = snapshot;
        if (cached != null && System.nanoTime() - cached.loadedAt() < CACHE_TTL_NANOS) {
            return cached;
        }
        Map<UploadLimit, Long> values;
        try {
            values = load();
        } catch (DataAccessException exception) {
            log.warn("读取上传限额设置失败，沿用{}", cached == null ? "默认值" : "上一次读到的设置", exception);
            if (cached != null) return cached;
            values = defaults();
        }
        Snapshot fresh = new Snapshot(Collections.unmodifiableMap(values), System.nanoTime());
        snapshot = fresh;
        return fresh;
    }

    private Map<UploadLimit, Long> load() {
        Map<UploadLimit, Long> values = defaults();
        jdbc.sql("SELECT limit_key, limit_value FROM upload_limit_setting")
                .query((rs, rowNum) -> Map.entry(rs.getString("limit_key"), rs.getLong("limit_value")))
                .list()
                .forEach(row -> {
                    UploadLimit limit = find(row.getKey());
                    // 删掉的旧键直接忽略；越界值（比如运维后来调低了配置文件里的上限）夹回范围内。
                    if (limit != null) values.put(limit, ranges.get(limit).clamp(row.getValue()));
                });
        return values;
    }

    private Map<UploadLimit, Long> defaults() {
        Map<UploadLimit, Long> values = new EnumMap<>(UploadLimit.class);
        ranges.forEach((limit, range) -> values.put(limit, range.defaultValue()));
        return values;
    }

    private static UploadLimit parse(String key) {
        UploadLimit limit = find(key);
        if (limit == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "未知的上传限额：" + key);
        }
        return limit;
    }

    private static UploadLimit find(String key) {
        if (key == null) return null;
        try {
            return UploadLimit.valueOf(key);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static String format(UploadLimit limit, long value) {
        return limit.unit() == UploadLimit.Unit.BYTES
                ? ImageUploadPolicy.describe(value) : value + " " + limit.unit().label();
    }

    record Range(long min, long defaultValue, long max) {
        /** 用内置下限，把配置文件给的默认值和上限整理成一个自洽的范围。 */
        static Range of(UploadLimit limit, long configuredDefault, long ceiling) {
            long max = Math.max(limit.min(), ceiling);
            long defaultValue = Math.min(Math.max(configuredDefault, limit.min()), max);
            return new Range(limit.min(), defaultValue, max);
        }

        long clamp(long value) {
            return Math.min(Math.max(value, min), max);
        }
    }

    private record Snapshot(Map<UploadLimit, Long> values, long loadedAt) {
    }

    /** {@code unitLabel} 是非字节类限额跟在数字后面的字样（张、个、次、次/秒、秒），字节类为空。 */
    public record LimitView(String key, String group, String groupLabel, String label, String description,
                            String unit, String unitLabel, long value, long defaultValue, long min, long max,
                            boolean customized) {
    }

    public record Change(long from, long to) {
    }
}
