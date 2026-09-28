-- 上传限额收归管理员：单文件大小、压缩包大小、压缩包内图片张数等都在「系统管理 → 上传限额」里改。
--
-- 只存管理员改过的项。没有行的项沿用程序内置的默认值（其中图库单张上限、招募上传额度、
-- 数据库备份上传上限的默认值仍取自配置文件，升级上来的部署行为不变）。
-- 每一项的可调范围（上下限）写在程序里（UploadLimit），不在库里：上限对应的是解码内存、
-- 对象存储单次 PUT、multipart 总上限这类系统能力，不能让一条数据库记录越过去。
CREATE TABLE upload_limit_setting (
    limit_key VARCHAR(64) PRIMARY KEY,
    limit_value BIGINT NOT NULL,
    updated_by BIGINT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_upload_limit_value_positive CHECK (limit_value > 0)
);
