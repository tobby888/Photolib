-- 选题「仅接收公共照片」。
--
-- 公共照片不是图片上的一列，而是一个固定的拍摄者：photographer_name = '公共照片'、学号为空串
-- （见 cn.photolib.photo.PublicPhoto）。这样在图库按拍摄者筛选时它就是一个普通的作者，
-- 不需要另开一套筛选条件；被引、工时都按这个拍摄者判定。
--
-- 选题打开这个开关后，传进来的图（需求交付、选题相册直传、批量上传、上传链接、从图库添加 /
-- 复制 / 移动进来）一律必须是公共照片。改开关不回写已在相册里的图。
-- 给默认值 FALSE：存量选题照旧收任何拍摄者的图。
ALTER TABLE project ADD COLUMN public_photos_only BOOLEAN NOT NULL DEFAULT FALSE;
