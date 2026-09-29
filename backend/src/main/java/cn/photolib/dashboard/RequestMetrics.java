package cn.photolib.dashboard;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 进程内的请求流量计数：数据面板上的 QPS、流量、错误率、响应时间和热门接口都从这里来。
 *
 * <p><b>只记在内存里，重启清零，也只是本实例的数字。</b>部署是单实例（见 README），这就是
 * 全站的流量；以后若横向扩容，面板上看到的只是处理这个请求的那一台。OSS 直传、预签名直链
 * 下载不经过应用服务器，不计入流量——面板上有同样的说明。</p>
 *
 * <p>两个环形桶：秒级 300 格（最近 5 分钟，算实时 QPS 和 5 分钟曲线）、分钟级 1440 格（最近
 * 24 小时）。每格记着自己属于哪一秒 / 哪一分钟，读的时候不是这一轮的格子就当 0，所以长时间
 * 没有请求之后也不会读到上一轮的旧数。分钟格另记「这一分钟里最忙的那一秒」，近 1 小时峰值
 * QPS 由它得出，而不必把 3600 个秒格都留着。</p>
 *
 * <p>写入是 {@code synchronized}：校园站的并发量下一把锁的开销可以忽略，换来的是读写
 * 两边都不用推敲可见性。</p>
 */
@Component
public class RequestMetrics {
    static final int SECOND_SLOTS = 300;
    static final int MINUTE_SLOTS = 1440;
    /** 热门接口按「方法 + 路由模板」聚合；模板是有限集合，这个上限只防意外。 */
    static final int MAX_ENDPOINTS = 300;
    static final String OTHER_ENDPOINTS = "(其他)";
    private static final long ACTIVE_USER_RETENTION_MS = 24 * 3600_000L;

    private final LongSupplier clock;
    private final long startedAt;
    private final Bucket[] seconds = new Bucket[SECOND_SLOTS];
    private final Bucket[] minutes = new Bucket[MINUTE_SLOTS];
    private final Map<String, EndpointCounter> endpoints = new HashMap<>();
    private final Map<Long, Long> activeUsers = new ConcurrentHashMap<>();
    private final Totals totals = new Totals();

    @Autowired
    public RequestMetrics() {
        this(System::currentTimeMillis);
    }

    RequestMetrics(LongSupplier clock) {
        this.clock = clock;
        this.startedAt = clock.getAsLong();
        for (int i = 0; i < SECOND_SLOTS; i++) seconds[i] = new Bucket();
        for (int i = 0; i < MINUTE_SLOTS; i++) minutes[i] = new Bucket();
    }

    public long now() {
        return clock.getAsLong();
    }

    /**
     * 记一次处理完的请求。{@code endpoint} 为空表示没匹配到接口（静态资源、404），
     * 只计入总量，不进热门接口榜。
     */
    public synchronized void record(String endpoint, int status, long latencyMs, long bytesIn, long bytesOut) {
        long now = clock.getAsLong();
        long second = now / 1000;
        long minute = now / 60_000;
        long latency = Math.max(0, latencyMs);
        long in = Math.max(0, bytesIn);
        long out = Math.max(0, bytesOut);

        Bucket secondBucket = current(seconds, second);
        secondBucket.add(status, latency, in, out);
        Bucket minuteBucket = current(minutes, minute);
        minuteBucket.add(status, latency, in, out);
        minuteBucket.peakPerSecond = Math.max(minuteBucket.peakPerSecond, secondBucket.requests);
        totals.requests++;
        totals.bytesIn += in;
        totals.bytesOut += out;

        if (endpoint != null) {
            EndpointCounter counter = endpoints.get(endpoint);
            if (counter == null) {
                String key = endpoints.size() < MAX_ENDPOINTS ? endpoint : OTHER_ENDPOINTS;
                counter = endpoints.computeIfAbsent(key, ignored -> new EndpointCounter());
            }
            counter.requests++;
            if (status >= 500) counter.serverErrors++;
            else if (status >= 400) counter.clientErrors++;
            counter.latencySum += latency;
            counter.latencyMax = Math.max(counter.latencyMax, latency);
        }
    }

