-- 企业微信自建应用取代邮件成为通知投递通道。
-- 邮箱字段保留：它同时是登录凭据（见 V15/V18），只是不再用于投递。

-- 用户与企微通讯录 userid 的绑定，由管理员维护。
-- 唯一索引允许多行 NULL，未绑定的账号不会互相冲突。
ALTER TABLE app_user ADD COLUMN wecom_userid VARCHAR(64) NULL;
CREATE UNIQUE INDEX uk_user_wecom_userid ON app_user (wecom_userid);

-- 投递日志多通道化：channel 说明这条记录走哪条通道，recipient 是该通道下的收件标识。
-- 历史行全部是邮件，默认值和回填保证旧数据的重试与展示不受影响。
ALTER TABLE notification_log ADD COLUMN channel VARCHAR(32) NOT NULL DEFAULT 'EMAIL';
ALTER TABLE notification_log ADD COLUMN recipient VARCHAR(255) NULL;
UPDATE notification_log SET recipient = email WHERE recipient IS NULL;
ALTER TABLE notification_log MODIFY COLUMN email VARCHAR(255) NULL;
