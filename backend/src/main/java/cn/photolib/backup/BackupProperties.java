package cn.photolib.backup;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * @param enabled                是否执行每日自动备份。关闭后管理员仍可手动备份和回滚。
 * @param cron                   自动备份的调度表达式，由 {@link DatabaseBackupJob} 直接读取占位符。
 * @param retention              自动清理超过该时长的备份对象。
 * @param minimumRetained        无论保留期如何都至少保留的成功备份数量，避免长时间无人使用后一个不剩。
 * @param maxUploadBytes         管理员上传备份文件的压缩后体积上限。
 * @param maxDecompressedBytes   上传文件解压后的体积上限，用于挡住压缩炸弹。
 */
@ConfigurationProperties(prefix = "photolib.backup")
public record BackupProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("0 0 0 * * *") String cron,
        @DefaultValue("30d") Duration retention,
        @DefaultValue("7") int minimumRetained,
        @DefaultValue("512MB") DataSize maxUploadBytes,
        @DefaultValue("2GB") DataSize maxDecompressedBytes
) {
}