    /** 带着有效会话来的请求；在线人数按「最近一次出现」算。 */
    public void markActive(Long userId) {
        if (userId == null) return;
        long now = clock.getAsLong();
        activeUsers.put(userId, now);
        if (activeUsers.size() > 5_000) pruneActiveUsers(now);
    }

    public ActiveUsers activeUsers() {
        long now = clock.getAsLong();
        pruneActiveUsers(now);
        long last5Minutes = 0;
        long lastHour = 0;
        long last24Hours = 0;
        for (long seen : activeUsers.values()) {
            long age = now - seen;
            if (age <= 5 * 60_000L) last5Minutes++;
            if (age <= 3600_000L) lastHour++;
            if (age <= ACTIVE_USER_RETENTION_MS) last24Hours++;
        }
        return new ActiveUsers(last5Minutes, lastHour, last24Hours);
    }

    public synchronized Snapshot snapshot(int topEndpoints) {
        long now = clock.getAsLong();
        long second = now / 1000;
        long minute = now / 60_000;

        // 实时 QPS 用刚过去的 10 个完整秒，不含正在进行的这一秒——那一格只填了一部分。
        Window last10Seconds = sumSeconds(second - 10, second);
        Window lastMinute = sumSeconds(second - 60, second);
        Window lastHour = sumMinutes(minute - 59, minute + 1);
        Window last24Hours = sumMinutes(minute - (MINUTE_SLOTS - 1), minute + 1);

        long peak = 0;
        for (long m = minute - 59; m <= minute; m++) {
            Bucket bucket = read(minutes, m);
            if (bucket != null) peak = Math.max(peak, bucket.peakPerSecond);
        }

        Map<String, List<Point>> series = new HashMap<>();
        series.put("5m", secondSeries(second, 60, 5));
        series.put("1h", minuteSeries(minute, 60, 1));
        series.put("24h", minuteSeries(minute, 96, 15));

        List<Endpoint> top = new ArrayList<>();
        endpoints.forEach((key, counter) -> top.add(new Endpoint(key, counter.requests,
                counter.clientErrors, counter.serverErrors,
                counter.requests == 0 ? 0 : (double) counter.latencySum / counter.requests,
                counter.latencyMax)));
        top.sort(Comparator.comparingLong(Endpoint::requests).reversed().thenComparing(Endpoint::endpoint));

        return new Snapshot(now, startedAt, last10Seconds.requests / 10.0, peak,
                lastMinute, lastHour, last24Hours,
                new Window(totals.requests, 0, 0, 0, 0, totals.bytesIn, totals.bytesOut, 0, 0),
                series, List.copyOf(top.subList(0, Math.min(Math.max(0, topEndpoints), top.size()))));
    }

    private void pruneActiveUsers(long now) {
        activeUsers.values().removeIf(seen -> now - seen > ACTIVE_USER_RETENTION_MS);
    }

    private List<Point> secondSeries(long currentSecond, int points, int step) {
        List<Point> result = new ArrayList<>(points);
        // 最后一格含正在进行的这一秒：曲线的右端要跟得上刚刚发生的请求。
        long end = currentSecond + 1;
        for (int i = points - 1; i >= 0; i--) {
            long to = end - (long) i * step;
            long from = to - step;
            result.add(Point.of(from * 1000, step, sumSeconds(from, to)));
        }
        return result;
    }

    private List<Point> minuteSeries(long currentMinute, int points, int step) {
        List<Point> result = new ArrayList<>(points);
        long end = currentMinute + 1;
        for (int i = points - 1; i >= 0; i--) {
            long to = end - (long) i * step;
            long from = to - step;
            result.add(Point.of(from * 60_000, step * 60, sumMinutes(from, to)));
        }
        return result;
    }

    /** [from, to) 秒。 */
    private Window sumSeconds(long from, long to) {
        Accumulator accumulator = new Accumulator();
        for (long s = Math.max(from, to - SECOND_SLOTS); s < to; s++) accumulator.add(read(seconds, s));
        return accumulator.window();
    }

