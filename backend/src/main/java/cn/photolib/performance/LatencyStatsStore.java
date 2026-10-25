package cn.photolib.performance;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 每个功能每小时一张 {@link LatencyHistogram}，落在 {@code perf_latency_hourly} 表里，用来算一周的分位数。
 *
 * <p>记录先进内存，定时（默认每分钟）合并进库：同一小时同一功能已有行就把计数加上去，没有就插一行。
 * 读的时候把库里的行和内存里还没落库的那部分加在一起，刚发生的请求立刻算得进去。
 * 落库和读取共用一把锁，"已从内存取走、还没写进库"的那一瞬间读不到半截数据。</p>
 *
 * <p>假设单实例部署：合并用"先查后改"，两台机器同时合并同一行会丢计数。唯一索引会让其中一边
 * 插入失败、整批退回内存下轮重试，不会写出重复行。</p>
 */
@Slf4j
@Component
public class LatencyStatsStore {
    private final JdbcClient jdbc;
    private final Clock clock;
    private final PerformanceProperties properties;

    private final Object lock = new Object();
    private Map<Key, Entry> pending = new HashMap<>();

    public LatencyStatsStore(JdbcClient jdbc, Clock clock, PerformanceProperties properties) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
    }

    record Key(LocalDateTime hour, String kind, String opKey) {
    }

    static final class Entry {
        String label;
        String module;
        final LatencyHistogram histogram;

        Entry(String label, String module, LatencyHistogram histogram) {
            this.label = label;
            this.module = module;
            this.histogram = histogram;
        }
    }

    /** 一个功能在某段时间内合并后的直方图。 */
    public record Series(String kind, String opKey, String label, String module, LatencyHistogram histogram) {
    }

    /** 一个功能在某一天合并后的直方图（趋势图用）。 */
    public record DailySeries(LocalDateTime day, String kind, String opKey, LatencyHistogram histogram) {
    }

    public void record(String kind, String opKey, String label, String module, long micros, boolean success,
                       long atMillis) {
        if (opKey == null) return;
        LocalDateTime hour = LocalDateTime.ofInstant(Instant.ofEpochMilli(atMillis), clock.getZone())
                .truncatedTo(ChronoUnit.HOURS);
        synchronized (lock) {
            Entry entry = pending.computeIfAbsent(new Key(hour, kind, truncate(opKey, 255)),
                    key -> new Entry(label, module, new LatencyHistogram()));
            if (label != null) entry.label = label;
            if (module != null) entry.module = module;
            entry.histogram.record(micros, success);
        }
    }

    @Scheduled(fixedDelayString = "${photolib.performance.histogram-flush-interval:60s}",
            initialDelayString = "${photolib.performance.histogram-flush-interval:60s}")
    public void flushScheduled() {
        try {
            flush();
        } catch (RuntimeException failure) {
            log.warn("耗时直方图落库失败，数据留在内存里下轮重试", failure);
        }
    }

    @PreDestroy
    void flushOnShutdown() {
        try {
            flush();
        } catch (RuntimeException failure) {
            log.warn("关闭前耗时直方图落库失败，最近一段的分位数据会丢失", failure);
        }
    }

    public void flush() {
        synchronized (lock) {
            if (pending.isEmpty()) return;
            Map<Key, Entry> batch = pending;
            pending = new HashMap<>();
            Map<Key, Entry> unwritten = new HashMap<>(batch);
            try {
                for (Map.Entry<Key, Entry> item : batch.entrySet()) {
                    upsert(item.getKey(), item.getValue());
                    unwritten.remove(item.getKey());
                }
            } catch (RuntimeException failure) {
                // 没写进去的放回内存，和这期间新记的合并，下轮再试。
                unwritten.forEach((key, entry) -> pending.merge(key, entry, (fresh, old) -> {
                    old.histogram.merge(fresh.histogram);
                    if (fresh.label != null) old.label = fresh.label;
                    if (fresh.module != null) old.module = fresh.module;
                    return old;
                }));
                throw failure;
            }
        }
    }

    private void upsert(Key key, Entry entry) {
        List<Row> existing = jdbc.sql("""
                        SELECT id, kind, op_key, label, module, sample_count, error_count, total_micros, max_micros,
                               buckets, hour_start
                        FROM perf_latency_hourly
                        WHERE hour_start = :hour AND kind = :kind AND op_key = :opKey AND scheme = :scheme
                        """)
                .param("hour", key.hour()).param("kind", key.kind()).param("opKey", key.opKey())
                .param("scheme", LatencyHistogram.SCHEME)
                .query(Row.class).list();
        LatencyHistogram merged = entry.histogram.copy();
        if (existing.isEmpty()) {
            jdbc.sql("""
                            INSERT INTO perf_latency_hourly
                                (hour_start, kind, op_key, label, module, scheme, sample_count, error_count,
                                 total_micros, max_micros, buckets)
                            VALUES (:hour, :kind, :opKey, :label, :module, :scheme, :count, :errors, :total, :max,
                                    :buckets)
                            """)
                    .param("hour", key.hour()).param("kind", key.kind()).param("opKey", key.opKey())
                    .param("label", truncate(entry.label, 128)).param("module", truncate(entry.module, 64))
                    .param("scheme", LatencyHistogram.SCHEME)
                    .param("count", merged.count()).param("errors", merged.errors())
                    .param("total", merged.totalMicros()).param("max", merged.maxMicros())
                    .param("buckets", merged.encodeBuckets())
                    .update();
            return;
        }
        Row row = existing.getFirst();
        merged.merge(row.histogram());
        jdbc.sql("""
                        UPDATE perf_latency_hourly
                        SET label = :label, module = :module, sample_count = :count, error_count = :errors,
                            total_micros = :total, max_micros = :max, buckets = :buckets
                        WHERE id = :id
                        """)
                .param("label", truncate(entry.label != null ? entry.label : row.label(), 128))
                .param("module", truncate(entry.module != null ? entry.module : row.module(), 64))
                .param("count", merged.count()).param("errors", merged.errors())
                .param("total", merged.totalMicros()).param("max", merged.maxMicros())
                .param("buckets", merged.encodeBuckets())
                .param("id", row.id())
                .update();
    }

    record Row(long id, String kind, String opKey, String label, String module, long sampleCount, long errorCount,
               long totalMicros, long maxMicros, String buckets, LocalDateTime hourStart) {
        LatencyHistogram histogram() {
            return LatencyHistogram.decode(buckets, sampleCount, errorCount, totalMicros, maxMicros);
        }
    }

    /** 每个功能在 {@code [from, now]} 内的合并直方图，含内存里还没落库的部分。 */
    public List<Series> since(LocalDateTime from) {
        Map<String, Entry> merged = new HashMap<>();
        Map<String, Snapshot> first = new HashMap<>();
        for (Snapshot item : snapshot(from)) {
            String key = item.kind() + "\n" + item.opKey();
            first.putIfAbsent(key, item);
            Entry entry = merged.computeIfAbsent(key, k -> new Entry(null, null, new LatencyHistogram()));
            entry.histogram.merge(item.histogram());
            if (item.label() != null) entry.label = item.label();
            if (item.module() != null) entry.module = item.module();
        }
        List<Series> result = new ArrayList<>(merged.size());
        merged.forEach((key, entry) -> {
            Snapshot sample = first.get(key);
            result.add(new Series(sample.kind(), sample.opKey(), entry.label, entry.module, entry.histogram));
        });
        return result;
    }

    /** 按天合并：同一天、同一功能一条。 */
    public List<DailySeries> dailySince(LocalDateTime from) {
        Map<String, DailySeries> merged = new HashMap<>();
        for (Snapshot item : snapshot(from)) {
            LocalDateTime day = item.hour().truncatedTo(ChronoUnit.DAYS);
            merged.computeIfAbsent(day + "\n" + item.kind() + "\n" + item.opKey(),
                            key -> new DailySeries(day, item.kind(), item.opKey(), new LatencyHistogram()))
                    .histogram().merge(item.histogram());
        }
        return new ArrayList<>(merged.values());
    }

    private record Snapshot(LocalDateTime hour, String kind, String opKey, String label, String module,
                            LatencyHistogram histogram) {
    }

    private List<Snapshot> snapshot(LocalDateTime from) {
        List<Snapshot> result = new ArrayList<>();
        synchronized (lock) {
            List<Row> rows = jdbc.sql("""
                            SELECT id, kind, op_key, label, module, sample_count, error_count, total_micros,
                                   max_micros, buckets, hour_start
                            FROM perf_latency_hourly
                            WHERE hour_start >= :from AND scheme = :scheme
                            """)
                    .param("from", from).param("scheme", LatencyHistogram.SCHEME)
                    .query(Row.class).list();
            for (Row row : rows) {
                result.add(new Snapshot(row.hourStart(), row.kind(), row.opKey(), row.label(), row.module(),
                        row.histogram()));
            }
            pending.forEach((key, entry) -> {
                if (!key.hour().isBefore(from)) {
                    result.add(new Snapshot(key.hour(), key.kind(), key.opKey(), entry.label, entry.module,
                            entry.histogram.copy()));
                }
            });
        }
        return result;
    }

    @Scheduled(cron = "0 17 3 * * *", zone = "Asia/Shanghai")
    public void pruneScheduled() {
        try {
            prune();
        } catch (RuntimeException failure) {
            log.warn("清理过期耗时直方图失败", failure);
        }
    }

    public int prune() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(properties.histogramRetention())
                .truncatedTo(ChronoUnit.HOURS);
        return jdbc.sql("DELETE FROM perf_latency_hourly WHERE hour_start < :cutoff")
                .param("cutoff", cutoff).update();
    }

    static String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }
}
