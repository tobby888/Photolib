package cn.photolib.performance;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.File;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 性能快照的记录端：快照开着的时候，SQL、存储操作、图片处理、HTTP 请求都会以 {@link Event} 的形式
 * 交到这里，另外按固定间隔采一次 CPU / 内存 / 连接池 / 图片队列。
 *
 * <p><b>业务线程只做一件事：把事件放进有界队列</b>，不等数据库。单独一个后台线程每秒批量写库；
 * 队列满了（写库跟不上）就丢弃并计数，丢了多少会写进快照记录和 CSV 文件名旁边，开发拿到数据时
 * 知道它不完整。那个后台线程自己的 INSERT 在 {@link PerformanceContext#silently} 里执行，
 * 不会被再记成事件。</p>
 *
 * <p>快照关着的时候 {@link #active()} 是一次 volatile 读，数据源和存储的包装层看到 false
 * 就直接走原对象，不产生任何额外开销。</p>
 */
@Slf4j
@Component
public class SnapshotRecorder {
    public static final String CATEGORY_HTTP = "HTTP";
    public static final String CATEGORY_SQL = "SQL";
    public static final String CATEGORY_STORAGE = "STORAGE";
    public static final String CATEGORY_IMAGE = "IMAGE";
    public static final String CATEGORY_MARK = "MARK";

    private static final int BATCH_SIZE = 2000;
    private static final int MAX_TARGET_LENGTH = 16000;

    /**
     * @param atMillis  结束时刻（epoch 毫秒）
     * @param category  HTTP / SQL / STORAGE / IMAGE / MARK
     * @param operation SQL 是 SELECT/INSERT/…，存储是 put/open/…，图片是 THUMBNAIL/COMPRESS，HTTP 是方法
     * @param micros    耗时（微秒）
     * @param target    SQL 原文 / 对象 key / 路由模板
     * @param amount    SQL 影响或返回的行数、存储传输的字节数、HTTP 状态码
     */
    public record Event(long atMillis, String category, String operation, long micros, boolean success,
                        String target, Long amount, String context, String requestId, String thread,
                        String error) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final PerformanceProperties properties;
    private final DataSource dataSource;
    private final ObjectProvider<ThreadPoolTaskExecutor> photoExecutor;

    private final BlockingQueue<Event> queue;
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong samples = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    private volatile String sessionId;
    private volatile boolean limitSignalled;
    private volatile Consumer<String> limitListener = id -> { };
    private ScheduledExecutorService writer;
    private ScheduledFuture<?> flushTask;
    private ScheduledFuture<?> sampleTask;

    public SnapshotRecorder(JdbcTemplate jdbc, Clock clock, PerformanceProperties properties, DataSource dataSource,
                            @Qualifier("photoProcessingExecutor") ObjectProvider<ThreadPoolTaskExecutor> photoExecutor) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
        this.dataSource = dataSource;
        this.photoExecutor = photoExecutor;
        this.queue = new ArrayBlockingQueue<>(Math.max(1000, properties.snapshotQueueCapacity()));
        if (dataSource instanceof TimedDataSource timed) timed.attach(this);
    }

    public boolean active() {
        return sessionId != null;
    }

    public String sessionId() {
        return sessionId;
    }

    public long writtenEvents() {
        return written.get();
    }

    public long writtenSamples() {
        return samples.get();
    }

    public long droppedEvents() {
        return dropped.get();
    }

    public int queuedEvents() {
        return queue.size();
    }

    /** 事件数到上限时回调（参数是快照 id），由 {@link PerformanceSnapshotService} 接去停止快照。 */
    void onLimitReached(Consumer<String> listener) {
        this.limitListener = listener;
    }

    /**
     * 开始往 {@code id} 这次快照里记。{@code alreadyWritten} 等是重启后续记时库里已有的数，
     * 新开的快照都传 0。
     */
    synchronized void activate(String id, long alreadyWritten, long alreadySampled, long alreadyDropped) {
        if (sessionId != null) throw new IllegalStateException("已有性能快照在记录");
        queue.clear();
        written.set(alreadyWritten);
        samples.set(alreadySampled);
        dropped.set(alreadyDropped);
        limitSignalled = false;
        writer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "perf-snapshot-writer");
            thread.setDaemon(true);
            return thread;
        });
        sessionId = id;
        long sampleMillis = Math.max(1000, properties.snapshotSampleInterval().toMillis());
        flushTask = writer.scheduleWithFixedDelay(this::flushQuietly, 1, 1, TimeUnit.SECONDS);
        sampleTask = writer.scheduleAtFixedRate(this::sampleQuietly, 0, sampleMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 停止记录：先不再接新事件，再把队列里剩下的全部写完。返回时库里就是这次快照的全部数据。
     */
    synchronized void deactivate() {
        String id = sessionId;
        if (id == null) return;
        sessionId = null;
        ScheduledExecutorService executor = writer;
        writer = null;
        if (flushTask != null) flushTask.cancel(false);
        if (sampleTask != null) sampleTask.cancel(false);
        try {
            // 最后一轮在写库线程上跑，和正在进行的那一批串行，不会两个线程同时写。
            executor.submit(() -> drain(id, Integer.MAX_VALUE)).get(5, TimeUnit.MINUTES);
        } catch (Exception failure) {
            log.warn("性能快照 {} 停止时写完剩余事件失败，已丢弃 {} 条", id, queue.size(), failure);
            dropped.addAndGet(queue.size());
            queue.clear();
        } finally {
            executor.shutdownNow();
        }
    }

    @PreDestroy
    void shutdown() {
        // 应用正常关闭：把已收到的事件写完，快照记录保持 RECORDING，下次启动时续记或收尾。
        deactivate();
    }

    public void record(Event event) {
        if (sessionId == null) return;
        if (!queue.offer(event)) dropped.incrementAndGet();
    }

    /** 加一条标记事件（快照开始、应用重启后续记等），方便在 CSV 里对齐时间线。 */
    void mark(String operation, String detail) {
        record(new Event(clock.millis(), CATEGORY_MARK, operation, 0, true, detail, null, null, null,
                Thread.currentThread().getName(), null));
    }

    void sql(String operation, String sql, long micros, boolean success, Long rows, String error) {
        if (sessionId == null || PerformanceContext.silent()) return;
        String text = sql;
        int max = Math.max(200, properties.snapshotMaxSqlLength());
        if (text != null && text.length() > max) text = text.substring(0, max) + "…（共 " + sql.length() + " 字）";
        record(new Event(clock.millis(), CATEGORY_SQL, operation, micros, success, text, rows,
                PerformanceContext.operation(), PerformanceContext.requestId(), Thread.currentThread().getName(),
                error));
    }

    void storage(String operation, String objectKey, long micros, boolean success, Long bytes, String error) {
        if (sessionId == null || PerformanceContext.silent()) return;
        record(new Event(clock.millis(), CATEGORY_STORAGE, operation, micros, success, objectKey, bytes,
                PerformanceContext.operation(), PerformanceContext.requestId(), Thread.currentThread().getName(),
                error));
    }

    // ------------------------------------------------------------------ 写库线程

    private void flushQuietly() {
        String id = sessionId;
        if (id == null) return;
        try {
            drain(id, 50);
        } catch (RuntimeException failure) {
            log.warn("性能快照事件写库失败，下轮重试", failure);
        }
    }

    /** 最多写 {@code maxBatches} 批；写库失败时这一批放弃并计入丢弃数，不让队列卡死。 */
    private void drain(String id, int maxBatches) {
        List<Event> batch = new ArrayList<>(BATCH_SIZE);
        for (int round = 0; round < maxBatches; round++) {
            batch.clear();
            queue.drainTo(batch, BATCH_SIZE);
            if (batch.isEmpty()) return;
            try {
                PerformanceContext.silently(() -> insertEvents(id, batch));
                written.addAndGet(batch.size());
            } catch (RuntimeException failure) {
                dropped.addAndGet(batch.size());
                throw failure;
            }
            if (!limitSignalled && written.get() >= properties.snapshotMaxEvents()) {
                limitSignalled = true;
                limitListener.accept(id);
            }
        }
    }

    private void insertEvents(String id, List<Event> batch) {
        jdbc.batchUpdate("""
                        INSERT INTO perf_snapshot_event
                            (session_id, occurred_at, category, operation, duration_micros, success, target, amount,
                             context, request_id, thread_name, error)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, batch, batch.size(), (PreparedStatement ps, Event event) -> {
                    ps.setString(1, id);
                    ps.setTimestamp(2, timestamp(event.atMillis()));
                    ps.setString(3, event.category());
                    ps.setString(4, clip(event.operation(), 64));
                    ps.setLong(5, event.micros());
                    ps.setBoolean(6, event.success());
                    ps.setString(7, clip(event.target(), MAX_TARGET_LENGTH));
                    setLong(ps, 8, event.amount());
                    ps.setString(9, clip(event.context(), 300));
                    ps.setString(10, clip(event.requestId(), 64));
                    ps.setString(11, clip(event.thread(), 100));
                    ps.setString(12, clip(event.error(), 500));
                });
    }

    private void sampleQuietly() {
        String id = sessionId;
        if (id == null) return;
        try {
            SystemSample sample = SystemSample.capture(clock, dataSource, photoExecutor.getIfAvailable());
            PerformanceContext.silently(() -> insertSample(id, sample));
            samples.incrementAndGet();
        } catch (RuntimeException failure) {
            log.warn("性能快照系统指标采集失败", failure);
        }
    }

    private void insertSample(String id, SystemSample s) {
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO perf_snapshot_sample
                        (session_id, sampled_at, process_cpu, system_cpu, load_average, processors, heap_used,
                         heap_committed, heap_max, non_heap_used, memory_total, memory_free, threads, gc_count,
                         gc_time_ms, db_active, db_idle, db_waiting, db_total, image_queue, image_active,
                         disk_usable)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """);
            ps.setString(1, id);
            ps.setTimestamp(2, timestamp(s.atMillis()));
            setDouble(ps, 3, s.processCpu());
            setDouble(ps, 4, s.systemCpu());
            setDouble(ps, 5, s.loadAverage());
            ps.setInt(6, s.processors());
            ps.setLong(7, s.heapUsed());
            ps.setLong(8, s.heapCommitted());
            ps.setLong(9, s.heapMax());
            ps.setLong(10, s.nonHeapUsed());
            setLong(ps, 11, s.memoryTotal());
            setLong(ps, 12, s.memoryFree());
            ps.setInt(13, s.threads());
            ps.setLong(14, s.gcCount());
            ps.setLong(15, s.gcTimeMs());
            setInt(ps, 16, s.dbActive());
            setInt(ps, 17, s.dbIdle());
            setInt(ps, 18, s.dbWaiting());
            setInt(ps, 19, s.dbTotal());
            setInt(ps, 20, s.imageQueue());
            setInt(ps, 21, s.imageActive());
            ps.setLong(22, s.diskUsable());
            return ps;
        });
    }

    private Timestamp timestamp(long millis) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), clock.getZone()));
    }

    private static String clip(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }

    private static void setLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) ps.setNull(index, Types.BIGINT);
        else ps.setLong(index, value);
    }

    private static void setInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) ps.setNull(index, Types.INTEGER);
        else ps.setInt(index, value);
    }

    private static void setDouble(PreparedStatement ps, int index, Double value) throws SQLException {
        if (value == null) ps.setNull(index, Types.DOUBLE);
        else ps.setDouble(index, value);
    }

    /** 一次系统指标采样。取不到的项（非 HotSpot JVM、非 Hikari 连接池）为 null。 */
    record SystemSample(long atMillis, Double processCpu, Double systemCpu, Double loadAverage, int processors,
                        long heapUsed, long heapCommitted, long heapMax, long nonHeapUsed,
                        Long memoryTotal, Long memoryFree, int threads, long gcCount, long gcTimeMs,
                        Integer dbActive, Integer dbIdle, Integer dbWaiting, Integer dbTotal,
                        Integer imageQueue, Integer imageActive, long diskUsable) {

        static SystemSample capture(Clock clock, DataSource dataSource, ThreadPoolTaskExecutor photoExecutor) {
            MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
            OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            Double processCpu = null;
            Double systemCpu = null;
            Long memoryTotal = null;
            Long memoryFree = null;
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                processCpu = ratio(sun.getProcessCpuLoad());
                systemCpu = ratio(sun.getCpuLoad());
                memoryTotal = sun.getTotalMemorySize();
                memoryFree = sun.getFreeMemorySize();
            }
            double load = os.getSystemLoadAverage();
            long gcCount = 0;
            long gcTime = 0;
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                gcCount += Math.max(0, gc.getCollectionCount());
                gcTime += Math.max(0, gc.getCollectionTime());
            }
            Integer dbActive = null;
            Integer dbIdle = null;
            Integer dbWaiting = null;
            Integer dbTotal = null;
            HikariPoolMXBean pool = hikariPool(dataSource);
            if (pool != null) {
                dbActive = pool.getActiveConnections();
                dbIdle = pool.getIdleConnections();
                dbWaiting = pool.getThreadsAwaitingConnection();
                dbTotal = pool.getTotalConnections();
            }
            Integer imageQueue = null;
            Integer imageActive = null;
            if (photoExecutor != null) {
                try {
                    imageQueue = photoExecutor.getThreadPoolExecutor().getQueue().size();
                    imageActive = photoExecutor.getActiveCount();
                } catch (IllegalStateException notInitialized) {
                    // 执行器还没初始化（极早期或测试里），留空。
                }
            }
            return new SystemSample(clock.millis(), processCpu, systemCpu, load >= 0 ? load : null,
                    os.getAvailableProcessors(), heap.getUsed(), heap.getCommitted(),
                    heap.getMax() > 0 ? heap.getMax() : heap.getCommitted(), nonHeap.getUsed(),
                    memoryTotal, memoryFree, ManagementFactory.getThreadMXBean().getThreadCount(),
                    gcCount, gcTime, dbActive, dbIdle, dbWaiting, dbTotal, imageQueue, imageActive,
                    new File(".").getAbsoluteFile().getUsableSpace());
        }

        private static HikariPoolMXBean hikariPool(DataSource dataSource) {
            try {
                DataSource target = dataSource instanceof TimedDataSource timed ? timed.getTargetDataSource() : dataSource;
                if (target != null && target.isWrapperFor(HikariDataSource.class)) {
                    return target.unwrap(HikariDataSource.class).getHikariPoolMXBean();
                }
            } catch (SQLException | RuntimeException ignored) {
                // 不是 Hikari 或池还没启动，连接池几项留空。
            }
            return null;
        }

        private static Double ratio(double value) {
            return value >= 0 && Double.isFinite(value) ? value : null;
        }
    }
}
