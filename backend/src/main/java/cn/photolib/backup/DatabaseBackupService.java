package cn.photolib.backup;

import cn.photolib.admin.AdminAlertEntity;
import cn.photolib.admin.AdminAlertMapper;
import cn.photolib.auth.AuthenticatedUser;
import cn.photolib.common.api.PageResponse;
import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import cn.photolib.common.util.PublicId;
import cn.photolib.storage.ObjectStorageService;
import cn.photolib.storage.StorageProperties;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.multipart.MultipartFile;

import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;

/**
 * 数据库备份与回滚的编排层：负责生成备份对象、上传到对象存储、记录任务状态，
 * 以及把某个备份写回数据库。
 *
 * <p>权限只按"系统管理员"判定（{@link AuthenticatedUser#isAdministrator()}），刻意没有对应的
 * {@link cn.photolib.permission.PermissionCode}，因此不会出现在权限面板里，也无法授予其他权限组。
 *
 * <p>整个流程假设单实例部署：并发控制依赖进程内的 {@link #lock} 与"是否已有任务在跑"的库内检查。
 * 如果将来要多实例部署，必须先换成数据库层的抢占锁，否则两台机器可能同时回滚。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseBackupService {
    public static final String TYPE_SCHEDULED = "SCHEDULED";
    public static final String TYPE_MANUAL = "MANUAL";
    public static final String TYPE_PRE_RESTORE = "PRE_RESTORE";
    public static final String TYPE_UPLOADED = "UPLOADED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    /** 进程被强杀会留下永远 RUNNING 的记录，超过这个时长就不再视为"有任务在跑"。 */
    private static final Duration STALE_RUNNING = Duration.ofHours(6);
    private static final int MAX_PAGE_SIZE = 100;

    private final DatabaseBackupMapper backups;
    private final DatabaseRestoreMapper restores;
    private final DatabaseDumpService dump;
    private final ObjectStorageService storage;
    private final StorageProperties storageProperties;
    private final AdminAlertMapper alerts;
    private final BackupProperties properties;
    private final ApplicationEventPublisher events;

    private final ReentrantLock lock = new ReentrantLock();

    public record BackupRequested(String backupId) {}

    public record RestoreRequested(String restoreId) {}

    public record BackupView(
            String id, String type, String status, Long sizeBytes, String sha256,
            Integer tableCount, Long rowCount, String schemaVersion, String errorMessage,
            String sourceFileName, Long createdBy, String createdByName,
            LocalDateTime startedAt, LocalDateTime finishedAt,
            boolean downloadable, boolean restorable) {}

    public record RestoreView(
            String id, String backupId, String safetyBackupId, String status,
            Integer tableCount, Long rowCount, String errorMessage,
            Long createdBy, String createdByName, LocalDateTime startedAt, LocalDateTime finishedAt) {}

    public record DownloadLink(String url, String fileName, Instant expiresAt) {}

    // ---------------------------------------------------------------- 手动备份

    @Transactional
    public BackupView startManualBackup(AuthenticatedUser user) {
        requireAdmin(user);
        requireIdle();
        DatabaseBackupEntity backup = newBackup(TYPE_MANUAL, user);
        backups.insert(backup);
        events.publishEvent(new BackupRequested(backup.getId()));
        return toView(backup);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBackupRequested(BackupRequested event) {
        execute(event.backupId());
    }

    /** 每日自动备份入口。调度线程本身就是后台线程，这里同步执行。 */
    public void runScheduledBackup() {
        if (!properties.enabled()) {
            log.info("每日数据库备份已通过配置关闭，跳过本次执行");
            return;
        }
        if (hasRunningOperation()) {
            log.warn("已有数据库备份或回滚任务在执行，跳过本次每日备份");
            return;
        }
        DatabaseBackupEntity backup = newBackup(TYPE_SCHEDULED, null);
        backups.insert(backup);
        execute(backup.getId());
    }

    private void execute(String backupId) {
        if (!lock.tryLock()) {
            fail(backupId, "另一个数据库备份或回滚任务正在执行");
            return;
        }
        try {
            performBackup(backupId);
            prune();
        } finally {
            lock.unlock();
        }
    }

    /** 生成一次备份并上传。失败会写入记录与管理员告警，不向调用方抛出。 */
    private void performBackup(String backupId) {
        DatabaseBackupEntity backup = backups.selectById(backupId);
        if (backup == null) {
            log.error("数据库备份记录不存在: {}", backupId);
            return;
        }
        try {
            completeBackup(backup);
        } catch (Exception failure) {
            log.error("数据库备份失败: {}", backupId, failure);
            fail(backupId, message(failure));
            alert("DATABASE_BACKUP_FAILED", "数据库备份失败：" + message(failure), backupId);
        }
    }

    /**
     * 真正执行导出。先落到临时文件，得到确定的大小与摘要后再上传，
     * 这样对象存储侧永远不会出现"长度未知"的半截备份。
     */
    private void completeBackup(DatabaseBackupEntity backup) throws Exception {
        Path temp = Files.createTempFile("photolib-backup-", ".jsonl.gz");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            DatabaseDumpService.DumpResult result;
            try (OutputStream file = Files.newOutputStream(temp);
                 DigestOutputStream digested = new DigestOutputStream(file, digest);
                 GZIPOutputStream gzip = new GZIPOutputStream(digested)) {
                result = dump.dump(gzip);
            }
            long size = Files.size(temp);
            String objectKey = objectKey(backup.getId());
            try (InputStream content = Files.newInputStream(temp)) {
                storage.put(objectKey, content, size, "application/gzip");
            }
            backup.setObjectKey(objectKey);
            backup.setSizeBytes(size);
            backup.setSha256(HexFormat.of().formatHex(digest.digest()));
            backup.setTableCount(result.tableCount());
            backup.setRowCount(result.rowCount());
            backup.setSchemaVersion(result.schemaVersion());
            backup.setMigrationCount(result.migrationCount());
            backup.setStatus(STATUS_SUCCEEDED);
            backup.setFinishedAt(LocalDateTime.now());
            backups.updateById(backup);
            log.info("数据库备份完成: id={}, 表={}, 行={}, 字节={}",
                    backup.getId(), result.tableCount(), result.rowCount(), size);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    // ---------------------------------------------------------------- 导入上传的备份

    /**
     * 导入管理员上传的备份文件（通常是之前从本系统下载下来的那一份）。
     *
     * <p>文件在入库前必须**完整**通过校验：gzip 归档完好、格式与版本可识别、结构版本与当前库
     * 一致、清单里的每张表都存在、每张表的字段集合与类型族都对得上、每行字段数量与取值形态
     * 合法。只有全部通过才写入对象存储并登记成一条 `UPLOADED` 备份，之后走与其他备份完全
     * 相同的回滚路径（含兜底备份与 SHA-256 校验）。
     *
     * <p>这道校验必须发生在导入时而不是回滚时：回滚是先清空整库再写回，等到写到一半才发现
     * 文件缺字段，损失已经造成，只能靠兜底备份救回来。
     */
    public BackupView importUploaded(MultipartFile file, AuthenticatedUser user) {
        requireAdmin(user);
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择要导入的备份文件");
        }
        long maxUpload = properties.maxUploadBytes().toBytes();
        if (file.getSize() > maxUpload) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE,
                    "备份文件不能超过 " + properties.maxUploadBytes().toMegabytes() + " MB");
        }
        String fileName = sanitizeFileName(file.getOriginalFilename());
        Path temp = null;
        try {
            temp = Files.createTempFile("photolib-backup-import-", ".jsonl.gz");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size;
            try (InputStream upload = file.getInputStream();
                 DigestInputStream digested = new DigestInputStream(upload, digest)) {
                size = Files.copy(digested, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            if (size == 0) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "备份文件为空");
            }
            if (size > maxUpload) {
                throw new BusinessException(ErrorCode.FILE_TOO_LARGE,
                        "备份文件不能超过 " + properties.maxUploadBytes().toMegabytes() + " MB");
            }
            requireGzip(temp);

            DatabaseDumpService.ValidationResult validated = validateArchive(temp);

            String id = PublicId.next();
            LocalDateTime now = LocalDateTime.now();
            String objectKey = "backups/uploads/%04d/%02d/%s.jsonl.gz"
                    .formatted(now.getYear(), now.getMonthValue(), id);
            try (InputStream content = Files.newInputStream(temp)) {
                storage.put(objectKey, content, size, "application/gzip");
            }

            DatabaseBackupEntity backup = new DatabaseBackupEntity();
            backup.setId(id);
            backup.setType(TYPE_UPLOADED);
            backup.setStatus(STATUS_SUCCEEDED);
            backup.setObjectKey(objectKey);
            backup.setSizeBytes(size);
            backup.setSha256(HexFormat.of().formatHex(digest.digest()));
            backup.setTableCount(validated.tableCount());
            backup.setRowCount(validated.rowCount());
            backup.setSchemaVersion(validated.schemaVersion());
            backup.setMigrationCount(validated.migrationCount());
            backup.setSourceFileName(fileName);
            backup.setCreatedBy(user.id());
            backup.setCreatedByName(user.displayName());
            backup.setStartedAt(now);
            backup.setFinishedAt(LocalDateTime.now());
            backups.insert(backup);
            log.info("已导入上传的备份: id={}, 文件={}, 表={}, 行={}, 字节={}",
                    id, fileName, validated.tableCount(), validated.rowCount(), size);
            return toView(backup);
        } catch (BusinessException known) {
            throw known;
        } catch (Exception failure) {
            log.error("导入备份文件失败: {}", fileName, failure);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "导入备份文件失败：" + message(failure));
        } finally {
            deleteQuietly(temp);
        }
    }

    /** 解压并干跑一遍上传的文件。gzip 自带 CRC，截断或改写的文件会在这里暴露。 */
    private DatabaseDumpService.ValidationResult validateArchive(Path archive) throws Exception {
        try (InputStream file = Files.newInputStream(archive);
             GZIPInputStream gzip = new GZIPInputStream(file);
             InputStream bounded = new BoundedInputStream(gzip, properties.maxDecompressedBytes().toBytes())) {
            return dump.validate(bounded);
        } catch (ZipException | EOFException broken) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "备份文件不是完整的 gzip 归档，可能已损坏或被截断");
        }
    }

    private void requireGzip(Path archive) throws IOException {
        byte[] magic = new byte[2];
        try (InputStream input = Files.newInputStream(archive)) {
            if (input.read(magic) != magic.length
                    || (magic[0] & 0xFF) != 0x1F || (magic[1] & 0xFF) != 0x8B) {
                throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE,
                        "只接受本系统导出的 .jsonl.gz 备份文件");
            }
        }
    }

    /** 只保留文件名本身，避免把上传方给的路径写进数据库或日志。 */
    private String sanitizeFileName(String original) {
        if (original == null || original.isBlank()) return null;
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (name.isEmpty()) return null;
        return name.length() <= 255 ? name : name.substring(0, 255);
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException failure) {
            log.warn("临时备份文件删除失败: {}", path, failure);
        }
    }

    /** 解压后的字节数上限，用来挡住压缩炸弹。 */
    private static final class BoundedInputStream extends FilterInputStream {
        private final long maximum;
        private long count;

        private BoundedInputStream(InputStream delegate, long maximum) {
            super(delegate);
            this.maximum = maximum;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) increment(1);
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            if (read > 0) increment(read);
            return read;
        }

        private void increment(long amount) {
            count += amount;
            if (count > maximum) {
                throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "备份文件解压后的体积超出允许范围");
            }
        }
    }

    // ---------------------------------------------------------------- 回滚

    @Transactional
    public RestoreView startRestore(String backupId, AuthenticatedUser user) {
        requireAdmin(user);
        requireIdle();
        DatabaseBackupEntity backup = requireRestorable(backupId);
        DatabaseRestoreEntity restore = new DatabaseRestoreEntity();
        restore.setId(PublicId.next());
        restore.setBackupId(backup.getId());
        restore.setStatus(STATUS_RUNNING);
        restore.setCreatedBy(user.id());
        restore.setCreatedByName(user.displayName());
        restore.setStartedAt(LocalDateTime.now());
        restores.insert(restore);
        events.publishEvent(new RestoreRequested(restore.getId()));
        return toView(restore);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRestoreRequested(RestoreRequested event) {
        if (!lock.tryLock()) {
            failRestore(event.restoreId(), "另一个数据库备份或回滚任务正在执行");
            return;
        }
        try {
            performRestore(event.restoreId());
        } finally {
            lock.unlock();
        }
    }

    private void performRestore(String restoreId) {
        DatabaseRestoreEntity restore = restores.selectById(restoreId);
        if (restore == null) {
            log.error("数据库回滚记录不存在: {}", restoreId);
            return;
        }
        try {
            DatabaseBackupEntity backup = requireRestorable(restore.getBackupId());

            // 先给"回滚前的现状"留一份兜底备份，管理员误回滚时还能再退回来。
            // 兜底备份失败就中止，绝不在没有退路的情况下覆盖现有数据。
            DatabaseBackupEntity safety = newBackup(TYPE_PRE_RESTORE, null);
            safety.setCreatedBy(restore.getCreatedBy());
            safety.setCreatedByName(restore.getCreatedByName());
            backups.insert(safety);
            restore.setSafetyBackupId(safety.getId());
            restores.updateById(restore);
            completeBackup(safety);

            Path temp = Files.createTempFile("photolib-restore-", ".jsonl.gz");
            try {
                download(backup, temp);
                DatabaseDumpService.RestoreResult result;
                try (InputStream file = Files.newInputStream(temp);
                     GZIPInputStream gzip = new GZIPInputStream(file)) {
                    result = dump.restore(gzip);
                }
                // database_restore 不在备份范围内，所以这条记录在库被整体替换后依然存在。
                restore.setStatus(STATUS_SUCCEEDED);
                restore.setTableCount(result.tableCount());
                restore.setRowCount(result.rowCount());
                restore.setFinishedAt(LocalDateTime.now());
                restores.updateById(restore);
                log.warn("数据库已回滚到备份 {}: 表={}, 行={}, 兜底备份={}",
                        backup.getId(), result.tableCount(), result.rowCount(), safety.getId());
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (Exception failure) {
            log.error("数据库回滚失败: {}", restoreId, failure);
            failRestore(restoreId, message(failure));
            alert("DATABASE_RESTORE_FAILED", "数据库回滚失败：" + message(failure), restoreId);
        }
    }

    /** 下载备份对象并校验大小与摘要——数据被改写前的最后一道关。 */
    private void download(DatabaseBackupEntity backup, Path target) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long copied;
        try (InputStream source = storage.open(backup.getObjectKey());
             DigestInputStream digested = new DigestInputStream(source, digest)) {
            copied = Files.copy(digested, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        if (backup.getSizeBytes() != null && backup.getSizeBytes() != copied) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "备份文件大小与记录不一致，可能已损坏，已中止回滚");
        }
        String sha256 = HexFormat.of().formatHex(digest.digest());
        if (backup.getSha256() != null && !backup.getSha256().equalsIgnoreCase(sha256)) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "备份文件校验失败，可能已损坏，已中止回滚");
        }
    }

    // ---------------------------------------------------------------- 查询

    public PageResponse<BackupView> listBackups(int page, int pageSize, AuthenticatedUser user) {
        requireAdmin(user);
        IPage<DatabaseBackupEntity> result = backups.selectPage(
                new Page<>(Math.max(page, 1), Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE)),
                Wrappers.<DatabaseBackupEntity>lambdaQuery().orderByDesc(DatabaseBackupEntity::getStartedAt));
        String schemaVersion = currentSchemaVersion();
        return new PageResponse<>(
                result.getRecords().stream().map(backup -> toView(backup, schemaVersion)).toList(),
                result.getCurrent(), result.getSize(), result.getTotal(), result.getPages());
    }

    public BackupView getBackup(String id, AuthenticatedUser user) {
        requireAdmin(user);
        DatabaseBackupEntity backup = backups.selectById(id);
        if (backup == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "备份记录不存在");
        return toView(backup);
    }

    public PageResponse<RestoreView> listRestores(int page, int pageSize, AuthenticatedUser user) {
        requireAdmin(user);
        IPage<DatabaseRestoreEntity> result = restores.selectPage(
                new Page<>(Math.max(page, 1), Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE)),
                Wrappers.<DatabaseRestoreEntity>lambdaQuery().orderByDesc(DatabaseRestoreEntity::getStartedAt));
        return new PageResponse<>(result.getRecords().stream().map(this::toView).toList(),
                result.getCurrent(), result.getSize(), result.getTotal(), result.getPages());
    }

    public RestoreView getRestore(String id, AuthenticatedUser user) {
        requireAdmin(user);
        DatabaseRestoreEntity restore = restores.selectById(id);
        if (restore == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "回滚记录不存在");
        return toView(restore);
    }

    public DownloadLink downloadLink(String id, AuthenticatedUser user) {
        requireAdmin(user);
        DatabaseBackupEntity backup = backups.selectById(id);
        if (backup == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "备份记录不存在");
        if (!STATUS_SUCCEEDED.equals(backup.getStatus()) || backup.getObjectKey() == null) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "该备份没有可下载的文件");
        }
        String fileName = backup.getSourceFileName() != null ? backup.getSourceFileName()
                : "photolib-backup-" + backup.getId() + ".jsonl.gz";
        ObjectStorageService.SignedUrl signed = storage.presignGet(
                backup.getObjectKey(), fileName, storageProperties.downloadUrlTtl());
        return new DownloadLink(signed.url().toString(), fileName, signed.expiresAt());
    }

    // ---------------------------------------------------------------- 保留期清理

    /**
     * 删除超过保留期的备份对象，但永远保留最近 {@code minimumRetained} 份成功备份。
     * 对象删除失败时保留记录不动，下一轮再试，避免出现指向已删对象的"可回滚"备份。
     */
    void prune() {
        LocalDateTime threshold = LocalDateTime.now().minus(properties.retention());
        List<DatabaseBackupEntity> succeeded = backups.selectList(
                Wrappers.<DatabaseBackupEntity>lambdaQuery()
                        .eq(DatabaseBackupEntity::getStatus, STATUS_SUCCEEDED)
                        .isNotNull(DatabaseBackupEntity::getObjectKey)
                        .orderByDesc(DatabaseBackupEntity::getStartedAt));
        for (int index = Math.max(properties.minimumRetained(), 0); index < succeeded.size(); index++) {
            DatabaseBackupEntity backup = succeeded.get(index);
            if (backup.getStartedAt() == null || backup.getStartedAt().isAfter(threshold)) continue;
            try {
                storage.delete(backup.getObjectKey());
            } catch (Exception failure) {
                log.warn("过期备份对象删除失败，保留记录待下次重试: {}", backup.getId(), failure);
                continue;
            }
            // updateById 会跳过 null 字段，清空 object_key 必须写成显式 set。
            backups.update(Wrappers.<DatabaseBackupEntity>lambdaUpdate()
                    .set(DatabaseBackupEntity::getStatus, STATUS_EXPIRED)
                    .set(DatabaseBackupEntity::getObjectKey, null)
                    .eq(DatabaseBackupEntity::getId, backup.getId()));
            log.info("过期备份已清理: {}", backup.getId());
        }
    }

    // ---------------------------------------------------------------- 内部工具

    private DatabaseBackupEntity newBackup(String type, AuthenticatedUser user) {
        DatabaseBackupEntity backup = new DatabaseBackupEntity();
        backup.setId(PublicId.next());
        backup.setType(type);
        backup.setStatus(STATUS_RUNNING);
        backup.setStartedAt(LocalDateTime.now());
        if (user != null) {
            backup.setCreatedBy(user.id());
            backup.setCreatedByName(user.displayName());
        }
        return backup;
    }

    private DatabaseBackupEntity requireRestorable(String backupId) {
        DatabaseBackupEntity backup = backups.selectById(backupId);
        if (backup == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "备份记录不存在");
        if (!STATUS_SUCCEEDED.equals(backup.getStatus()) || backup.getObjectKey() == null) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "只能回滚到已成功且文件仍在的备份");
        }
        try {
            DatabaseDumpService.SchemaState current = dump.currentSchemaState();
            if (backup.getSchemaVersion() != null && !backup.getSchemaVersion().equals(current.version())) {
                throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT,
                        "该备份对应的数据库结构版本（%s）与当前版本（%s）不一致，无法回滚"
                                .formatted(backup.getSchemaVersion(), current.version()));
            }
        } catch (java.sql.SQLException failure) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法读取当前数据库结构版本");
        }
        return backup;
    }

    private void requireIdle() {
        if (hasRunningOperation()) {
            throw new BusinessException(ErrorCode.RESOURCE_STATE_CONFLICT, "已有数据库备份或回滚任务正在执行");
        }
    }

    private boolean hasRunningOperation() {
        LocalDateTime stale = LocalDateTime.now().minus(STALE_RUNNING);
        Long runningBackups = backups.selectCount(Wrappers.<DatabaseBackupEntity>lambdaQuery()
                .eq(DatabaseBackupEntity::getStatus, STATUS_RUNNING)
                .gt(DatabaseBackupEntity::getStartedAt, stale));
        Long runningRestores = restores.selectCount(Wrappers.<DatabaseRestoreEntity>lambdaQuery()
                .eq(DatabaseRestoreEntity::getStatus, STATUS_RUNNING)
                .gt(DatabaseRestoreEntity::getStartedAt, stale));
        return runningBackups > 0 || runningRestores > 0;
    }

    private void fail(String backupId, String reason) {
        DatabaseBackupEntity backup = backups.selectById(backupId);
        if (backup == null) return;
        backup.setStatus(STATUS_FAILED);
        backup.setErrorMessage(truncate(reason));
        backup.setFinishedAt(LocalDateTime.now());
        backups.updateById(backup);
    }

    private void failRestore(String restoreId, String reason) {
        DatabaseRestoreEntity restore = restores.selectById(restoreId);
        if (restore == null) return;
        restore.setStatus(STATUS_FAILED);
        restore.setErrorMessage(truncate(reason));
        restore.setFinishedAt(LocalDateTime.now());
        restores.updateById(restore);
    }

    private void alert(String type, String message, String resourceId) {
        try {
            AdminAlertEntity alert = new AdminAlertEntity();
            alert.setType(type);
            alert.setMessage(truncate(message));
            alert.setResourceType("DATABASE_BACKUP");
            alert.setResourceId(resourceId);
            alert.setResolved(false);
            alert.setCreatedAt(LocalDateTime.now());
            alerts.insert(alert);
        } catch (Exception failure) {
            log.warn("数据库备份告警写入失败: {}", type, failure);
        }
    }

    private void requireAdmin(AuthenticatedUser user) {
        if (user == null || !user.isAdministrator()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅系统管理员可以管理数据库备份");
        }
    }

    private String objectKey(String backupId) {
        LocalDateTime now = LocalDateTime.now();
        return "backups/%04d/%02d/%s.jsonl.gz".formatted(now.getYear(), now.getMonthValue(), backupId);
    }

    private String message(Exception failure) {
        String text = failure.getMessage();
        return text == null || text.isBlank() ? failure.getClass().getSimpleName() : text;
    }

    private String truncate(String text) {
        if (text == null) return null;
        return text.length() <= 1000 ? text : text.substring(0, 1000);
    }

    private BackupView toView(DatabaseBackupEntity backup) {
        return toView(backup, currentSchemaVersion());
    }

    private BackupView toView(DatabaseBackupEntity backup, String currentSchemaVersion) {
        boolean downloadable = STATUS_SUCCEEDED.equals(backup.getStatus()) && backup.getObjectKey() != null;
        // 结构版本不一致的备份不能回滚：备份里只有数据，写回旧数据需要当时的表结构。
        boolean restorable = downloadable && currentSchemaVersion != null
                && currentSchemaVersion.equals(backup.getSchemaVersion());
        return new BackupView(backup.getId(), backup.getType(), backup.getStatus(), backup.getSizeBytes(),
                backup.getSha256(), backup.getTableCount(), backup.getRowCount(), backup.getSchemaVersion(),
                backup.getErrorMessage(), backup.getSourceFileName(), backup.getCreatedBy(),
                backup.getCreatedByName(), backup.getStartedAt(), backup.getFinishedAt(),
                downloadable, restorable);
    }

    private String currentSchemaVersion() {
        try {
            return dump.currentSchemaState().version();
        } catch (java.sql.SQLException failure) {
            log.warn("读取当前数据库结构版本失败", failure);
            return null;
        }
    }

    private RestoreView toView(DatabaseRestoreEntity restore) {
        return new RestoreView(restore.getId(), restore.getBackupId(), restore.getSafetyBackupId(),
                restore.getStatus(), restore.getTableCount(), restore.getRowCount(),
                restore.getErrorMessage(), restore.getCreatedBy(), restore.getCreatedByName(),
                restore.getStartedAt(), restore.getFinishedAt());
    }
}
