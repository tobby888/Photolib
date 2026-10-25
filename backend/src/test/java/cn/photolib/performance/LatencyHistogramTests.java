package cn.photolib.performance;

import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** 分桶直方图的精度、合并与持久化往返；以及实时窗口的精确分位数和几个纯函数。 */
class LatencyHistogramTests {

    @Test
    void bucketBoundsGrowMonotonicallyAndCoverOneHour() {
        long[] bounds = LatencyHistogram.BOUNDS;
        assertThat(bounds[0]).isEqualTo(100);
        for (int i = 1; i < bounds.length; i++) {
            assertThat(bounds[i]).isGreaterThan(bounds[i - 1]);
            // 每格最多放大 8%（取整后头几格可能更小，但不会更大）。
            assertThat((double) bounds[i] / bounds[i - 1]).isLessThanOrEqualTo(1.08 + 0.011);
        }
        assertThat(bounds[bounds.length - 1]).isGreaterThanOrEqualTo(3_600_000_000L);
        assertThat(LatencyHistogram.bucketOf(0)).isZero();
        assertThat(LatencyHistogram.bucketOf(100)).isZero();
        assertThat(LatencyHistogram.bucketOf(101)).isEqualTo(1);
        assertThat(LatencyHistogram.bucketOf(Long.MAX_VALUE)).isEqualTo(bounds.length - 1);
    }

    @Test
    void percentilesStayWithinEightPercentOfTheExactValue() {
        Random random = new Random(42);
        LatencyHistogram histogram = new LatencyHistogram();
        long[] values = new long[50_000];
        for (int i = 0; i < values.length; i++) {
            // 对数正态：大多数请求几十毫秒，长尾到几秒，接近真实接口的分布。
            values[i] = Math.max(1, (long) Math.exp(10 + 1.2 * random.nextGaussian()));
            histogram.record(values[i], i % 100 != 0);
        }
        Arrays.sort(values);
        for (double q : new double[]{0.5, 0.9, 0.95, 0.99}) {
            long exact = PerformanceAnalysisService.rank(values, q);
            long estimate = histogram.percentile(q);
            assertThat(estimate).isGreaterThanOrEqualTo(exact);
            assertThat((double) estimate / exact).isLessThanOrEqualTo(1.08 + 0.001);
        }
        assertThat(histogram.count()).isEqualTo(50_000);
        assertThat(histogram.errors()).isEqualTo(500);
        assertThat(histogram.maxMicros()).isEqualTo(values[values.length - 1]);
    }

    @Test
    void percentileNeverExceedsTheObservedMaximum() {
        LatencyHistogram histogram = new LatencyHistogram();
        histogram.record(120_000, true);
        assertThat(histogram.percentile(0.99)).isEqualTo(120_000);
        assertThat(new LatencyHistogram().percentile(0.5)).isZero();
    }

    @Test
    void encodeDecodeAndMergeRoundTrip() {
        LatencyHistogram a = new LatencyHistogram();
        LatencyHistogram b = new LatencyHistogram();
        for (int i = 1; i <= 1000; i++) a.record(i * 1000L, true);
        for (int i = 1; i <= 500; i++) b.record(i * 7000L, i % 10 != 0);

        LatencyHistogram decoded = LatencyHistogram.decode(a.encodeBuckets(), a.count(), a.errors(),
                a.totalMicros(), a.maxMicros());
        assertThat(decoded.encodeBuckets()).isEqualTo(a.encodeBuckets());
        assertThat(decoded.percentile(0.95)).isEqualTo(a.percentile(0.95));

        decoded.merge(b);
        assertThat(decoded.count()).isEqualTo(1500);
        assertThat(decoded.errors()).isEqualTo(50);
        assertThat(decoded.maxMicros()).isEqualTo(3_500_000);
        assertThat(decoded.totalMicros()).isEqualTo(a.totalMicros() + b.totalMicros());
        // 稀疏编码：空格子不写。
        assertThat(new LatencyHistogram().encodeBuckets()).isEmpty();
        assertThat(LatencyHistogram.decode("", 0, 0, 0, 0).count()).isZero();
    }

    @Test
    void liveWindowStatisticsUseEveryCallExactly() {
        List<LiveCallBuffer.Call> calls = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            calls.add(new LiveCallBuffer.Call(i, LiveCallBuffer.Kind.REQUEST, "GET /api/v1/x", null, "模块",
                    i == 100 ? 500 : 200, i * 1000L, null));
        }
        PerformanceAnalysisService.Stats stats = PerformanceAnalysisService.exact(calls);
        assertThat(stats.count()).isEqualTo(100);
        assertThat(stats.errors()).isEqualTo(1);
        assertThat(stats.p50Ms()).isEqualTo(50.0);
        assertThat(stats.p90Ms()).isEqualTo(90.0);
        assertThat(stats.p95Ms()).isEqualTo(95.0);
        assertThat(stats.p99Ms()).isEqualTo(99.0);
        assertThat(stats.maxMs()).isEqualTo(100.0);
        assertThat(stats.avgMs()).isEqualTo(50.5);
    }

    @Test
    void liveBufferKeepsTheNewestCallsAndFlagsTruncation() {
        LiveCallBuffer buffer = new LiveCallBuffer(16);
        for (int i = 0; i < 40; i++) {
            buffer.add(new LiveCallBuffer.Call(1000L + i, LiveCallBuffer.Kind.REQUEST, "k", null, null, 200, 1, null));
        }
        LiveCallBuffer.Window all = buffer.since(0);
        assertThat(all.calls()).hasSize(16);
        assertThat(all.calls().getFirst().at()).isEqualTo(1024);
        assertThat(all.truncated()).isTrue();
        // 窗口起点在保留范围之内时不算截断。
        LiveCallBuffer.Window recent = buffer.since(1030);
        assertThat(recent.calls()).hasSize(10);
        assertThat(recent.truncated()).isFalse();
    }

    @Test
    void sqlOperationNamesAndPathMasking() {
        assertThat(TimedDataSource.operationOf("  select * from t", "executeQuery", 0)).isEqualTo("SELECT");
        assertThat(TimedDataSource.operationOf("INSERT INTO t VALUES (?)", "executeBatch", 200))
                .isEqualTo("INSERT×200");
        assertThat(TimedDataSource.operationOf(null, "execute", 0)).isEqualTo("SQL");
        assertThat(PerformanceContext.maskPath("/api/v1/public/shares/AbCdEfGhIjKlMnOpQrSt/photos/12"))
                .isEqualTo("/api/v1/public/shares/{…}/photos/12");
    }

    @Test
    void csvCellsAreQuotedEscapedAndFormulaSafe() throws Exception {
        StringWriter out = new StringWriter();
        PerformanceSnapshotService.writeRow(out, "SELECT \"a\" FROM t", null, 12L, "=HYPERLINK(1)", "-1");
        assertThat(out.toString())
                .isEqualTo("\"SELECT \"\"a\"\" FROM t\",\"\",\"12\",\"'=HYPERLINK(1)\",\"'-1\"\r\n");
    }
}
