package cn.photolib.performance;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PerformanceProperties.class)
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
}
