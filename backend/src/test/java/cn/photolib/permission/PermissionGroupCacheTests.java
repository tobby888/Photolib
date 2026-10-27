package cn.photolib.permission;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionGroupCacheTests {
    private final MutableClock clock = new MutableClock();
    private final PermissionGroupCache cache = new PermissionGroupCache(clock);
    private final AtomicInteger loads = new AtomicInteger();

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void servesRepeatedLookupsFromMemoryUntilTheTtlExpires() {
        assertThat(cache.get(7L, this::load).permissions()).containsExactly(PermissionCode.PHOTO_VIEW);
        cache.get(7L, this::load);
        assertThat(loads).hasValue(1);

        clock.advanceMillis(PermissionGroupCache.TTL.toMillis() + 1);
        cache.get(7L, this::load);
        assertThat(loads).hasValue(2);
    }

    @Test
    void invalidationForcesTheNextLookupBackToTheDatabase() {
        cache.get(7L, this::load);
        cache.invalidateAll();
        cache.get(7L, this::load);
        assertThat(loads).hasValue(2);
    }

    @Test
    void missingGroupsAreNotCached() {
        assertThat(cache.get(9L, id -> {
            loads.incrementAndGet();
            return null;
        })).isNull();
        cache.get(9L, this::load);
        assertThat(loads).hasValue(2);
    }

    @Test
    void aLoadThatRacesWithAnInvalidationIsNotKept() {
        // 读库读到一半，写入方提交并清了缓存：这次读到的可能是旧值，不能放进缓存。
        cache.get(7L, id -> {
            cache.invalidateAll();
            return load(id);
        });
        cache.get(7L, this::load);
        assertThat(loads).hasValue(2);
    }

    @Test
    void invalidationInsideATransactionClearsAgainAfterItCompletes() {
        TransactionSynchronizationManager.initSynchronization();
        cache.invalidateAll();
        // 提交前别的请求读到旧值并放进了缓存……
        cache.get(7L, this::load);
        cache.get(7L, this::load);
        assertThat(loads).hasValue(1);

        // ……事务结束时再清一次，它活不过提交。
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
        cache.get(7L, this::load);
        assertThat(loads).hasValue(2);
    }

    private PermissionGroupCache.Snapshot load(Long id) {
        loads.incrementAndGet();
        PermissionGroupEntity group = new PermissionGroupEntity();
        group.setId(id);
        group.setCode("G" + id);
        return new PermissionGroupCache.Snapshot(group, Set.of(PermissionCode.PHOTO_VIEW));
    }

    private static final class MutableClock extends Clock {
        private long millis = 1_000_000L;

        void advanceMillis(long delta) {
            millis += delta;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }
    }
}
