-- 打包下载的实时进度：前端弹窗按这几列显示「正在打包 37 / 120 张」「正在生成下载文件 60%」。
-- phase：PACKING（从存储里逐张读出、写进 ZIP）/ UPLOADING（把 ZIP 存回对象存储）；排队时为空。
-- 只有图片打包任务会写这几列，统计和工时导出仍只用 status / progress。
ALTER TABLE export_job ADD COLUMN phase VARCHAR(32) NULL;
ALTER TABLE export_job ADD COLUMN total_items INT NULL;
ALTER TABLE export_job ADD COLUMN done_items INT NULL;
ALTER TABLE export_job ADD COLUMN total_bytes BIGINT NULL;
ALTER TABLE export_job ADD COLUMN done_bytes BIGINT NULL;

-- 看门狗按状态 + 最后更新时间找中断的任务。
CREATE INDEX idx_export_job_status_updated ON export_job(status, updated_at);
