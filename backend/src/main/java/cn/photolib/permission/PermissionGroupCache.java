package cn.photolib.permission;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 每个带令牌的请求在认证时都要查一次权限组和它的权限码（{@link PermissionGroupService#toPrincipal}），
 * 而这两张表一天里几乎不变。这里把它们按权限组 id 缓存一小段时间。
 *
 * <p>只缓存权限组本身，<b>不缓存</b>会话、账号启用状态、校区授权和两步验证状态——
 * 那些要么必须立即生效（吊销、停用），要么是按人变的。
 *
 * <p>后端是授权边界，所以失效要做对：
 * <ul>
 *   <li>改权限组的写入口（新建、编辑、删除、整库回滚）调用 {@link #invalidateAll()}，
 *       在当前事务提交后再清一次——提交前清掉的话，别的请求可能马上读回旧值又放进来。</li>
 *   <li>代际计数挡住"读库读到一半、写入方已经提交并清缓存"的竞争：加载前记下代际，
 *       放回时代际变了就不放。</li>
 *   <li>即便漏了某个写入口（比如直接改库），{@link #TTL} 到期也会自愈。</li>
 * </ul>
 * 只在进程内生效，与性能分析模块一样假设单实例部署；多实例时需要改成广播失效。
 */
@Component
public class PermissionGroupCache {
    static final Duration TTL = Duration.ofSeconds(30);

    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private final Clock clock;

    public PermissionGroupCache() {
        this(Clock.systemUTC());
    }

    PermissionGroupCache(Clock clock) {
        this.clock = clock;
    }

    /**
     * 认证用到的那部分权限组信息。实体是从库里读出来的可变对象，调用方只能读、不能改。
     */
    public record Snapshot(PermissionGroupEntity group, Set<PermissionCode> permissions) {
        public Snapshot {
            permissions = Set.copyOf(permissions);
        }
    }

    /** 取缓存；没有或过期时用 {@code loader} 读库。{@code loader} 返回 null（权限组不存在）时不缓存。 */
    public Snapshot get(Long groupId, Function<Long, Snapshot> loader) {
        long now = clock.millis();
        Entry cached = entries.get(groupId);
        if (cached != null && cached.expiresAt > now) {
            return cached.snapshot;
        }
        long observed = generation.get();
        Snapshot loaded = loader.apply(groupId);
        if (loaded != null && generation.get() == observed) {
            entries.put(groupId, new Entry(loaded, now + TTL.toMillis()));
        }
        return loaded;
    }

    /** 立即清空；若在事务里，提交后再清一次。 */
    public void invalidateAll() {
        clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    clear();
                }
            });
        }
    }

    private void clear() {
        generation.incrementAndGet();
        entries.clear();
    }

    private record Entry(Snapshot snapshot, long expiresAt) {
    }
}
