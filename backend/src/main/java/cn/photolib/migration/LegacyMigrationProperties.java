package cn.photolib.migration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("photolib.migration")
public record LegacyMigrationProperties(
        boolean enabled,
        String databaseUrl,
        String databaseUsername,
        String databasePassword
) {
}
