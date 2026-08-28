package cn.photolib.backup;

import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.PageResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.util.PublicId;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.user.model.UserRole;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 备份编排的端到端测试。
 *
 * <p>这里用独立的数据源（独立的 Spring 上下文）：回滚会清空整库，跑在共享测试库上
 * 会连带清掉其他测试类写入的数据。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:photolib-backup;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "photolib.backup.enabled=true",
        "photolib.backup.minimum-retained=0",
})
class DatabaseBackupServiceTests {
    private static final AuthenticatedUser ADMIN =
            new AuthenticatedUser(1L, "admin", "系统管理员", UserRole.ADMIN, null, false);
    private static final AuthenticatedUser MINISTER =
            new AuthenticatedUser(2L, "minister", "部长", UserRole.MINISTER, null, false);

    @Autowired DatabaseBackupService service;
    @Autowired DatabaseDumpService dump;
    @Autowired DatabaseBackupMapper backups;
    @Autowired DatabaseRestoreMapper restores;
    @Autowired ObjectStorageService storage;
    @Autowired JdbcClient jdbc;

    @Test
    void scheduledBackupUploadsObjectAndRecordsItsContents() {
        long campusId = System.nanoTime() & Long.MAX_VALUE;
        insertCampus(campusId, "nightly");

        service.runScheduledBackup();

        DatabaseBackupEntity backup = latestBackup();
        assertThat(backup.getType()).isEqualTo(DatabaseBackupService.TYPE_SCHEDULED);
        assertThat(backup.getStatus()).isEqualTo(DatabaseBackupService.STATUS_SUCCEEDED);
        assertThat(backup.getObjectKey()).startsWith("backups/");
        assertThat(backup.getSha256()).hasSize(64);
        assertThat(backup.getRowCount()).isPositive();
        assertThat(backup.getSchemaVersion()).isNotEqualTo("unknown");
        assertThat(storage.find(backup.getObjectKey())).isPresent();
        assertThat(storage.find(backup.getObjectKey()).orElseThrow().size())
                .isEqualTo(backup.getSizeBytes());
    }

    @Test
    void restoreBringsDeletedRowsBackAndKeepsASafetyBackup() throws Exception {
        long campusId = System.nanoTime() & Long.MAX_VALUE;
        insertCampus(campusId, "restore");
        service.runScheduledBackup();
        String backupId = latestBackup().getId();

        jdbc.sql("DELETE FROM campus WHERE id = :id").param("id", campusId).update();
        assertThat(campusCount(campusId)).isZero();

        DatabaseBackupService.RestoreView started = service.startRestore(backupId, ADMIN);
        DatabaseRestoreEntity restore = awaitRestore(started.id());

        assertThat(restore.getStatus()).isEqualTo(DatabaseBackupService.STATUS_SUCCEEDED);
        assertThat(restore.getRowCount()).isPositive();
        assertThat(campusCount(campusId)).isEqualTo(1);

        // 回滚记录本身不在备份范围内，所以它在整库被替换后依然存在，兜底备份也一样。
        DatabaseBackupEntity safety = backups.selectById(restore.getSafetyBackupId());
        assertThat(safety.getType()).isEqualTo(DatabaseBackupService.TYPE_PRE_RESTORE);
        assertThat(safety.getStatus()).isEqualTo(DatabaseBackupService.STATUS_SUCCEEDED);
        assertThat(storage.find(safety.getObjectKey())).isPresent();
    }

    @Test
    void backupContentSurvivesGzipAndCanBeReadBack() throws Exception {
        service.runScheduledBackup();
        DatabaseBackupEntity backup = latestBackup();

        byte[] stored;
        try (var input = storage.open(backup.getObjectKey())) {
            stored = input.readAllBytes();
        }
        try (var gzip = new java.util.zip.GZIPInputStream(new ByteArrayInputStream(stored))) {
            String manifest = new java.io.BufferedReader(
                    new java.io.InputStreamReader(gzip, java.nio.charset.StandardCharsets.UTF_8)).readLine();
            assertThat(manifest).contains(DatabaseDumpService.FORMAT_NAME).contains("\"tables\"");
            // 备份目录自身绝不能出现在清单里。
            assertThat(manifest).doesNotContain("\"database_backup\"");
        }
    }

