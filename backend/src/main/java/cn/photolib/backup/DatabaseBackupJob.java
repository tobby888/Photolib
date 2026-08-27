package cn.photolib.backup;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每天凌晨 0 点（Asia/Shanghai）自动备份整库数据到对象存储。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseBackupJob {
    private final DatabaseBackupService backups;

    @Scheduled(cron = "${photolib.backup.cron:0 0 0 * * *}", zone = "Asia/Shanghai")
    public void backupNightly() {
        try {
            backups.runScheduledBackup();
        } catch (Exception failure) {
            // runScheduledBackup 自己会记录失败并写告警；这里只兜住调度线程，
            // 避免一次异常让后续每日备份不再触发。
            log.error("每日数据库备份任务异常终止", failure);
        }
    }
}
