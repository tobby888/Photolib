package cn.photolib.backup;

import cn.photolib.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 备份引擎的类型与外键覆盖测试。这里刻意使用独立的 H2 库，而不是共享的测试上下文：
 * 回滚会清空整库，跑在共享库上会破坏其他测试的数据。
 */
class DatabaseDumpServiceTests {

    @Test
    void roundTripsEveryColumnTypeIncludingNulls() throws Exception {
        DataSource dataSource = freshDatabase();
        execute(dataSource, """
                CREATE TABLE sample (
                    id BIGINT PRIMARY KEY,
                    name VARCHAR(100),
                    amount DECIMAL(12,2),
                    ratio DOUBLE,
                    flag BOOLEAN,
                    taken_on DATE,
                    moment TIMESTAMP,
                    payload VARBINARY(64),
                    notes CLOB
                )
                """);
        execute(dataSource, """
                INSERT INTO sample VALUES
                    (1, '南校区 "秋招"', 12.30, 0.5, TRUE, DATE '2026-08-27',
                     TIMESTAMP '2026-08-27 09:15:30', X'0102FF', '换行\n与制表\t都要原样保留'),
                    (2, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)
                """);
        DatabaseDumpService service = new DatabaseDumpService(dataSource);

        ByteArrayOutputStream backup = new ByteArrayOutputStream();
        DatabaseDumpService.DumpResult dumped = service.dump(backup);
        assertThat(dumped.tableCount()).isEqualTo(1);
        assertThat(dumped.rowCount()).isEqualTo(2);

        execute(dataSource, "DELETE FROM sample");
        execute(dataSource, "INSERT INTO sample (id, name) VALUES (3, '回滚后不应存在')");

        DatabaseDumpService.RestoreResult restored =
                service.restore(new ByteArrayInputStream(backup.toByteArray()));
        assertThat(restored.rowCount()).isEqualTo(2);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM sample ORDER BY id")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getLong("id")).isEqualTo(1);
            assertThat(rows.getString("name")).isEqualTo("南校区 \"秋招\"");
            assertThat(rows.getBigDecimal("amount")).isEqualByComparingTo("12.30");
            assertThat(rows.getDouble("ratio")).isEqualTo(0.5);
            assertThat(rows.getBoolean("flag")).isTrue();
            assertThat(rows.getString("taken_on")).isEqualTo("2026-08-27");
            assertThat(rows.getString("moment")).startsWith("2026-08-27 09:15:30");
            assertThat(rows.getBytes("payload")).containsExactly(0x01, 0x02, (byte) 0xFF);
            assertThat(rows.getString("notes")).isEqualTo("换行\n与制表\t都要原样保留");

            assertThat(rows.next()).isTrue();
            assertThat(rows.getLong("id")).isEqualTo(2);
            assertThat(rows.getString("name")).isNull();
            assertThat(rows.getBigDecimal("amount")).isNull();
            assertThat(rows.getBytes("payload")).isNull();
            assertThat(rows.next()).isFalse();
        }
    }

    @Test
    void restoresRowsWhoseInsertOrderViolatesForeignKeys() throws Exception {
        DataSource dataSource = freshDatabase();
        // 表按字母序导出，child 会排在 parent 前面；只有关掉外键校验才能写回。
        execute(dataSource, "CREATE TABLE parent (id BIGINT PRIMARY KEY, name VARCHAR(50))");
        execute(dataSource, """
                CREATE TABLE child (
                    id BIGINT PRIMARY KEY,
                    parent_id BIGINT NOT NULL,
                    CONSTRAINT fk_child_parent FOREIGN KEY (parent_id) REFERENCES parent(id)
                )
                """);
        execute(dataSource, "INSERT INTO parent VALUES (1, '选题')");
        execute(dataSource, "INSERT INTO child VALUES (10, 1)");
        DatabaseDumpService service = new DatabaseDumpService(dataSource);

        ByteArrayOutputStream backup = new ByteArrayOutputStream();
        service.dump(backup);
        execute(dataSource, "DELETE FROM child");
        execute(dataSource, "DELETE FROM parent");

        DatabaseDumpService.RestoreResult restored =
                service.restore(new ByteArrayInputStream(backup.toByteArray()));

        assertThat(restored.tableCount()).isEqualTo(2);
        assertThat(restored.rowCount()).isEqualTo(2);
        assertThat(count(dataSource, "child")).isEqualTo(1);
        // 外键校验必须被恢复，否则连接归还连接池后业务写入会失去约束保护。
        assertThatThrownBy(() -> execute(dataSource, "INSERT INTO child VALUES (11, 999)"))
                .hasMessageContaining("child");
    }

    @Test
    void neverExportsBackupBookkeepingTables() throws Exception {
        DataSource dataSource = freshDatabase();
        execute(dataSource, "CREATE TABLE database_backup (id VARCHAR(26) PRIMARY KEY)");
        execute(dataSource, "CREATE TABLE database_restore (id VARCHAR(26) PRIMARY KEY)");
        execute(dataSource, "CREATE TABLE campus (id BIGINT PRIMARY KEY)");
        execute(dataSource, "INSERT INTO database_backup VALUES ('KEEPTHISBACKUPRECORD000000')");
        DatabaseDumpService service = new DatabaseDumpService(dataSource);

        ByteArrayOutputStream backup = new ByteArrayOutputStream();
        DatabaseDumpService.DumpResult dumped = service.dump(backup);

        assertThat(dumped.tableCount()).isEqualTo(1);
        assertThat(backup.toString(StandardCharsets.UTF_8)).doesNotContain("KEEPTHISBACKUPRECORD000000");

        // 回滚同样不能碰这两张表，否则会把"为这次回滚生成的兜底备份"一起抹掉。
        service.restore(new ByteArrayInputStream(backup.toByteArray()));
        assertThat(count(dataSource, "database_backup")).isEqualTo(1);
    }

    @Test
    void rejectsFilesThatAreNotPhotolibBackups() throws Exception {
        DatabaseDumpService service = new DatabaseDumpService(freshDatabase());

        assertThatThrownBy(() -> service.restore(new ByteArrayInputStream(
                "{\"format\":\"mysqldump\"}\n".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("格式无法识别");
        assertThatThrownBy(() -> service.restore(new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("为空");
    }

    @Test
    void refusesToRestoreWhenABackedUpTableIsMissing() throws Exception {
        DataSource dataSource = freshDatabase();
        execute(dataSource, "CREATE TABLE sample (id BIGINT PRIMARY KEY)");
        DatabaseDumpService service = new DatabaseDumpService(dataSource);
        ByteArrayOutputStream backup = new ByteArrayOutputStream();
        service.dump(backup);
        execute(dataSource, "DROP TABLE sample");

        assertThatThrownBy(() -> service.restore(new ByteArrayInputStream(backup.toByteArray())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("缺少备份中的数据表");
    }

    private DataSource freshDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:dump-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private void execute(DataSource dataSource, String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long count(DataSource dataSource, String table) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
