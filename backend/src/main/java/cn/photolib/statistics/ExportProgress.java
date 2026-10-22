package cn.photolib.statistics;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 图片打包任务的进度回写，前端的进度弹窗每秒来读一次。
 *
 * <p>总进度 0–100：打包阶段占 {@link #PACKING_SHARE}（按已读出的字节算，图片没记大小时
 * 按张数算），上传阶段占到 99，存好、签出下载地址才到 100（由 {@code ExportService.succeed}
 * 写）。两个阶段都要从对象存储搬一遍数据，耗时差不多，所以权重接近一半一半。</p>
 *
 * <p>写库有节流：同一阶段内两次写至少隔 {@link #MIN_INTERVAL_NANOS}，切阶段和打包完最后
 * 一张时强制写。只更新还在排队或处理中的任务——被看门狗判为中断的任务不会被写回"处理中"。
 * 每次写都刷新 {@code updated_at}，看门狗靠它判断任务是不是还活着，所以大文件读到一半也要写。</p>
 */
final class ExportProgress {
    static final int PACKING_SHARE = 60;
    static final int UPLOADING_END = 99;
    static final long MIN_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(500);

    private final JdbcClient jdbc;
    private final String jobId;
    private final LongSupplier nanoTime;
    private long lastWrite = Long.MIN_VALUE;

    ExportProgress(JdbcClient jdbc, String jobId) {
        this(jdbc, jobId, System::nanoTime);
    }

    ExportProgress(JdbcClient jdbc, String jobId, LongSupplier nanoTime) {
        this.jdbc = jdbc;
        this.jobId = jobId;
        this.nanoTime = nanoTime;
    }

    /** 打包阶段：已打包 {@code doneItems} 张，已读出 {@code doneBytes} 字节。 */
    void packing(int totalItems, Long totalBytes, int doneItems, long doneBytes, boolean force) {
        int percent;
        if (totalBytes != null && totalBytes > 0) {
            percent = (int) Math.min(PACKING_SHARE, doneBytes * PACKING_SHARE / totalBytes);
        } else {
            percent = totalItems == 0 ? PACKING_SHARE : doneItems * PACKING_SHARE / totalItems;
        }
        write("PACKING", percent, totalItems, doneItems, totalBytes, doneBytes, force);
    }

    /** 上传阶段：ZIP 共 {@code zipBytes} 字节，已上传 {@code uploaded}。 */
    void uploading(int totalItems, long zipBytes, long uploaded, boolean force) {
        int span = UPLOADING_END - PACKING_SHARE;
        int percent = PACKING_SHARE + (zipBytes <= 0 ? span : (int) Math.min(span, uploaded * span / zipBytes));
        write("UPLOADING", percent, totalItems, totalItems, zipBytes, uploaded, force);
    }

    private void write(String phase, int percent, int totalItems, int doneItems, Long totalBytes, long doneBytes,
                       boolean force) {
        long now = nanoTime.getAsLong();
        if (!force && lastWrite != Long.MIN_VALUE && now - lastWrite < MIN_INTERVAL_NANOS) return;
        lastWrite = now;
        jdbc.sql("""
                UPDATE export_job
                SET status='PROCESSING', phase=:phase, progress=:progress,
                    total_items=:totalItems, done_items=:doneItems,
                    total_bytes=:totalBytes, done_bytes=:doneBytes, updated_at=:now
                WHERE id=:id AND status IN ('PENDING', 'PROCESSING')
                """)
                .param("phase", phase)
                .param("progress", percent)
                .param("totalItems", totalItems)
                .param("doneItems", doneItems)
                .param("totalBytes", totalBytes)
                .param("doneBytes", doneBytes)
                .param("now", LocalDateTime.now())
                .param("id", jobId)
                .update();
    }

    /** 读多少报多少的输入流：上传 ZIP 时用它知道存储那边已经收了多少。 */
    static final class CountingInputStream extends FilterInputStream {
        private final java.util.function.LongConsumer onRead;
        private long count;

        CountingInputStream(InputStream input, java.util.function.LongConsumer onRead) {
            super(input);
            this.onRead = onRead;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) advance(1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) advance(read);
            return read;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            if (skipped > 0) advance(skipped);
            return skipped;
        }

        private void advance(long bytes) {
            count += bytes;
            onRead.accept(count);
        }
    }
}
