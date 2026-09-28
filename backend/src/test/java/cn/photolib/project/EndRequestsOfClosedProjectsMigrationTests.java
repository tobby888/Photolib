package cn.photolib.project;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** V59：把在 ProjectService.endOpenRequests 之前就结束的选题名下的存量需求补齐。 */
class EndRequestsOfClosedProjectsMigrationTests {
    private static final LocalDateTime PROJECT_COMPLETED_AT = LocalDateTime.of(2026, 6, 30, 18, 0);

    @Test
    void v59EndsOpenRequestsOfAlreadyClosedProjects() {
        DataSource dataSource = database();
        migrate(dataSource, "58");
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (93001, 'v59-admin', 'hash', '迁移测试', 'ADMIN', TRUE, FALSE)
                """).update();
        jdbc.sql("INSERT INTO campus (id, code, name) VALUES (93001, 'V59', '迁移校区')").update();
        seedProject(jdbc, 93101L, "COMPLETED", PROJECT_COMPLETED_AT);
        seedProject(jdbc, 93102L, "CANCELLED", null);
        seedProject(jdbc, 93103L, "ACTIVE", null);

        seedRequest(jdbc, 93201L, 93101L, "DRAFT");
        seedRequest(jdbc, 93202L, 93101L, "PUBLISHED");
        seedRequest(jdbc, 93203L, 93101L, "ACCEPTED");
        seedRequest(jdbc, 93204L, 93101L, "SUBMITTED");
        seedRequest(jdbc, 93205L, 93101L, "CANCELLED");
        seedRequest(jdbc, 93211L, 93102L, "PUBLISHED");
        seedRequest(jdbc, 93212L, 93102L, "SUBMITTED");
        seedRequest(jdbc, 93213L, 93102L, "COMPLETED");
        seedRequest(jdbc, 93221L, 93103L, "ACCEPTED");
        jdbc.sql("""
                UPDATE photo_request SET return_reason='旧的退回原因', returned_by=93001,
                    returned_at=CURRENT_TIMESTAMP(6)
                WHERE id=93203
                """).update();

        migrate(dataSource, null);

        assertThat(status(jdbc, 93201L)).isEqualTo("CANCELLED");
        assertThat(status(jdbc, 93202L)).isEqualTo("CANCELLED");
        assertThat(status(jdbc, 93203L)).isEqualTo("COMPLETED");
        assertThat(status(jdbc, 93204L)).isEqualTo("COMPLETED");
        assertThat(status(jdbc, 93205L)).isEqualTo("CANCELLED");
        assertThat(status(jdbc, 93211L)).isEqualTo("CANCELLED");
        assertThat(status(jdbc, 93212L)).isEqualTo("CANCELLED");
        assertThat(status(jdbc, 93213L)).isEqualTo("COMPLETED");
        assertThat(status(jdbc, 93221L)).isEqualTo("ACCEPTED");

        // 补上的完成时间取选题的完成时间，而不是迁移执行的时刻
        assertThat(jdbc.sql("SELECT completed_at FROM photo_request WHERE id=93204")
                .query(LocalDateTime.class).single()).isEqualTo(PROJECT_COMPLETED_AT);
        assertThat(jdbc.sql("SELECT return_reason FROM photo_request WHERE id=93203")
                .query(String.class).optional()).isEmpty();
        assertThat(cancelReason(jdbc, 93202L)).isEqualTo("所属选题已完成，需求自动结束");
        assertThat(cancelReason(jdbc, 93212L)).isEqualTo("所属选题已取消，需求自动结束");
        // 本来就结束了的需求不动
        assertThat(cancelReason(jdbc, 93205L)).isNull();
        assertThat(version(jdbc, 93205L)).isEqualTo(1);
        assertThat(version(jdbc, 93213L)).isEqualTo(1);
        assertThat(version(jdbc, 93221L)).isEqualTo(1);
        assertThat(version(jdbc, 93203L)).isEqualTo(2);
    }

    private static void seedProject(JdbcClient jdbc, long id, String status, LocalDateTime completedAt) {
        jdbc.sql("""
                INSERT INTO project (id, title, status, created_by, completed_at)
                VALUES (:id, '迁移选题', :status, 93001, :completedAt)
                """).param("id", id).param("status", status).param("completedAt", completedAt).update();
    }

    private static void seedRequest(JdbcClient jdbc, long id, long projectId, String status) {
        jdbc.sql("""
                INSERT INTO photo_request
                    (id, project_id, title, campus_id, required_count, deadline, status, created_by)
                VALUES (:id, :projectId, '迁移需求', 93001, 1, CURRENT_TIMESTAMP(6), :status, 93001)
                """).param("id", id).param("projectId", projectId).param("status", status).update();
    }

    private static String status(JdbcClient jdbc, long id) {
        return jdbc.sql("SELECT status FROM photo_request WHERE id=:id").param("id", id)
                .query(String.class).single();
    }

    private static String cancelReason(JdbcClient jdbc, long id) {
        return jdbc.sql("SELECT cancel_reason FROM photo_request WHERE id=:id").param("id", id)
                .query(String.class).optional().orElse(null);
    }

    private static int version(JdbcClient jdbc, long id) {
        return jdbc.sql("SELECT version FROM photo_request WHERE id=:id").param("id", id)
                .query(Integer.class).single();
    }

    private static DataSource database() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:v59_migration_"
                + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static void migrate(DataSource dataSource, String target) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration");
        if (target != null) configuration.target(target);
        configuration.load().migrate();
    }
}
