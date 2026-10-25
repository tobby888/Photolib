package cn.photolib.performance;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Set;

/**
 * 给数据源套一层：性能快照开着时，每条 SQL 的原文（MyBatis-Plus 生成的、JdbcClient 手写的都算）、
 * 执行耗时、影响行数交给 {@link SnapshotRecorder}。
 *
 * <p>只记带 {@code ?} 占位符的 SQL 文本，<b>不记参数值</b>：参数里有密码哈希、令牌、手机号，
 * 而这份 CSV 是要交给开发团队的。判断调优方向看 SQL 形状和耗时就够了。</p>
 *
 * <p>快照关着时 {@link #getConnection()} 直接返回连接池的原连接，一个包装都不加。快照开着时
 * 新借出的连接才会被包装——借出时间很短（一个事务），开启后几乎立刻覆盖全部流量。</p>
 *
 * <p>继承 {@link DelegatingDataSource} 而不是另写一个 {@link DataSource}：Spring Boot 的连接池
 * 指标和健康检查认得它，能顺着 {@link #getTargetDataSource()} 找到底下的 Hikari。</p>
 */
public class TimedDataSource extends DelegatingDataSource implements AutoCloseable {
    private static final Set<String> EXECUTE_METHODS = Set.of(
            "execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "executeBatch", "executeLargeBatch");

    private volatile SnapshotRecorder recorder;

    public TimedDataSource(DataSource target) {
        super(target);
    }

    void attach(SnapshotRecorder recorder) {
        this.recorder = recorder;
    }

    /**
     * Spring 按"Bean 实例上有没有 public close()"推断销毁方法，看的是包装后的这个对象。
     * 不转交的话应用关闭时 Hikari 连接池不会被关掉。
     */
    @Override
    public void close() throws Exception {
        if (getTargetDataSource() instanceof AutoCloseable closeable) closeable.close();
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(super.getConnection(username, password));
    }

    private Connection wrap(Connection connection) {
        SnapshotRecorder current = recorder;
        if (current == null || !current.active() || connection == null) return connection;
        return (Connection) Proxy.newProxyInstance(TimedDataSource.class.getClassLoader(),
                new Class<?>[]{Connection.class}, new ConnectionHandler(connection, current));
    }

    private static final class ConnectionHandler implements InvocationHandler {
        private final Connection target;
        private final SnapshotRecorder recorder;

        ConnectionHandler(Connection target, SnapshotRecorder recorder) {
            this.target = target;
            this.recorder = recorder;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("equals".equals(name) && args != null && args.length == 1) return proxy == args[0];
            if ("hashCode".equals(name) && (args == null || args.length == 0)) return System.identityHashCode(proxy);
            Object result = invokeTarget(target, method, args);
            if (result instanceof CallableStatement statement && args != null && args.length > 0
                    && args[0] instanceof String sql) {
                return statementProxy(statement, CallableStatement.class, sql, recorder);
            }
            if (result instanceof PreparedStatement statement && args != null && args.length > 0
                    && args[0] instanceof String sql) {
                return statementProxy(statement, PreparedStatement.class, sql, recorder);
            }
            if (result instanceof Statement statement && "createStatement".equals(name)) {
                return statementProxy(statement, Statement.class, null, recorder);
            }
            return result;
        }
    }

    private static Object statementProxy(Statement statement, Class<?> type, String sql, SnapshotRecorder recorder) {
        return Proxy.newProxyInstance(TimedDataSource.class.getClassLoader(), new Class<?>[]{type},
                new StatementHandler(statement, sql, recorder));
    }

    private static final class StatementHandler implements InvocationHandler {
        private final Statement target;
        private final String preparedSql;
        private final SnapshotRecorder recorder;
        private int batched;

        StatementHandler(Statement target, String preparedSql, SnapshotRecorder recorder) {
            this.target = target;
            this.preparedSql = preparedSql;
            this.recorder = recorder;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("equals".equals(name) && args != null && args.length == 1) return proxy == args[0];
            if ("hashCode".equals(name) && (args == null || args.length == 0)) return System.identityHashCode(proxy);
            if ("addBatch".equals(name)) batched++;
            if (!EXECUTE_METHODS.contains(name)) return invokeTarget(target, method, args);
            String sql = args != null && args.length > 0 && args[0] instanceof String text ? text : preparedSql;
            long started = System.nanoTime();
            boolean success = false;
            String error = null;
            Object result = null;
            try {
                result = invokeTarget(target, method, args);
                success = true;
                return result;
            } catch (SQLException failure) {
                error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                throw failure;
            } catch (RuntimeException failure) {
                error = failure.getClass().getSimpleName();
                throw failure;
            } finally {
                long micros = (System.nanoTime() - started) / 1_000;
                String operation = operationOf(sql, name, batched);
                if (name.startsWith("executeBatch") || name.startsWith("executeLargeBatch")) batched = 0;
                recorder.sql(operation, sql, micros, success, rows(result), error);
            }
        }
    }

    /** {@code SELECT} / {@code UPDATE} …；批量执行记成 {@code INSERT×200} 方便一眼看出是批量。 */
    static String operationOf(String sql, String method, int batched) {
        String verb = "SQL";
        if (sql != null) {
            String trimmed = sql.stripLeading();
            int end = 0;
            while (end < trimmed.length() && Character.isLetter(trimmed.charAt(end))) end++;
            if (end > 0) verb = trimmed.substring(0, end).toUpperCase(Locale.ROOT);
        }
        if (method.startsWith("executeBatch") || method.startsWith("executeLargeBatch")) {
            return verb + "×" + batched;
        }
        return verb;
    }

    private static Long rows(Object result) {
        if (result instanceof Integer value) return value.longValue();
        if (result instanceof Long value) return value;
        if (result instanceof int[] values) {
            long total = 0;
            for (int value : values) if (value > 0) total += value;
            return total;
        }
        if (result instanceof long[] values) {
            long total = 0;
            for (long value : values) if (value > 0) total += value;
            return total;
        }
        return null;
    }

    private static Object invokeTarget(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getTargetException();
        }
    }
}
