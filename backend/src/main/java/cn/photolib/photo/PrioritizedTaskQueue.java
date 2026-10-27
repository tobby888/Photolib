package cn.photolib.photo;

import java.util.Comparator;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 图片处理池的工作队列：按 {@link ProcessingPriority} 出队，同一优先级先进先出。
 *
 * <p>{@link PriorityBlockingQueue} 本身没有容量上限，而处理池原来靠"队列满了就拒绝"
 * 让调用方看到失败（随后由卡住照片的恢复任务接手），所以这里在 {@link #offer} 上补回上限。
 */
final class PrioritizedTaskQueue extends PriorityBlockingQueue<Runnable> {
    private static final Comparator<Runnable> ORDER = Comparator
            .comparingInt(PrioritizedTaskQueue::rank)
            .thenComparingLong(PrioritizedTaskQueue::sequence);

    private final int capacity;

    PrioritizedTaskQueue(int capacity) {
        super(Math.max(11, Math.min(capacity, 1024)), ORDER);
        if (capacity < 1) throw new IllegalArgumentException("队列容量必须大于 0");
        this.capacity = capacity;
    }

    @Override
    public synchronized boolean offer(Runnable task) {
        if (size() >= capacity) return false;
        return super.offer(task);
    }

    @Override
    public int remainingCapacity() {
        return Math.max(0, capacity - size());
    }

    private static int rank(Runnable task) {
        return task instanceof Task prioritized ? prioritized.priority.ordinal() : 0;
    }

    private static long sequence(Runnable task) {
        return task instanceof Task prioritized ? prioritized.sequence : 0;
    }

    /** 带优先级和提交序号的任务。序号保证同一优先级内按提交顺序执行。 */
    static final class Task implements Runnable {
        private static final AtomicLong SEQUENCE = new AtomicLong();

        private final ProcessingPriority priority;
        private final long sequence;
        private final Runnable delegate;

        Task(ProcessingPriority priority, Runnable delegate) {
            this.priority = priority == null ? ProcessingPriority.INTERACTIVE : priority;
            this.sequence = SEQUENCE.incrementAndGet();
            this.delegate = delegate;
        }

        @Override
        public void run() {
            delegate.run();
        }
    }
}
