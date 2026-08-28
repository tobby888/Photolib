-- 权限组的图库可见范围：SELF（仅本人上传）/ CAMPUS（授权校区内全部）/ GLOBAL（全站全部）。
-- 与 data_scope 正交：data_scope 管校区授权本身，这一列管"看得到的图片属于谁"。
ALTER TABLE permission_group
    ADD COLUMN photo_visibility VARCHAR(16) NOT NULL DEFAULT 'SELF' AFTER data_scope;

-- 存量权限组必须保持升级前的行为：全局数据范围的账号本来就能看全站图片，
-- 校区范围（以及 NO_ACCESS）的账号本来只能看自己上传的图片。
UPDATE permission_group SET photo_visibility = 'GLOBAL' WHERE data_scope = 'GLOBAL';
