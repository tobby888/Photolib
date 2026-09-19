-- 清理"传了一半就走"留下的临时对象。
--
-- 在此之前没有任何东西删它们：`OriginalCleanupJob` 只删 `original_delete_after`
-- 到期的原图，而那一列**只在处理成功时才写**（PhotoProcessingService.completeProcessing）；
-- `PhotoStorageReconciliationService` 只对 AVAILABLE/ARCHIVED 的照片做对账，不看
-- `temporary/`。于是签了票据却没 complete 的单张、传完却没 complete 的 ZIP、以及
-- 解包出来却没被整理的条目，全都在对象存储和本地盘上无限期地堆着。
--
-- 记下**预签名地址什么时候过期**，是因为"早删没用"：对象删掉之后，客户端手里
-- 那条还没过期的 PUT 地址可以把它原样再传回来，而那时库里已经没有任何一行指向
-- 它了——一个谁都不知道的孤儿。招募那条上传链路在 V28 踩过同一个坑并加了同名的
-- 列，这里照搬，两处的清理任务因此是同一个形状。
ALTER TABLE photo ADD COLUMN upload_url_expires_at DATETIME(6) NULL;
ALTER TABLE photo_upload_batch ADD COLUMN upload_url_expires_at DATETIME(6) NULL;
ALTER TABLE photo_upload_item ADD COLUMN upload_url_expires_at DATETIME(6) NULL;

-- 存量行的预签名地址不可能还有效（迁移执行时它们早就过了 TTL），直接标成"到期
-- 时间＝建立时间"，让清理任务第一轮就能把改动之前留下的那些一并收走。
UPDATE photo SET upload_url_expires_at = created_at
WHERE status = 'UPLOADING' AND original_object_key IS NOT NULL
  AND upload_url_expires_at IS NULL;

UPDATE photo_upload_batch SET upload_url_expires_at = created_at
WHERE status = 'UPLOADING' AND archive_object_key IS NOT NULL
  AND upload_url_expires_at IS NULL;

UPDATE photo_upload_item SET upload_url_expires_at = created_at
WHERE status = 'UPLOADING' AND temp_object_key IS NOT NULL
  AND upload_url_expires_at IS NULL;

-- 三条清理查询各自的驱动索引。photo 那张表最大，所以把 status 放在最前面：
-- 待清理的行只可能是 UPLOADING，而 UPLOADING 在任何时刻都只有极少数。
CREATE INDEX idx_photo_abandoned_upload ON photo(status, upload_url_expires_at);
CREATE INDEX idx_batch_abandoned_upload ON photo_upload_batch(status, upload_url_expires_at);
CREATE INDEX idx_batch_item_abandoned_upload ON photo_upload_item(status, upload_url_expires_at);
