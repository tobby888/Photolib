package cn.photolib.performance;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.PageResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.util.PublicId;
import cn.photolib.common.util.SpreadsheetText;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.storage.StorageProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 性能快照的生命周期：开始 → 记录（最长 72 小时）→ 停止（管理员手动 / 到点自动 / 事件数到上限）
 * → 导出 CSV 到对象存储 → 删除库里的明细。
 *
 * <p><b>CSV 永久保留在对象存储里，没有任何清理任务会删它</b>；快照记录本身（{@code perf_snapshot_session}）
 * 也永久保留，用来列出和下载。库里的明细只是导出前的暂存，导出成功后立刻整批删掉。
 * 导出失败时明细保留、状态 FAILED，管理员可以重新导出，不会丢数据。</p>
 *
 * <p>重启：记录中的快照在下次启动时继续记（还没到计划结束时间）或直接收尾导出（已经过了）；
 * 导出到一半的重新导出。CSV 里会有一条 MARK 事件标出重启续记的时刻，中间那段空白是停机时间。</p>
 *
 * <p>假设单实例部署：同一时刻只有一个快照在记，靠本类的同步和库里"有没有 RECORDING"判断。</p>
 */
@Slf4j
@Service
public class PerformanceSnapshotService {
    public static final String STATUS_RECORDING = "RECORDING";
    public static final String STATUS_EXPORTING = "EXPORTING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String REASON_MANUAL = "MANUAL";
    public static final String REASON_TIMEOUT = "TIMEOUT";
    public static final String REASON_LIMIT = "LIMIT";
    public static final String FILE_EVENTS = "events";
    public static final String FILE_SAMPLES = "samples";

    /** 用户要求的硬上限：配置写得再大也不超过 72 小时。 */
    static final Duration HARD_MAX_DURATION = Duration.ofHours(72);
    static final Duration MIN_DURATION = Duration.ofMinutes(1);
    private static final int EXPORT_PAGE = 5000;
    private static final int DELETE_CHUNK = 10_000;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String CSV_TYPE = "text/csv; charset=utf-8";
    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter KEY_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final JdbcClient jdbc;
    private final SnapshotRecorder recorder;
    private final ObjectStorageService storage;
    private final StorageProperties storageProperties;
    private final PerformanceProperties properties;
    private final Clock clock;
    private final ExecutorService exporter;

    private volatile LocalDateTime plannedEnd;

