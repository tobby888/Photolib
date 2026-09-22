package cn.photolib.support;

import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;

/**
 * 测试专用的主键生成器：雪花 ID，但相邻两次发号至少隔开 {@link #GAP}。
 *
 * <p>实体主键走 ASSIGN_ID（雪花），而大量测试用 {@code JdbcClient} 直接 INSERT、不写 id，
 * 靠表上的 AUTO_INCREMENT 拿号。H2 的 MySQL 模式（和真 MySQL 一样）在插入显式 id 后会把
 * 自增计数器推到 {@code max(id) + 1}，于是测试手插的那一行正好拿到「上一个雪花 ID + 1」；
 * 同一毫秒内雪花序列号也是 +1，下一次 {@code mapper.insert} 就撞主键。拉开间隔后，
 * 自增只会落在两个雪花 ID 之间的空档里。
 *
 * <p>状态是 JVM 级静态的，测试里缓存的多个 Spring 上下文共用同一条发号序列。
 */
public final class GappedIdentifierGenerator implements IdentifierGenerator {
    static final long GAP = 1L << 16;

    private static final IdentifierGenerator SNOWFLAKE = DefaultIdentifierGenerator.getInstance();
    private static long last;

    @Override
    public Long nextId(Object entity) {
        synchronized (GappedIdentifierGenerator.class) {
            long id = Math.max(SNOWFLAKE.nextId(entity).longValue(), last + GAP);
            last = id;
            return id;
        }
    }
}
