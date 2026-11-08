-- 动图（多帧 GIF、APNG）。
--
-- 动图的成品图就是原样保存的原文件，不做质量压缩；缩略图照常是静态首帧 WebP。这一列让前端
-- 知道哪些图要在网格里悬停播放、在详情页带暂停按钮播放，后端据此拒绝裁切编辑。
-- APNG 的 content_type 仍是 image/png，所以单看 content_type 不够。
--
-- 存量照片一律 FALSE：此前只收 JPG / PNG，存量里即使有 APNG 也只会显示成静态图，
-- 不影响使用；要补标需要逐张下载成品图检查，不在迁移里做。
ALTER TABLE photo ADD COLUMN animated BOOLEAN NOT NULL DEFAULT FALSE;
