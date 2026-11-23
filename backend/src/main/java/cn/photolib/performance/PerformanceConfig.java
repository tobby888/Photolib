package cn.photolib.performance;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.lang.NonNull;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PerformanceProperties.class)
@ImportRuntimeHints(PerformanceConfig.Hints.class)
public class PerformanceConfig {

    /**
     * 把应用的数据源包成 {@link TimedDataSource}。用后置处理器而不是自己声明数据源，
     * 是为了不改 Spring Boot 对连接池的自动配置（Hikari 参数、Flyway、健康检查都照旧）。
     * 必须是 static：后置处理器要在其他 Bean 之前创建，不能依赖本配置类的实例。
     */
    @Bean
    static BeanPostProcessor timedDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName) {
                if (bean instanceof DataSource dataSource && !(bean instanceof TimedDataSource)
                        && "dataSource".equals(beanName)) {
                    return new TimedDataSource(dataSource);
                }
                return bean;
            }
        };
    }

    static final class Hints implements RuntimeHintsRegistrar {
        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            for (Class<?> type : new Class<?>[]{
                    Connection.class, Statement.class, PreparedStatement.class, CallableStatement.class}) {
                hints.proxies().registerJdkProxy(type);
                hints.reflection().registerType(type, MemberCategory.INVOKE_PUBLIC_METHODS);
            }
            for (Class<?> rowType : new Class<?>[]{
                    LatencyStatsStore.Row.class, PerformanceSnapshotService.Row.class}) {
                hints.reflection().registerType(rowType,
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                        MemberCategory.INVOKE_DECLARED_METHODS,
                        MemberCategory.ACCESS_DECLARED_FIELDS);
            }
        }
    }
}
