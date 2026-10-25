package cn.photolib.performance;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * 最近一段时间每一次功能调用的明细（实时窗口用，最长看 10 分钟）。
 *
 * <p>固定大小的环形数组，写入只是一次自增加一次赋值，不加锁，不拖慢请求。只在内存里，
 * 重启清零、只算本实例。容量用完后最老的记录被挤掉；读的时候若发现窗口起点之后的记录
 * 已经被挤掉过，就在结果上标记 {@code truncated}，界面提示「流量太大，只保留了最近 N 次」。</p>
 */
public final class LiveCallBuffer {
    public enum Kind { REQUEST, IMAGE }

    /**
     * @param at        结束时刻（epoch 毫秒）
     * @param kind      请求还是图片处理
     * @param key       请求是「方法 + 路由模板」，图片处理是 THUMBNAIL / COMPRESS
     * @param label     功能名（接口上 {@code @AuditAction} 的操作名，图片处理是「生成缩略图」等）
     * @param module    模块中文名
     * @param status    HTTP 状态；图片处理成功记 200、失败记 500
     * @param micros    耗时（微秒）
     * @param detail    补充信息（图片处理的源文件大小等）
     */
    public record Call(long at, Kind kind, String key, String label, String module, int status,
                       long micros, String detail) {
        public boolean success() {
            return status < 500;
        }
    }

    private final AtomicReferenceArray<Call> slots;
    private final int capacity;
    private final AtomicLong written = new AtomicLong();

    public LiveCallBuffer(int capacity) {
        this.capacity = Math.max(16, capacity);
        this.slots = new AtomicReferenceArray<>(this.capacity);
    }

    public void add(Call call) {
        long index = written.getAndIncrement();
        slots.set((int) (index % capacity), call);
    }

    public int capacity() {
        return capacity;
    }

    public record Window(List<Call> calls, boolean truncated) {
    }

    /** 结束时刻不早于 {@code sinceMillis} 的调用，按时间从早到晚。 */
    public Window since(long sinceMillis) {
        long total = written.get();
        long start = Math.max(0, total - capacity);
        List<Call> result = new ArrayList<>();
        Call oldestKept = null;
        for (long i = start; i < total; i++) {
            Call call = slots.get((int) (i % capacity));
            if (call == null) continue;
            if (oldestKept == null || call.at() < oldestKept.at()) oldestKept = call;
            if (call.at() >= sinceMillis) result.add(call);
        }
        result.sort((a, b) -> Long.compare(a.at(), b.at()));
        boolean truncated = total > capacity && oldestKept != null && oldestKept.at() >= sinceMillis;
        return new Window(result, truncated);
    }
}
