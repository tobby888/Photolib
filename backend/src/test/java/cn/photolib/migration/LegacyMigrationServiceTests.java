package cn.photolib.migration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LegacyMigrationServiceTests {
    @Test
    void acceptsJdbcUrlWithoutChangingIt() {
        assertEquals("jdbc:postgresql://db:5432/warehouse",
                LegacyMigrationService.normalizeJdbcUrl("jdbc:postgresql://db:5432/warehouse"));
    }

    @Test
    void convertsSqlAlchemyPostgresUrl() {
        assertEquals("jdbc:postgresql://db:5432/warehouse",
                LegacyMigrationService.normalizeJdbcUrl("postgresql://db:5432/warehouse"));
    }

    @Test
    void rejectsUnknownDatabaseUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> LegacyMigrationService.normalizeJdbcUrl("mysql://db/warehouse"));
    }
}
