package cn.photolib.photo;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Serializes startup generation, runtime reconciliation and targeted repair. */
@Component
public class PreviewMaintenanceLock {
    private final ReentrantLock lock = new ReentrantLock();

    public <T> T exclusively(Supplier<T> task) {
        lock.lock();
        try {
            return task.get();
        } finally {
            lock.unlock();
        }
    }
}
