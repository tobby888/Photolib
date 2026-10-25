package cn.photolib.performance;

import cn.photolib.storage.ObjectStorageService;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 给对象存储套一层计时：性能快照开着时，每次 put / open / stat / find / list / delete / 签名
 * 的耗时、对象 key、字节数交给 {@link SnapshotRecorder}。快照关着时直接转交，不多做任何事。
 *
 * <p>{@code open} 返回的是流，真正的下载发生在读的过程中，所以这一条在流关闭时才记，
 * 耗时从打开算到关闭，字节数是实际读出的量。</p>
 *
 * <p>需要原对象的地方（本地存储控制器要拿 {@code LocalObjectStorageService}）用 {@link #delegate()}。</p>
 */
public class TimedObjectStorageService implements ObjectStorageService, AutoCloseable {
    private final ObjectStorageService delegate;
    private final SnapshotRecorder recorder;

    public TimedObjectStorageService(ObjectStorageService delegate, SnapshotRecorder recorder) {
        this.delegate = delegate;
        this.recorder = recorder;
    }

    public ObjectStorageService delegate() {
        return delegate;
    }

    /**
     * 包装层才是 Spring 管理的 Bean，底下的实现上的 {@code @PreDestroy} 不会被调到。Spring 按
     * Bean 上的 public close() 推断销毁方法，这里转交下去（OSS 客户端要在关闭时 shutdown）。
     */
    @Override
    public void close() throws Exception {
        if (delegate instanceof AutoCloseable closeable) closeable.close();
    }

    /** 拆掉计时包装，拿到真正的实现。 */
    public static ObjectStorageService unwrap(ObjectStorageService storage) {
        return storage instanceof TimedObjectStorageService timed ? timed.delegate() : storage;
    }

    @Override
    public void initialize() {
        timed("initialize", null, null, () -> {
            delegate.initialize();
            return null;
        });
    }

    @Override
    public List<StoredObject> list(String prefix) {
        if (!recorder.active()) return delegate.list(prefix);
        long started = System.nanoTime();
        try {
            List<StoredObject> result = delegate.list(prefix);
            recorder.storage("list", prefix, micros(started), true, (long) result.size(), null);
            return result;
        } catch (RuntimeException failure) {
            recorder.storage("list", prefix, micros(started), false, null, describe(failure));
            throw failure;
        }
    }

    @Override
    public SignedUrl presignPut(String objectKey, String contentType, Duration ttl) {
        return timed("presignPut", objectKey, null, () -> delegate.presignPut(objectKey, contentType, ttl));
    }

    @Override
    public SignedUrl presignGet(String objectKey, String downloadName, Duration ttl, String cacheControl) {
        return timed("presignGet", objectKey, null,
                () -> delegate.presignGet(objectKey, downloadName, ttl, cacheControl));
    }

    @Override
    public ObjectInfo stat(String objectKey) {
        return timed("stat", objectKey, null, () -> delegate.stat(objectKey));
    }

    @Override
    public Optional<ObjectInfo> find(String objectKey) {
        return timed("find", objectKey, null, () -> delegate.find(objectKey));
    }

    @Override
    public InputStream open(String objectKey) {
        if (!recorder.active()) return delegate.open(objectKey);
        long started = System.nanoTime();
        InputStream stream;
        try {
            stream = delegate.open(objectKey);
        } catch (RuntimeException failure) {
            recorder.storage("open", objectKey, micros(started), false, null, describe(failure));
            throw failure;
        }
        return new CountingStream(stream, objectKey, started);
    }

    @Override
    public void put(String objectKey, InputStream input, long size, String contentType,
                    Map<String, String> userMetadata) {
        timed("put", objectKey, size, () -> {
            delegate.put(objectKey, input, size, contentType, userMetadata);
            return null;
        });
    }

    @Override
    public void delete(String objectKey) {
        timed("delete", objectKey, null, () -> {
            delegate.delete(objectKey);
            return null;
        });
    }

    private <T> T timed(String operation, String objectKey, Long bytes, Supplier<T> action) {
        if (!recorder.active()) return action.get();
        long started = System.nanoTime();
        try {
            T result = action.get();
            recorder.storage(operation, objectKey, micros(started), true, bytes, null);
            return result;
        } catch (RuntimeException failure) {
            recorder.storage(operation, objectKey, micros(started), false, bytes, describe(failure));
            throw failure;
        }
    }

    private static long micros(long started) {
        return (System.nanoTime() - started) / 1_000;
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private final class CountingStream extends FilterInputStream {
        private final String objectKey;
        private final long started;
        private long bytes;
        private String error;
        private boolean closed;

        CountingStream(InputStream in, String objectKey, long started) {
            super(in);
            this.objectKey = objectKey;
            this.started = started;
        }

        @Override
        public int read() throws IOException {
            try {
                int value = super.read();
                if (value >= 0) bytes++;
                return value;
            } catch (IOException failure) {
                error = describe(failure);
                throw failure;
            }
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            try {
                int count = super.read(buffer, offset, length);
                if (count > 0) bytes += count;
                return count;
            } catch (IOException failure) {
                error = describe(failure);
                throw failure;
            }
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            if (skipped > 0) bytes += skipped;
            return skipped;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                if (!closed) {
                    closed = true;
                    recorder.storage("open", objectKey, micros(started), error == null, bytes, error);
                }
            }
        }
    }
}
