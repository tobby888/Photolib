-- 上传链接的 ZIP 批量上传。
--
-- 活动当天一个人手里常常是几百张，逐张传既慢又容易传一半就关掉页面。站内早就有
-- 一条 ZIP 通道（`photo_upload_batch` / `photo_upload_item` + `SafeImageZipExtractor`，
-- V2 起），这里把上传链接接到**同一条**通道上，限额也一律沿用站内那一套
-- （`ImageUploadPolicy`：ZIP ≤ 1.5 GB、包内 ≤ 100 张、单张 ≤ 100 MiB、解压总量 ≤ 10 GiB）。
-- 不给站外单独开一套阈值：两套数字迟早会各改各的，而松的那一套就是被用来打进来的那一套。
--
-- 唯一需要的新数据是"这个批次是哪条上传链接开的"。与 `photo.share_link_id`
-- （V48）和 `export_job.share_link_id`（V41）同一个用途：批次的 `created_by` 记的是
-- 链接创建者，对访客不是凭据，访客能不能碰这个批次只看这一列。
ALTER TABLE photo_upload_batch ADD COLUMN share_link_id BIGINT NULL;
ALTER TABLE photo_upload_batch ADD CONSTRAINT fk_batch_share_link
    FOREIGN KEY (share_link_id) REFERENCES project_share_link(id);
CREATE INDEX idx_batch_share_link ON photo_upload_batch(share_link_id);
