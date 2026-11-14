-- 老票据没有可信的会话归属，保留 NULL。会话过期后可以清理，照片仍需保留。
ALTER TABLE photo ADD COLUMN share_upload_session_id CHAR(26) NULL;