    /** [from, to) 分钟。 */
    private Window sumMinutes(long from, long to) {
        Accumulator accumulator = new Accumulator();
        for (long m = Math.max(from, to - MINUTE_SLOTS); m < to; m++) accumulator.add(read(minutes, m));
        return accumulator.window();
    }

    private static Bucket current(Bucket[] ring, long epoch) {
        Bucket bucket = ring[(int) Math.floorMod(epoch, (long) ring.length)];
        if (bucket.epoch != epoch) bucket.reset(epoch);
        return bucket;
    }

    private static Bucket read(Bucket[] ring, long epoch) {
        Bucket bucket = ring[(int) Math.floorMod(epoch, (long) ring.length)];
        return bucket.epoch == epoch ? bucket : null;
    }

    private static final class Bucket {
        long epoch = Long.MIN_VALUE;
        long requests;
        long successes;
        long redirects;
        long clientErrors;
        long serverErrors;
        long bytesIn;
        long bytesOut;
        long latencySum;
        long latencyMax;
        long peakPerSecond;

        void reset(long epoch) {
            this.epoch = epoch;
            requests = successes = redirects = clientErrors = serverErrors = 0;
            bytesIn = bytesOut = latencySum = latencyMax = peakPerSecond = 0;
        }

        void add(int status, long latency, long in, long out) {
            requests++;
            if (status >= 500) serverErrors++;
            else if (status >= 400) clientErrors++;
            else if (status >= 300) redirects++;
            else if (status >= 200) successes++;
            bytesIn += in;
            bytesOut += out;
            latencySum += latency;
            latencyMax = Math.max(latencyMax, latency);
        }
    }

    private static final class Accumulator {
        long requests;
        long successes;
        long redirects;
        long clientErrors;
        long serverErrors;
        long bytesIn;
        long bytesOut;
        long latencySum;
        long latencyMax;

        void add(Bucket bucket) {
            if (bucket == null) return;
            requests += bucket.requests;
            successes += bucket.successes;
            redirects += bucket.redirects;
            clientErrors += bucket.clientErrors;
            serverErrors += bucket.serverErrors;
            bytesIn += bucket.bytesIn;
            bytesOut += bucket.bytesOut;
            latencySum += bucket.latencySum;
            latencyMax = Math.max(latencyMax, bucket.latencyMax);
        }

        Window window() {
            return new Window(requests, successes, redirects, clientErrors, serverErrors, bytesIn, bytesOut,
                    requests == 0 ? 0 : (double) latencySum / requests, latencyMax);
        }
    }

    private static final class EndpointCounter {
        long requests;
        long clientErrors;
        long serverErrors;
        long latencySum;
        long latencyMax;
    }

    private static final class Totals {
        long requests;
        long bytesIn;
        long bytesOut;
    }

    /** 一段时间窗口里的合计；{@code avgLatencyMs} 按请求数加权。 */
    public record Window(long requests, long successes, long redirects, long clientErrors, long serverErrors,
                         long bytesIn, long bytesOut, double avgLatencyMs, long maxLatencyMs) {
    }

    /** 曲线上的一格。{@code qps} = 这一格的请求数 / 这一格的秒数。 */
    public record Point(long time, long requests, double qps, long clientErrors, long serverErrors,
                        long bytesIn, long bytesOut, double avgLatencyMs) {
        static Point of(long time, int seconds, Window window) {
            return new Point(time, window.requests(), (double) window.requests() / seconds,
                    window.clientErrors(), window.serverErrors(), window.bytesIn(), window.bytesOut(),
                    window.avgLatencyMs());
        }
    }

    public record Endpoint(String endpoint, long requests, long clientErrors, long serverErrors,
                           double avgLatencyMs, long maxLatencyMs) {
    }

    public record ActiveUsers(long last5Minutes, long lastHour, long last24Hours) {
    }

    public record Snapshot(long generatedAt, long startedAt, double qps, long peakQpsLastHour,
                           Window lastMinute, Window lastHour, Window last24Hours, Window sinceStart,
                           Map<String, List<Point>> series, List<Endpoint> topEndpoints) {
    }
}