    @Test
    void onlySystemAdministratorsMayBackUpOrRollBack() {
        assertThatThrownBy(() -> service.startManualBackup(MINISTER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅系统管理员");
        assertThatThrownBy(() -> service.listBackups(1, 20, MINISTER))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.startRestore("ANY", MINISTER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅系统管理员");
        assertThatThrownBy(() -> service.startManualBackup(null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void refusesToRollBackToABackupThatHasNoUsableFile() {
        DatabaseBackupEntity failed = new DatabaseBackupEntity();
        failed.setId(PublicId.next());
        failed.setType(DatabaseBackupService.TYPE_MANUAL);
        failed.setStatus(DatabaseBackupService.STATUS_FAILED);
        failed.setStartedAt(LocalDateTime.now());
        backups.insert(failed);

        assertThatThrownBy(() -> service.startRestore(failed.getId(), ADMIN))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只能回滚到已成功");
        assertThatThrownBy(() -> service.startRestore("MISSINGBACKUPIDENTIFIER00", ADMIN))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void refusesToRollBackAcrossASchemaVersionChange() {
        DatabaseBackupEntity stale = new DatabaseBackupEntity();
        stale.setId(PublicId.next());
        stale.setType(DatabaseBackupService.TYPE_MANUAL);
        stale.setStatus(DatabaseBackupService.STATUS_SUCCEEDED);
        stale.setObjectKey("backups/2020/01/stale.jsonl.gz");
        stale.setSchemaVersion("1");
        stale.setStartedAt(LocalDateTime.now());
        backups.insert(stale);

        assertThatThrownBy(() -> service.startRestore(stale.getId(), ADMIN))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无法回滚");

        PageResponse<DatabaseBackupService.BackupView> page = service.listBackups(1, 100, ADMIN);
        DatabaseBackupService.BackupView view = page.items().stream()
                .filter(item -> item.id().equals(stale.getId())).findFirst().orElseThrow();
        assertThat(view.downloadable()).isTrue();
        assertThat(view.restorable()).isFalse();
    }

    @Test
    void pruneDeletesExpiredObjectsButKeepsTheRecord() throws Exception {
        String objectKey = "backups/2020/01/" + PublicId.next() + ".jsonl.gz";
        storage.put(objectKey, new ByteArrayInputStream(new byte[]{1, 2, 3}), 3, "application/gzip");
        DatabaseBackupEntity old = new DatabaseBackupEntity();
        old.setId(PublicId.next());
        old.setType(DatabaseBackupService.TYPE_SCHEDULED);
        old.setStatus(DatabaseBackupService.STATUS_SUCCEEDED);
        old.setObjectKey(objectKey);
        old.setSizeBytes(3L);
        old.setStartedAt(LocalDateTime.now().minusDays(400));
        backups.insert(old);

        service.prune();

        DatabaseBackupEntity pruned = backups.selectById(old.getId());
        assertThat(pruned.getStatus()).isEqualTo(DatabaseBackupService.STATUS_EXPIRED);
        assertThat(pruned.getObjectKey()).isNull();
        assertThat(storage.find(objectKey)).isEmpty();
    }

    @Test
    void importsADownloadedBackupFileAndCanRollBackToIt() throws Exception {
        long campusId = System.nanoTime() & Long.MAX_VALUE;
        insertCampus(campusId, "import");
        service.runScheduledBackup();
        DatabaseBackupEntity source = latestBackup();
        byte[] archive = readObject(source.getObjectKey());

        // 管理员把刚下载下来的那份文件原样传回来。
        DatabaseBackupService.BackupView imported = service.importUploaded(
                new MockMultipartFile("file", "photolib-backup-1.jsonl.gz", "application/gzip", archive), ADMIN);

        assertThat(imported.type()).isEqualTo(DatabaseBackupService.TYPE_UPLOADED);
        assertThat(imported.status()).isEqualTo(DatabaseBackupService.STATUS_SUCCEEDED);
        assertThat(imported.sourceFileName()).isEqualTo("photolib-backup-1.jsonl.gz");
        assertThat(imported.sha256()).isEqualTo(source.getSha256());
        assertThat(imported.rowCount()).isEqualTo(source.getRowCount());
        assertThat(imported.tableCount()).isEqualTo(source.getTableCount());
        assertThat(imported.restorable()).isTrue();
        assertThat(imported.createdByName()).isEqualTo(ADMIN.displayName());
        // 导入的对象必须是独立的一份，不能复用原备份的 object key。
        assertThat(backups.selectById(imported.id()).getObjectKey())
                .isNotEqualTo(source.getObjectKey())
                .startsWith("backups/uploads/");

        jdbc.sql("DELETE FROM campus WHERE id = :id").param("id", campusId).update();
        DatabaseRestoreEntity restore = awaitRestore(service.startRestore(imported.id(), ADMIN).id());

        assertThat(restore.getStatus()).isEqualTo(DatabaseBackupService.STATUS_SUCCEEDED);
        assertThat(campusCount(campusId)).isEqualTo(1);
    }

    @Test
    void rejectsUploadsThatAreNotIntactBackupArchives() throws Exception {
        service.runScheduledBackup();
        byte[] archive = readObject(latestBackup().getObjectKey());

        assertThatThrownBy(() -> service.importUploaded(upload(new byte[0]), ADMIN))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请选择要导入的备份文件");
        assertThatThrownBy(() -> service.importUploaded(
                upload("这不是备份文件".getBytes(StandardCharsets.UTF_8)), ADMIN))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只接受本系统导出的");
        // 截断的归档：gzip 自带的 CRC 与长度校验必须把它挡下来。
        assertThatThrownBy(() -> service.importUploaded(
                upload(Arrays.copyOf(archive, archive.length - 32)), ADMIN))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("gzip 归档");
        assertThatThrownBy(() -> service.importUploaded(
                upload(gzip("{\"format\":\"mysqldump\",\"version\":1}")), ADMIN))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("格式无法识别");
        assertThatThrownBy(() -> service.importUploaded(upload(archive), MINISTER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅系统管理员");
    }

    @Test
    void rejectsUploadsWhoseTableOrColumnStructureDoesNotMatchTheDatabase() throws Exception {
        long before = uploadedBackupCount();

        assertThatThrownBy(() -> service.importUploaded(upload(gzip(
                manifest("campus"),
                "{\"table\":\"campus\",\"columns\":[\"id\"],\"types\":[-5]}",
                "{\"endTable\":\"campus\",\"rows\":0}")), ADMIN))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("缺少字段");
        assertThatThrownBy(() -> service.importUploaded(upload(gzip(
                manifest("not_a_real_table"))), ADMIN))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("当前数据库缺少备份中的数据表");
        assertThatThrownBy(() -> service.importUploaded(upload(gzip(
                manifest("database_backup"))), ADMIN))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("系统自身维护的数据表");

        // 被拒绝的文件一条记录都不该留下。
        assertThat(uploadedBackupCount()).isEqualTo(before);
    }

    private long uploadedBackupCount() {
        return backups.selectCount(Wrappers.<DatabaseBackupEntity>lambdaQuery()
                .eq(DatabaseBackupEntity::getType, DatabaseBackupService.TYPE_UPLOADED));
    }

    private MockMultipartFile upload(byte[] content) {
        return new MockMultipartFile("file", "backup.jsonl.gz", "application/gzip", content);
    }

    private byte[] gzip(String... lines) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
            gzip.write(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        }
        return buffer.toByteArray();
    }

    private String manifest(String... tables) throws Exception {
        DatabaseDumpService.SchemaState schema = dump.currentSchemaState();
        String names = String.join(",", Arrays.stream(tables).map(table -> '"' + table + '"').toList());
        return "{\"format\":\"photolib-backup\",\"version\":1,\"schemaVersion\":\"%s\","
                .formatted(schema.version())
                + "\"migrationCount\":%d,\"tables\":[%s]}".formatted(schema.migrationCount(), names);
    }

    private byte[] readObject(String objectKey) throws IOException {
        try (var input = storage.open(objectKey)) {
            return input.readAllBytes();
        }
    }

    /**
     * 好图精选的三张表没有出现在 {@code EXCLUDED_TABLES} 里，因此必须随整库一起
     * 备份和回滚——这是新模块与备份能力唯一真正相交的地方。用例覆盖到条目表，
     * 因为它是三张表里唯一带外键指向 photo / campus / app_user 的，
     * 回滚时的外键顺序问题只会在这种表上暴露出来。
     */
    @Test
    void featuredCollectionsAreBackedUpAndRestoredWithTheRestOfTheDatabase() throws Exception {
        long campusId = System.nanoTime() & Long.MAX_VALUE;
        insertCampus(campusId, "featured");
        long collectionId = insertFeaturedCollection(campusId);

        service.runScheduledBackup();
        String backupId = latestBackup().getId();

        // 精选、指派和条目一起被删掉，模拟一次误操作。
        jdbc.sql("DELETE FROM featured_entry WHERE collection_id = :id")
                .param("id", collectionId).update();
        jdbc.sql("DELETE FROM featured_collection_assignment WHERE collection_id = :id")
                .param("id", collectionId).update();
        jdbc.sql("DELETE FROM featured_collection WHERE id = :id").param("id", collectionId).update();
        assertThat(featuredCount("featured_collection", collectionId)).isZero();

        DatabaseRestoreEntity restore = awaitRestore(service.startRestore(backupId, ADMIN).id());
        assertThat(restore.getStatus()).isEqualTo(DatabaseBackupService.STATUS_SUCCEEDED);

        assertThat(featuredCount("featured_collection", collectionId)).isEqualTo(1);
        assertThat(featuredCount("featured_collection_assignment", collectionId)).isEqualTo(1);
        assertThat(featuredCount("featured_entry", collectionId)).isEqualTo(1);
        // 条目的文字快照必须原样回来，Word 文档正是照着它出的。
        assertThat(jdbc.sql("SELECT idea FROM featured_entry WHERE collection_id = :id")
                .param("id", collectionId).query(String.class).single())
                .isEqualTo("备份回滚后仍应保留的拍摄思路");
    }

    /** 建一份已截止的精选，连带一条校区指派和一条带图片的条目。 */
    private long insertFeaturedCollection(long campusId) {
        // 这个上下文用的是独立空库，bootstrap 也关着，外键需要的账号得自己建。
        long userId = insertUser();
        long photoId = insertPhoto(campusId, userId);
        jdbc.sql("""
                INSERT INTO featured_collection
                    (title, requirement_html, requirement_text, starts_at, ends_at, status,
                     assign_all, entry_limit, document_status, created_by)
                VALUES ('备份测试精选', '<p>要求</p>', '要求', :start, :end, 'CLOSED',
                        FALSE, 10, 'PENDING', :userId)
                """).param("start", LocalDateTime.now().minusDays(2))
                .param("end", LocalDateTime.now().minusDays(1)).param("userId", userId).update();
        long collectionId = jdbc.sql(
                "SELECT id FROM featured_collection WHERE title = '备份测试精选' ORDER BY id DESC LIMIT 1")
                .query(Long.class).single();
        jdbc.sql("""
                INSERT INTO featured_collection_assignment (collection_id, campus_id)
                VALUES (:collectionId, :campusId)
                """).param("collectionId", collectionId).param("campusId", campusId).update();
        jdbc.sql("""
                INSERT INTO featured_entry
                    (collection_id, photo_id, campus_id, submitted_by, idea, location,
                     photographer_name, photographer_student_id, taken_at, photo_title, sort_order)
                VALUES (:collectionId, :photoId, :campusId, :userId, '备份回滚后仍应保留的拍摄思路',
                        '东校区', '拍摄者', 'S001', :takenAt, '备份测试作品', 1)
                """).param("collectionId", collectionId).param("photoId", photoId)
                .param("campusId", campusId).param("userId", userId)
                .param("takenAt", LocalDateTime.now().minusDays(3))
                .update();
        return collectionId;
    }

    private long insertUser() {
        String username = "featured-backup-" + System.nanoTime();
        jdbc.sql("""
                INSERT INTO app_user
                    (username, password_hash, display_name, role, enabled, must_change_password)
                VALUES (:username, 'hash', '精选备份测试账号', 'MINISTER', TRUE, FALSE)
                """).param("username", username).update();
        return jdbc.sql("SELECT id FROM app_user WHERE username = :username")
                .param("username", username).query(Long.class).single();
    }

    private long insertPhoto(long campusId, long userId) {
        String objectKey = "photos/backup-featured-" + System.nanoTime() + ".jpg";
        jdbc.sql("""
                INSERT INTO photo
                    (photographer_student_id, photographer_name, uploaded_by, campus_id, taken_at,
                     size, content_type, object_key, sha256, status, title)
                VALUES ('S001', '拍摄者', :userId, :campusId, :takenAt, 1024, 'image/jpeg', :objectKey,
                        '0000000000000000000000000000000000000000000000000000000000000000',
                        'AVAILABLE', '备份测试作品')
                """).param("userId", userId).param("campusId", campusId)
                .param("takenAt", LocalDateTime.now().minusDays(3))
                .param("objectKey", objectKey).update();
        return jdbc.sql("SELECT id FROM photo WHERE object_key = :objectKey")
                .param("objectKey", objectKey).query(Long.class).single();
    }

    private long featuredCount(String table, long collectionId) {
        String column = "featured_collection".equals(table) ? "id" : "collection_id";
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = :id")
                .param("id", collectionId).query(Long.class).single();
    }

    private void insertCampus(long id, String prefix) {
        jdbc.sql("INSERT INTO campus (id, code, name) VALUES (:id, :code, '备份测试校区')")
                .param("id", id).param("code", prefix + "-" + id).update();
    }

    private long campusCount(long id) {
        return jdbc.sql("SELECT COUNT(*) FROM campus WHERE id = :id")
                .param("id", id).query(Long.class).single();
    }

    private DatabaseBackupEntity latestBackup() {
        return backups.selectList(Wrappers.<DatabaseBackupEntity>lambdaQuery()
                .orderByDesc(DatabaseBackupEntity::getStartedAt)
                .last("LIMIT 1")).getFirst();
    }

    private DatabaseRestoreEntity awaitRestore(String id) throws InterruptedException {
        for (int attempt = 0; attempt < 300; attempt++) {
            DatabaseRestoreEntity restore = restores.selectById(id);
            if (restore != null && !DatabaseBackupService.STATUS_RUNNING.equals(restore.getStatus())) {
                return restore;
            }
            Thread.sleep(100);
        }
        return restores.selectById(id);
    }
}
