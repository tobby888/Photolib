package cn.photolib.recruitment;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecruitmentMigrationTests {

    @Test
    void freshMigrationEnforcesRecruitmentBindingsStatusesAndModes() {
        DataSource dataSource = database();
        migrate(dataSource, null);
        JdbcClient jdbc = JdbcClient.create(dataSource);
        seedUser(jdbc);
        seedTask(jdbc, 91_001L, id(101), "DRAFT");
        seedTask(jdbc, 91_002L, id(102), "DRAFT");
        seedDraft(jdbc, id(201), 91_001L, "STUDENT-1", "DRAFT");
        seedDraft(jdbc, id(202), 91_002L, "STUDENT-2", "DRAFT");
        seedBatch(jdbc, id(401), id(201));
        seedItem(jdbc, id(401));

        assertThatThrownBy(() -> insertApplication(jdbc, id(301), 91_001L,
                id(202), "STUDENT-2"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertApplication(jdbc, id(302), 91_002L,
                id(202), "SOMEONE-ELSE"))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertApplication(jdbc, id(303), 91_002L, id(202), "STUDENT-2");

        assertConstraintRejects(jdbc, "UPDATE recruitment_task SET status='BROKEN' WHERE id=91001");
        assertConstraintRejects(jdbc, "UPDATE recruitment_draft SET status='BROKEN' WHERE id='" + id(201) + "'");
        assertConstraintRejects(jdbc, "UPDATE recruitment_upload_batch SET mode='BROKEN' WHERE id='" + id(401) + "'");
        assertConstraintRejects(jdbc, "UPDATE recruitment_upload_batch SET status='BROKEN' WHERE id='" + id(401) + "'");
        assertConstraintRejects(jdbc, "UPDATE recruitment_upload_item SET status='BROKEN' WHERE batch_id='" + id(401) + "'");
        assertConstraintRejects(jdbc, "UPDATE recruitment_task SET status='draft' WHERE id=91001");
    }

    @Test
    void v27DataUpgradesThroughV29AndV30WithoutOpeningReplayWindow() {
        DataSource dataSource = database();
        migrate(dataSource, "27");
        JdbcClient jdbc = JdbcClient.create(dataSource);
        seedUser(jdbc);
        seedTask(jdbc, 92_001L, id(111), "PUBLISHED");

        String submittedDraft = id(211);
        String openDraft = id(212);
        jdbc.sql("""
                INSERT INTO recruitment_draft
                    (id, task_id, token_hash, status, expires_at, created_at, updated_at)
                VALUES (:id, 92001, :hash, 'SUBMITTED',
                        TIMESTAMPADD(DAY, 30, CURRENT_TIMESTAMP(6)),
                        TIMESTAMPADD(MINUTE, -10, CURRENT_TIMESTAMP(6)), CURRENT_TIMESTAMP(6))
                """).param("id", submittedDraft).param("hash", "a".repeat(64)).update();
        jdbc.sql("""
                INSERT INTO recruitment_application
                    (id, task_id, draft_id, student_id, normalized_student_id,
                     answers_json, form_schema_json, submitted_at)
                VALUES (:id, 92001, :draftId, 'legacy-1', 'LEGACY-1',
                        '{}', '{}', CURRENT_TIMESTAMP(6))
                """).param("id", id(311)).param("draftId", submittedDraft).update();
        jdbc.sql("""
                INSERT INTO recruitment_draft
                    (id, task_id, token_hash, status, expires_at, created_at, updated_at)
                VALUES (:id, 92001, :hash, 'DRAFT',
                        TIMESTAMPADD(DAY, 30, CURRENT_TIMESTAMP(6)),
                        TIMESTAMPADD(MINUTE, -10, CURRENT_TIMESTAMP(6)), CURRENT_TIMESTAMP(6))
                """).param("id", openDraft).param("hash", "b".repeat(64)).update();
        jdbc.sql("""
                INSERT INTO recruitment_upload_batch
                    (id, draft_id, mode, archive_object_key, archive_file_name, archive_size,
                     status, total_count, success_count, failure_count, created_at, updated_at)
                VALUES (:id, :draftId, 'ZIP', 'temporary/recruitment/archive.zip',
                        'archive.zip', 1024, 'UPLOADING', 0, 0, 0,
                        TIMESTAMPADD(MINUTE, -10, CURRENT_TIMESTAMP(6)), CURRENT_TIMESTAMP(6))
                """).param("id", id(411)).param("draftId", openDraft).update();
        jdbc.sql("""
                INSERT INTO recruitment_upload_item
                    (batch_id, original_file_name, temp_object_key, content_type, size,
                     sha256, status, created_at, updated_at)
                VALUES (:batchId, 'legacy.jpg', 'temporary/recruitment/legacy.jpg',
                        'image/jpeg', 512, :sha, 'UPLOADING',
                        TIMESTAMPADD(MINUTE, -10, CURRENT_TIMESTAMP(6)), CURRENT_TIMESTAMP(6))
                """).param("batchId", id(411)).param("sha", "c".repeat(64)).update();

        migrate(dataSource, "29");

        assertThat(jdbc.sql("SELECT normalized_student_id FROM recruitment_draft WHERE id=:id")
                .param("id", submittedDraft).query(String.class).single()).isEqualTo("LEGACY-1");
        assertThat(jdbc.sql("SELECT normalized_student_id FROM recruitment_draft WHERE id=:id")
                .param("id", openDraft).query(String.class).single()).isEqualTo("LEGACY_UNBOUND");
        assertThat(jdbc.sql("SELECT status FROM recruitment_draft WHERE id=:id")
                .param("id", openDraft).query(String.class).single()).isEqualTo("EXPIRED");
        LocalDateTime legacyBatchExpiry = jdbc.sql("""
                SELECT upload_url_expires_at FROM recruitment_upload_batch WHERE id=:id
                """).param("id", id(411)).query(LocalDateTime.class).single();
        LocalDateTime batchCreatedAt = jdbc.sql("""
                SELECT created_at FROM recruitment_upload_batch WHERE id=:id
                """).param("id", id(411)).query(LocalDateTime.class).single();
        assertThat(legacyBatchExpiry).isEqualTo(batchCreatedAt);

        LocalDateTime beforeV30 = LocalDateTime.now();
        migrate(dataSource, null);

        LocalDateTime batchExpiry = jdbc.sql("""
                SELECT upload_url_expires_at FROM recruitment_upload_batch WHERE id=:id
                """).param("id", id(411)).query(LocalDateTime.class).single();
        LocalDateTime itemExpiry = jdbc.sql("""
                SELECT upload_url_expires_at FROM recruitment_upload_item WHERE batch_id=:batchId
                """).param("batchId", id(411)).query(LocalDateTime.class).single();
        assertThat(batchExpiry).isAfter(beforeV30.plusDays(6));
        assertThat(itemExpiry).isAfter(beforeV30.plusDays(6));
        assertThat(batchExpiry).isBefore(beforeV30.plusDays(8));
        assertThat(itemExpiry).isBefore(beforeV30.plusDays(8));

        assertConstraintRejects(jdbc,
                "UPDATE recruitment_draft SET status='UNKNOWN' WHERE id='" + openDraft + "'");
    }

    private static DataSource database() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:recruitment_migration_"
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

    private static void seedUser(JdbcClient jdbc) {
        jdbc.sql("""
                INSERT INTO app_user
                    (id, username, password_hash, display_name, role, enabled,
                     must_change_password, version, deleted, created_at, updated_at)
                VALUES (90001, 'migration-review', 'hash', '迁移测试', 'ADMIN', TRUE,
                        FALSE, 1, FALSE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """).update();
    }

    private static void seedTask(JdbcClient jdbc, long taskId, String publicId, String status) {
        jdbc.sql("""
                INSERT INTO recruitment_task
                    (id, public_id, title, form_schema_json, student_id_label, upload_label,
                     upload_required, starts_at, ends_at, status, created_by,
                     version, deleted, created_at, updated_at)
                VALUES (:id, :publicId, '迁移测试招募', '{}', '学号', '附件', FALSE,
                        TIMESTAMPADD(DAY, -1, CURRENT_TIMESTAMP(6)),
                        TIMESTAMPADD(DAY, 30, CURRENT_TIMESTAMP(6)), :status, 90001,
                        1, FALSE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """).param("id", taskId).param("publicId", publicId)
                .param("status", status).update();
    }

    private static void seedDraft(JdbcClient jdbc, String draftId, long taskId,
                                  String studentId, String status) {
        jdbc.sql("""
                INSERT INTO recruitment_draft
                    (id, task_id, normalized_student_id, token_hash, status,
                     expires_at, created_at, updated_at)
                VALUES (:id, :taskId, :studentId, :hash, :status,
                        TIMESTAMPADD(DAY, 30, CURRENT_TIMESTAMP(6)),
                        CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """).param("id", draftId).param("taskId", taskId)
                .param("studentId", studentId).param("hash", draftId.equals(id(201))
                        ? "d".repeat(64) : "e".repeat(64))
                .param("status", status).update();
    }

    private static void seedBatch(JdbcClient jdbc, String batchId, String draftId) {
        jdbc.sql("""
                INSERT INTO recruitment_upload_batch
                    (id, draft_id, mode, status, total_count, success_count, failure_count,
                     created_at, updated_at)
                VALUES (:id, :draftId, 'FILES', 'UPLOADING', 1, 0, 0,
                        CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """).param("id", batchId).param("draftId", draftId).update();
    }

    private static void seedItem(JdbcClient jdbc, String batchId) {
        jdbc.sql("""
                INSERT INTO recruitment_upload_item
                    (batch_id, original_file_name, content_type, size, sha256, status,
                     created_at, updated_at)
                VALUES (:batchId, 'test.jpg', 'image/jpeg', 1, :sha, 'UPLOADING',
                        CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """).param("batchId", batchId).param("sha", "f".repeat(64)).update();
    }

    private static void insertApplication(JdbcClient jdbc, String applicationId, long taskId,
                                          String draftId, String studentId) {
        jdbc.sql("""
                INSERT INTO recruitment_application
                    (id, task_id, draft_id, student_id, normalized_student_id,
                     answers_json, form_schema_json, submitted_at, created_at)
                VALUES (:id, :taskId, :draftId, :studentId, :studentId,
                        '{}', '{}', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """).param("id", applicationId).param("taskId", taskId)
                .param("draftId", draftId).param("studentId", studentId).update();
    }

    private static void assertConstraintRejects(JdbcClient jdbc, String sql) {
        assertThatThrownBy(() -> jdbc.sql(sql).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static String id(int value) {
        return "01J" + String.format("%023d", value);
    }
}
