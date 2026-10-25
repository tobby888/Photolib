package cn.photolib.performance;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param histogramRetention    每小时耗时直方图在库里保留多久。分位数只看最近 7 天，多留的部分方便回头对比。
 * @param histogramFlushInterval 内存里的直方图多久合并进库一次。重启时丢的最多就是这一段。
 * @param liveCapacity          实时窗口的环形缓冲能放多少次调用。10 分钟内超过这个数，最老的会被挤掉，界面标「已截断」。
 * @param snapshotMaxDuration   一次性能快照最长记录多久，到点自动停止。
 * @param snapshotSampleInterval 快照期间采集 CPU / 内存 / 连接池的间隔。
 * @param snapshotQueueCapacity 待写库事件队列的长度。写库跟不上时多出来的事件丢弃并计数，不让业务线程等。
 * @param snapshotMaxEvents     一次快照最多记多少条事件，超过即自动停止，防止把磁盘写满。
 * @param snapshotMaxSqlLength  每条 SQL 最多保留的字符数。
 */
@ConfigurationProperties(prefix = "photolib.performance")
public record PerformanceProperties(
        @DefaultValue("35d") Duration histogramRetention,
        @DefaultValue("60s") Duration histogramFlushInterval,
        @DefaultValue("65536") int liveCapacity,
        @DefaultValue("72h") Duration snapshotMaxDuration,
        @DefaultValue("5s") Duration snapshotSampleInterval,
        @DefaultValue("200000") int snapshotQueueCapacity,
        @DefaultValue("20000000") long snapshotMaxEvents,
        @DefaultValue("8000") int snapshotMaxSqlLength
) {
}
