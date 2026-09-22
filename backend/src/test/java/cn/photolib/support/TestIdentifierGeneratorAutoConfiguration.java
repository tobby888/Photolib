package cn.photolib.support;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 只在测试 classpath 上登记（src/test/resources/META-INF/spring/...AutoConfiguration.imports），
 * 让每个 {@code @SpringBootTest} 上下文的 MyBatis-Plus 都用 {@link GappedIdentifierGenerator}。
 */
@AutoConfiguration
public class TestIdentifierGeneratorAutoConfiguration {
    @Bean
    IdentifierGenerator identifierGenerator() {
        return new GappedIdentifierGenerator();
    }
}
