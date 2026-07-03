package cn.photolib.migration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(LegacyMigrationProperties.class)
public class LegacyMigrationConfig {
}
