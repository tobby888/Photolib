-- 停在 PROCESSING 的照片被 StalledProcessingRecoveryJob 重新提交过几次。
--
-- 必须落库而不能放在内存里：处理途中把 JVM 带走的往往就是这张图本身（原生组件
-- 段错误），重启之后内存里的计数没了，恢复任务会再提交一次、再崩一次，systemd
-- 就这样一直拉起、一直崩。到了上限就不再重提交，改成把照片标为处理失败。
-- 上传者重新 complete 时清零（PhotoService / ProjectShareUploadService）。
ALTER TABLE photo ADD COLUMN processing_recoveries INT NOT NULL DEFAULT 0;
