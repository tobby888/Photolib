-- 管理员可以把下载下来的备份文件重新上传回系统再回滚，记录下原始文件名，
-- 方便在列表里区分"哪一份是导入进来的、来自哪个文件"。
ALTER TABLE database_backup ADD COLUMN source_file_name VARCHAR(255) NULL;