    public PerformanceSnapshotService(JdbcClient jdbc, SnapshotRecorder recorder, ObjectStorageService storage,
                                      StorageProperties storageProperties, PerformanceProperties properties,
                                      Clock clock) {
        this.jdbc = jdbc;
        this.recorder = recorder;
        this.storage = storage;
        this.storageProperties = storageProperties;
        this.properties = properties;
        this.clock = clock;
        this.exporter = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "perf-snapshot-export");
            thread.setDaemon(true);
            return thread;
        });
        recorder.onLimitReached(id -> exporter.execute(() -> {
            try {
                stopInternal(id, REASON_LIMIT, null);
            } catch (RuntimeException failure) {
                log.warn("性能快照 {} 事件数到上限后自动停止失败", id, failure);
            }
        }));
    }

    @PreDestroy
    void shutdown() {
        exporter.shutdown();
        try {
            if (!exporter.awaitTermination(30, TimeUnit.SECONDS)) exporter.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            exporter.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ 视图

    public record SessionView(String id, String status, String note, Long startedBy, String startedByName,
                              LocalDateTime startedAt, LocalDateTime plannedEndAt, LocalDateTime endedAt,
                              String stopReason, Long stoppedBy, String stoppedByName, int sampleIntervalSeconds,
                              long eventCount, long sampleCount, long droppedCount, int queuedEvents,
                              Long eventsSize, Long samplesSize, LocalDateTime exportedAt, String errorMessage,
                              boolean downloadable) {
    }

    public record Overview(SessionView current, long maxDurationMinutes, int sampleIntervalSeconds,
                           long maxEvents) {
    }

    public record DownloadLink(String url, String fileName, Instant expiresAt) {
    }

    private record Row(String id, String status, String note, Long startedBy, String startedByName,
                       LocalDateTime startedAt, LocalDateTime plannedEndAt, LocalDateTime endedAt, String stopReason,
                       Long stoppedBy, String stoppedByName, int sampleIntervalSeconds, long eventCount,
                       long sampleCount, long droppedCount, String eventsObjectKey, Long eventsSize,
                       String samplesObjectKey, Long samplesSize, LocalDateTime exportedAt, String errorMessage) {
    }

    private static final String COLUMNS = """
            id, status, note, started_by, started_by_name, started_at, planned_end_at, ended_at, stop_reason,
            stopped_by, stopped_by_name, sample_interval_seconds, event_count, sample_count, dropped_count,
            events_object_key, events_size, samples_object_key, samples_size, exported_at, error_message
            """;

    private SessionView view(Row row) {
        boolean live = STATUS_RECORDING.equals(row.status()) && row.id().equals(recorder.sessionId());
        return new SessionView(row.id(), row.status(), row.note(), row.startedBy(), row.startedByName(),
                row.startedAt(), row.plannedEndAt(), row.endedAt(), row.stopReason(), row.stoppedBy(),
                row.stoppedByName(), row.sampleIntervalSeconds(),
                live ? recorder.writtenEvents() : row.eventCount(),
                live ? recorder.writtenSamples() : row.sampleCount(),
                live ? recorder.droppedEvents() : row.droppedCount(),
                live ? recorder.queuedEvents() : 0,
                row.eventsSize(), row.samplesSize(), row.exportedAt(), row.errorMessage(),
                STATUS_COMPLETED.equals(row.status()) && row.eventsObjectKey() != null);
    }

    private Row find(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM perf_snapshot_session WHERE id = :id")
                .param("id", id).query(Row.class).optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "性能快照不存在"));
    }

    private List<Row> withStatus(String status) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM perf_snapshot_session WHERE status = :status ORDER BY started_at")
                .param("status", status).query(Row.class).list();
    }

    public Overview overview(AuthenticatedUser user) {
        requireAdmin(user);
        List<Row> recording = withStatus(STATUS_RECORDING);
        return new Overview(recording.isEmpty() ? null : view(recording.getLast()), maxDuration().toMinutes(),
                sampleIntervalSeconds(), properties.snapshotMaxEvents());
    }

    public PageResponse<SessionView> list(int page, int pageSize, AuthenticatedUser user) {
        requireAdmin(user);
        int size = Math.clamp(pageSize, 1, MAX_PAGE_SIZE);
        int current = Math.max(1, page);
        long total = jdbc.sql("SELECT COUNT(*) FROM perf_snapshot_session").query(Long.class).single();
        List<SessionView> items = jdbc.sql("SELECT " + COLUMNS
                        + " FROM perf_snapshot_session ORDER BY started_at DESC, id DESC LIMIT :limit OFFSET :offset")
                .param("limit", size).param("offset", (long) (current - 1) * size)
                .query(Row.class).list().stream().map(this::view).toList();
        return new PageResponse<>(items, current, size, total, (total + size - 1) / size);
    }

    public SessionView get(String id, AuthenticatedUser user) {
        requireAdmin(user);
        return view(find(id));
    }

    // ------------------------------------------------------------------ 开始 / 停止

    public synchronized SessionView start(AuthenticatedUser user, long durationMinutes, String note) {
        requireAdmin(user);
        Duration duration = Duration.ofMinutes(durationMinutes);
        if (duration.compareTo(MIN_DURATION) < 0 || duration.compareTo(maxDuration()) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "记录时长须在 1 分钟到 " + maxDuration().toHours() + " 小时之间");
        }
        if (recorder.active() || !withStatus(STATUS_RECORDING).isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "已有性能快照正在记录，请先停止");
        }
        String cleanNote = note == null || note.isBlank() ? null : note.strip();
        if (cleanNote != null && cleanNote.length() > 200) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "备注不能超过 200 字");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        String id = PublicId.next();
        jdbc.sql("""
                        INSERT INTO perf_snapshot_session
                            (id, status, note, started_by, started_by_name, started_at, planned_end_at,
                             sample_interval_seconds)
                        VALUES (:id, :status, :note, :userId, :userName, :startedAt, :plannedEnd, :interval)
                        """)
                .param("id", id).param("status", STATUS_RECORDING).param("note", cleanNote)
                .param("userId", user.id()).param("userName", user.displayName())
                .param("startedAt", now).param("plannedEnd", now.plus(duration))
                .param("interval", sampleIntervalSeconds())
                .update();
        plannedEnd = now.plus(duration);
        recorder.activate(id, 0, 0, 0);
        recorder.mark("START", "开始记录，计划时长 " + durationMinutes + " 分钟"
                + (cleanNote == null ? "" : "；备注：" + cleanNote));
        log.info("性能快照 {} 开始记录，计划 {} 分钟，发起人 {}", id, durationMinutes, user.username());
        return view(find(id));
    }

    public SessionView stop(String id, AuthenticatedUser user) {
        requireAdmin(user);
        stopInternal(id, REASON_MANUAL, user);
        return view(find(id));
    }

    private synchronized void stopInternal(String id, String reason, AuthenticatedUser user) {
        Row row = find(id);
        if (!STATUS_RECORDING.equals(row.status())) {
            if (REASON_MANUAL.equals(reason)) {
                throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "这次性能快照已经停止");
            }
            return;
        }
        long events = row.eventCount();
        long samples = row.sampleCount();
        long dropped = row.droppedCount();
        if (id.equals(recorder.sessionId())) {
            recorder.mark("STOP", describeReason(reason));
            recorder.deactivate();
            events = recorder.writtenEvents();
            samples = recorder.writtenSamples();
            dropped = recorder.droppedEvents();
        } else {
            // 不是本进程在记（重启后收尾）：以库里实际行数为准。
            events = countEvents(id);
            samples = countSamples(id);
        }
        plannedEnd = null;
        jdbc.sql("""
                        UPDATE perf_snapshot_session
                        SET status = :status, ended_at = :endedAt, stop_reason = :reason, stopped_by = :userId,
                            stopped_by_name = :userName, event_count = :events, sample_count = :samples,
                            dropped_count = :dropped
                        WHERE id = :id AND status = :recording
                        """)
                .param("status", STATUS_EXPORTING).param("endedAt", LocalDateTime.now(clock))
                .param("reason", reason).param("userId", user == null ? null : user.id())
                .param("userName", user == null ? null : user.displayName())
                .param("events", events).param("samples", samples).param("dropped", dropped)
                .param("id", id).param("recording", STATUS_RECORDING)
                .update();
        log.info("性能快照 {} 已停止（{}），共 {} 条事件、{} 次采样、丢弃 {} 条，开始导出",
                id, describeReason(reason), events, samples, dropped);
        exporter.execute(() -> export(id));
    }

    public SessionView retryExport(String id, AuthenticatedUser user) {
        requireAdmin(user);
        synchronized (this) {
            Row row = find(id);
            if (!STATUS_FAILED.equals(row.status())) {
                throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "只有导出失败的快照可以重新导出");
            }
            jdbc.sql("UPDATE perf_snapshot_session SET status = :exporting, error_message = NULL WHERE id = :id")
                    .param("exporting", STATUS_EXPORTING).param("id", id).update();
        }
        exporter.execute(() -> export(id));
        return view(find(id));
    }

    public DownloadLink downloadLink(String id, String file, AuthenticatedUser user) {
        requireAdmin(user);
        Row row = find(id);
        if (!STATUS_COMPLETED.equals(row.status())) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "快照还没导出完成");
        }
        String key = FILE_SAMPLES.equals(file) ? row.samplesObjectKey() : row.eventsObjectKey();
        if (!FILE_EVENTS.equals(file) && !FILE_SAMPLES.equals(file) || key == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "未知的文件类型");
        }
        String fileName = "performance-" + row.startedAt().format(KEY_TIME) + "-" + file + ".csv";
        ObjectStorageService.SignedUrl signed = storage.presignGet(key, fileName, storageProperties.downloadUrlTtl());
        return new DownloadLink(signed.url().toString(), fileName, signed.expiresAt());
    }

    // ------------------------------------------------------------------ 定时与启动

    /** 到点自动停止；顺带把进度写回快照记录，进程被强杀时列表里也能看到大致的量。 */
    @Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
    public void tick() {
        String id = recorder.sessionId();
        if (id == null) return;
        try {
            LocalDateTime end = plannedEnd;
            if (end != null && !LocalDateTime.now(clock).isBefore(end)) {
                stopInternal(id, REASON_TIMEOUT, null);
                return;
            }
            PerformanceContext.silently(() -> jdbc.sql("""
                            UPDATE perf_snapshot_session
                            SET event_count = :events, sample_count = :samples, dropped_count = :dropped
                            WHERE id = :id AND status = :recording
                            """)
                    .param("events", recorder.writtenEvents()).param("samples", recorder.writtenSamples())
                    .param("dropped", recorder.droppedEvents()).param("id", id)
                    .param("recording", STATUS_RECORDING).update());
        } catch (RuntimeException failure) {
            log.warn("性能快照 {} 的定时检查失败", id, failure);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resumeAfterStartup() {
        try {
            resume();
        } catch (RuntimeException failure) {
            log.error("启动时恢复性能快照失败", failure);
        }
    }

    synchronized void resume() {
        LocalDateTime now = LocalDateTime.now(clock);
        for (Row row : withStatus(STATUS_RECORDING)) {
            if (row.id().equals(recorder.sessionId())) continue;
            if (!now.isBefore(row.plannedEndAt()) || recorder.active()) {
                stopInternal(row.id(), REASON_TIMEOUT, null);
                continue;
            }
            plannedEnd = row.plannedEndAt();
            recorder.activate(row.id(), countEvents(row.id()), countSamples(row.id()), row.droppedCount());
            recorder.mark("RESUME", "应用重启后继续记录，此前的空白为停机时间");
            log.info("性能快照 {} 在应用重启后继续记录，计划 {} 结束", row.id(), row.plannedEndAt());
        }
        for (Row row : withStatus(STATUS_EXPORTING)) {
            exporter.execute(() -> export(row.id()));
        }
        purgeExported();
    }

    /** 已导出完成、却还有明细残留的快照（导出后删除那一步失败过），把残留删掉。 */
    void purgeExported() {
        PerformanceContext.silently(() -> {
            for (String table : List.of("perf_snapshot_event", "perf_snapshot_sample")) {
                jdbc.sql("DELETE FROM " + table + " WHERE session_id IN "
                                + "(SELECT id FROM perf_snapshot_session WHERE status = :completed)")
                        .param("completed", STATUS_COMPLETED).update();
            }
        });
    }

    // ------------------------------------------------------------------ 导出

    /**
     * 明细 → 两份 CSV（事件、系统采样）+ 一份会话说明 → 对象存储 → 删明细。任何一步失败都停在 FAILED，
     * 明细不删，可重新导出；对象 key 固定，重新导出直接覆盖上一次没传完的。
     */
    void export(String id) {
        // 导出期间若已有新快照在记，这里的查询、删除和上传不算进去：它们是导出本身的开销，不是业务负载。
        PerformanceContext.silently(() -> doExport(id));
    }

    private void doExport(String id) {
        Path directory = null;
        try {
            Row row = find(id);
            if (!STATUS_EXPORTING.equals(row.status())) return;
            directory = Files.createTempDirectory("photolib-perf-");
            Path events = directory.resolve("events.csv");
            Path samples = directory.resolve("samples.csv");
            Path session = directory.resolve("session.csv");
            long[] eventRange = writeEvents(id, events);
            long[] sampleRange = writeSamples(id, samples);
            writeSession(row, eventRange[0], sampleRange[0], session);

            String prefix = "performance-snapshots/" + row.startedAt().format(KEY_TIME) + "-" + id + "/";
            put(prefix + "events.csv", events);
            put(prefix + "samples.csv", samples);
            put(prefix + "session.csv", session);

            jdbc.sql("""
                            UPDATE perf_snapshot_session
                            SET status = :completed, events_object_key = :eventsKey, events_size = :eventsSize,
                                samples_object_key = :samplesKey, samples_size = :samplesSize,
                                exported_at = :exportedAt, error_message = NULL,
                                event_count = :eventCount, sample_count = :sampleCount
                            WHERE id = :id
                            """)
                    .param("completed", STATUS_COMPLETED)
                    .param("eventsKey", prefix + "events.csv").param("eventsSize", Files.size(events))
                    .param("samplesKey", prefix + "samples.csv").param("samplesSize", Files.size(samples))
                    .param("exportedAt", LocalDateTime.now(clock))
                    .param("eventCount", eventRange[0]).param("sampleCount", sampleRange[0])
                    .param("id", id).update();
            // CSV 已经在对象存储里了，再删明细。删到一半失败不改状态（数据已经安全），
            // 剩下的行由下次启动时的 purgeExported 收走。
            try {
                deleteRange("perf_snapshot_event", id, eventRange[1], eventRange[2]);
                deleteRange("perf_snapshot_sample", id, sampleRange[1], sampleRange[2]);
            } catch (RuntimeException failure) {
                log.warn("性能快照 {} 已导出，但删除库内明细失败，下次启动时再清", id, failure);
            }
            log.info("性能快照 {} 已导出到 {}，事件 {} 条、采样 {} 次，库内明细已删除",
                    id, prefix, eventRange[0], sampleRange[0]);
        } catch (Exception failure) {
            log.error("性能快照 {} 导出失败，明细保留在库里，可重新导出", id, failure);
            try {
                jdbc.sql("UPDATE perf_snapshot_session SET status = :failed, error_message = :message WHERE id = :id")
                        .param("failed", STATUS_FAILED)
                        .param("message", truncate("导出失败：" + failure.getClass().getSimpleName()
                                + (failure.getMessage() == null ? "" : "：" + failure.getMessage()), 1000))
                        .param("id", id).update();
            } catch (RuntimeException ignored) {
                log.error("性能快照 {} 的失败状态也没能写回", id, ignored);
            }
        } finally {
            if (directory != null) deleteQuietly(directory);
        }
    }

    private void put(String key, Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            storage.put(key, input, Files.size(file), CSV_TYPE);
        }
    }

    private static final String[] EVENT_HEADER = {
            "时间", "类别", "操作", "耗时(毫秒)", "成功", "对象(SQL / 存储 key / 接口)", "行数/字节数/状态码",
            "所属接口或功能", "请求ID", "线程", "错误"};

    /** 返回 {行数, 最小 id, 最大 id}。 */
    private long[] writeEvents(String id, Path file) {
        long[] range = {0, Long.MAX_VALUE, Long.MIN_VALUE};
        try (BufferedWriter writer = csvWriter(file)) {
            writeRow(writer, (Object[]) EVENT_HEADER);
            long cursor = 0;
            while (true) {
                List<Object[]> page = jdbc.sql("""
                                SELECT id, occurred_at, category, operation, duration_micros, success, target,
                                       amount, context, request_id, thread_name, error
                                FROM perf_snapshot_event
                                WHERE session_id = :id AND id > :cursor
                                ORDER BY id
                                LIMIT :limit
                                """)
                        .param("id", id).param("cursor", cursor).param("limit", EXPORT_PAGE)
                        .query(PerformanceSnapshotService::eventRow).list();
                if (page.isEmpty()) break;
                for (Object[] values : page) {
                    long rowId = (Long) values[0];
                    cursor = rowId;
                    range[0]++;
                    range[1] = Math.min(range[1], rowId);
                    range[2] = Math.max(range[2], rowId);
                    Object[] cells = new Object[values.length - 1];
                    System.arraycopy(values, 1, cells, 0, cells.length);
                    writeRow(writer, cells);
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return range;
    }

    private static Object[] eventRow(ResultSet rs, int rowNum) throws SQLException {
        long amount = rs.getLong("amount");
        boolean amountNull = rs.wasNull();
        return new Object[]{
                rs.getLong("id"),
                rs.getTimestamp("occurred_at").toLocalDateTime().format(CSV_TIME),
                rs.getString("category"),
                rs.getString("operation"),
                millis(rs.getLong("duration_micros")),
                rs.getBoolean("success") ? "是" : "否",
                rs.getString("target"),
                amountNull ? null : amount,
                rs.getString("context"),
                rs.getString("request_id"),
                rs.getString("thread_name"),
                rs.getString("error")};
    }

    private static final String[] SAMPLE_HEADER = {
            "时间", "进程CPU(%)", "系统CPU(%)", "系统负载", "CPU核数", "堆已用(字节)", "堆已提交(字节)", "堆上限(字节)",
            "堆占用(%)", "非堆已用(字节)", "物理内存总量(字节)", "物理内存可用(字节)", "物理内存占用(%)", "线程数",
            "GC次数(累计)", "GC耗时毫秒(累计)", "连接池活跃", "连接池空闲", "连接池等待线程", "连接池总数",
            "图片处理排队", "图片处理中", "磁盘可用(字节)"};

    private long[] writeSamples(String id, Path file) {
        long[] range = {0, Long.MAX_VALUE, Long.MIN_VALUE};
        try (BufferedWriter writer = csvWriter(file)) {
            writeRow(writer, (Object[]) SAMPLE_HEADER);
            long cursor = 0;
            while (true) {
                List<Object[]> page = jdbc.sql("""
                                SELECT id, sampled_at, process_cpu, system_cpu, load_average, processors, heap_used,
                                       heap_committed, heap_max, non_heap_used, memory_total, memory_free, threads,
                                       gc_count, gc_time_ms, db_active, db_idle, db_waiting, db_total, image_queue,
                                       image_active, disk_usable
                                FROM perf_snapshot_sample
                                WHERE session_id = :id AND id > :cursor
                                ORDER BY id
                                LIMIT :limit
                                """)
                        .param("id", id).param("cursor", cursor).param("limit", EXPORT_PAGE)
                        .query(PerformanceSnapshotService::sampleRow).list();
                if (page.isEmpty()) break;
                for (Object[] values : page) {
                    long rowId = (Long) values[0];
                    cursor = rowId;
                    range[0]++;
                    range[1] = Math.min(range[1], rowId);
                    range[2] = Math.max(range[2], rowId);
                    Object[] cells = new Object[values.length - 1];
                    System.arraycopy(values, 1, cells, 0, cells.length);
                    writeRow(writer, cells);
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return range;
    }

    private static Object[] sampleRow(ResultSet rs, int rowNum) throws SQLException {
        long heapUsed = rs.getLong("heap_used");
        long heapMax = rs.getLong("heap_max");
        Long memoryTotal = nullableLong(rs, "memory_total");
        Long memoryFree = nullableLong(rs, "memory_free");
        return new Object[]{
                rs.getLong("id"),
                rs.getTimestamp("sampled_at").toLocalDateTime().format(CSV_TIME),
                percent(nullableDouble(rs, "process_cpu")),
                percent(nullableDouble(rs, "system_cpu")),
                decimal(nullableDouble(rs, "load_average")),
                rs.getInt("processors"),
                heapUsed,
                rs.getLong("heap_committed"),
                heapMax,
                heapMax > 0 ? percent((double) heapUsed / heapMax) : null,
                rs.getLong("non_heap_used"),
                memoryTotal,
                memoryFree,
                memoryTotal != null && memoryFree != null && memoryTotal > 0
                        ? percent((double) (memoryTotal - memoryFree) / memoryTotal) : null,
                rs.getInt("threads"),
                rs.getLong("gc_count"),
                rs.getLong("gc_time_ms"),
                nullableLong(rs, "db_active"),
                nullableLong(rs, "db_idle"),
                nullableLong(rs, "db_waiting"),
                nullableLong(rs, "db_total"),
                nullableLong(rs, "image_queue"),
                nullableLong(rs, "image_active"),
                rs.getLong("disk_usable")};
    }

    private void writeSession(Row row, long events, long samples, Path file) {
        try (BufferedWriter writer = csvWriter(file)) {
            writeRow(writer, "项目", "值");
            writeRow(writer, "快照ID", row.id());
            writeRow(writer, "备注", row.note());
            writeRow(writer, "发起人", row.startedByName());
            writeRow(writer, "开始时间", format(row.startedAt()));
            writeRow(writer, "计划结束", format(row.plannedEndAt()));
            writeRow(writer, "实际结束", format(row.endedAt()));
            writeRow(writer, "停止原因", describeReason(row.stopReason()));
            writeRow(writer, "停止人", row.stoppedByName());
            writeRow(writer, "系统采样间隔(秒)", row.sampleIntervalSeconds());
            writeRow(writer, "事件数", events);
            writeRow(writer, "采样次数", samples);
            writeRow(writer, "丢弃事件数（写库跟不上时）", row.droppedCount());
            writeRow(writer, "说明", "SQL 只含 ? 占位符，不含参数值；耗时为应用侧测得的墙钟时间；"
                    + "存储 open 的耗时从打开算到读完关闭。");
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static BufferedWriter csvWriter(Path file) throws IOException {
        BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        writer.write('﻿');
        return writer;
    }

    /** 每格加双引号、内部双引号转义；以公式字符开头的文本前加单引号，Excel 打开时不会被当成公式执行。 */
    static void writeRow(Writer writer, Object... cells) throws IOException {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) writer.write(',');
            Object cell = cells[i];
            String text = cell == null ? "" : cell instanceof Number ? cell.toString() : SpreadsheetText.safe(cell);
            writer.write('"');
            writer.write(text.replace("\"", "\"\""));
            writer.write('"');
        }
        writer.write("\r\n");
    }

    private void deleteRange(String table, String id, long minId, long maxId) {
        if (minId > maxId) return;
        for (long low = minId; low <= maxId; low += DELETE_CHUNK) {
            jdbc.sql("DELETE FROM " + table + " WHERE session_id = :id AND id BETWEEN :low AND :high")
                    .param("id", id).param("low", low).param("high", Math.min(maxId, low + DELETE_CHUNK - 1))
                    .update();
        }
    }

    private long countEvents(String id) {
        return jdbc.sql("SELECT COUNT(*) FROM perf_snapshot_event WHERE session_id = :id")
                .param("id", id).query(Long.class).single();
    }

    private long countSamples(String id) {
        return jdbc.sql("SELECT COUNT(*) FROM perf_snapshot_sample WHERE session_id = :id")
                .param("id", id).query(Long.class).single();
    }

    // ------------------------------------------------------------------ 小工具

    Duration maxDuration() {
        Duration configured = properties.snapshotMaxDuration();
        return configured.compareTo(HARD_MAX_DURATION) > 0 ? HARD_MAX_DURATION : configured;
    }

    private int sampleIntervalSeconds() {
        return (int) Math.max(1, properties.snapshotSampleInterval().toSeconds());
    }

    private static void requireAdmin(AuthenticatedUser user) {
        if (user == null || !user.isAdministrator()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有系统管理员可以使用性能分析");
        }
    }

    static String describeReason(String reason) {
        if (reason == null) return null;
        return switch (reason) {
            case REASON_MANUAL -> "管理员手动停止";
            case REASON_TIMEOUT -> "到达计划时长自动停止";
            case REASON_LIMIT -> "事件数达到上限自动停止";
            default -> reason;
        };
    }

    private static String millis(long micros) {
        return String.format(Locale.ROOT, "%.3f", micros / 1000.0);
    }

    private static String percent(Double ratio) {
        return ratio == null ? null : String.format(Locale.ROOT, "%.2f", ratio * 100);
    }

    private static String decimal(Double value) {
        return value == null ? null : String.format(Locale.ROOT, "%.2f", value);
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String format(LocalDateTime time) {
        return time == null ? null : time.format(CSV_TIME);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static void deleteQuietly(Path directory) {
        try (var files = Files.walk(directory)) {
            files.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时目录，删不掉留给系统清理。
                }
            });
        } catch (IOException ignored) {
            // 同上。
        }
    }
}
