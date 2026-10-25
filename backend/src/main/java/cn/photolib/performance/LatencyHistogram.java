package cn.photolib.performance;

import java.util.Arrays;

/**
 * 按对数分桶的耗时直方图，用来算一周的 p50 / p90 / p95 / p99。
 *
 * <p>为什么不存每一次的耗时：一周几十万上百万次请求，逐条存再排序太重；按桶计数后每个接口每小时
 * 只占一行，合并一周就是把计数加起来。代价是精度：桶边界从 100 微秒起每格放大 8%，
 * 分位数取所在桶的上界（再用实测最大值封顶），相对误差不超过 8%。判断"该不该加机器"
 * 这个精度足够；要逐条耗时请用性能快照。</p>
 *
 * <p>{@link #SCHEME} 写进每一行。改了桶的起点或倍率必须换一个新的 scheme 名，旧行的桶号
 * 按新边界解读会全部错位。</p>
 */
public final class LatencyHistogram {
    public static final String SCHEME = "log108";
    static final long FIRST_BOUND_MICROS = 100;
    static final double GROWTH = 1.08;
    /** 最后一格的上界至少 1 小时；更慢的都落进最后一格。 */
    static final long LAST_BOUND_MICROS = 3_600_000_000L;
    static final long[] BOUNDS = bounds();

    private final long[] counts = new long[BOUNDS.length];
    private long count;
    private long errors;
    private long totalMicros;
    private long maxMicros;

    private static long[] bounds() {
        long[] values = new long[256];
        int size = 0;
        double bound = FIRST_BOUND_MICROS;
        long previous = 0;
        while (true) {
            long rounded = Math.max(previous + 1, Math.round(bound));
            values[size++] = rounded;
            previous = rounded;
            if (rounded >= LAST_BOUND_MICROS) break;
            bound *= GROWTH;
        }
        return Arrays.copyOf(values, size);
    }

    /** 耗时落在哪一格：第一个上界不小于它的格子。 */
    static int bucketOf(long micros) {
        if (micros <= BOUNDS[0]) return 0;
        int index = Arrays.binarySearch(BOUNDS, micros);
        if (index < 0) index = -index - 1;
        return Math.min(index, BOUNDS.length - 1);
    }

    public void record(long micros, boolean success) {
        long value = Math.max(0, micros);
        counts[bucketOf(value)]++;
        count++;
        if (!success) errors++;
        totalMicros += value;
        maxMicros = Math.max(maxMicros, value);
    }

    public void merge(LatencyHistogram other) {
        for (int i = 0; i < counts.length; i++) counts[i] += other.counts[i];
        count += other.count;
        errors += other.errors;
        totalMicros += other.totalMicros;
        maxMicros = Math.max(maxMicros, other.maxMicros);
    }

    public LatencyHistogram copy() {
        LatencyHistogram copy = new LatencyHistogram();
        copy.merge(this);
        return copy;
    }

    public long count() {
        return count;
    }

    public long errors() {
        return errors;
    }

    public long totalMicros() {
        return totalMicros;
    }

    public long maxMicros() {
        return maxMicros;
    }

    /**
     * 第 {@code quantile} 分位的耗时（微秒）。没有样本时返回 0。取所在桶的上界，
     * 但不超过实测最大值——否则只有一次 120ms 的请求时 p99 会报成 129ms。
     */
    public long percentile(double quantile) {
        if (count == 0) return 0;
        long rank = Math.max(1, (long) Math.ceil(quantile * count));
        long seen = 0;
        for (int i = 0; i < counts.length; i++) {
            seen += counts[i];
            if (seen >= rank) return Math.min(BOUNDS[i], maxMicros);
        }
        return maxMicros;
    }

    /** 只写非零格：{@code 桶号:计数,桶号:计数}，一小时里通常只有十来格有数。 */
    public String encodeBuckets() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] == 0) continue;
            if (!text.isEmpty()) text.append(',');
            text.append(i).append(':').append(counts[i]);
        }
        return text.toString();
    }

    public static LatencyHistogram decode(String buckets, long count, long errors, long totalMicros, long maxMicros) {
        LatencyHistogram histogram = new LatencyHistogram();
        if (buckets != null && !buckets.isBlank()) {
            for (String part : buckets.split(",")) {
                int colon = part.indexOf(':');
                if (colon <= 0) continue;
                int index = Integer.parseInt(part.substring(0, colon).trim());
                long value = Long.parseLong(part.substring(colon + 1).trim());
                if (index >= 0 && index < histogram.counts.length && value > 0) histogram.counts[index] += value;
            }
        }
        histogram.count = count;
        histogram.errors = errors;
        histogram.totalMicros = totalMicros;
        histogram.maxMicros = maxMicros;
        return histogram;
    }
}
