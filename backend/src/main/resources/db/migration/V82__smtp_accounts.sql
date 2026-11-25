-- 后台动态配置的多个 SMTP 发信邮箱。
-- 发信时按 sort_order 升序依次尝试，前一个失败就顺延到下一个；全部失败时把每个邮箱的原因合并返回。
-- from_address 允许与 username 分开配置（部分邮件服务商的 SMTP 登录名不是发件邮箱地址）。
-- password_secret 在配置了 SMTP_ENCRYPTION_KEY（或沿用 MFA_ENCRYPTION_KEY）时存 AES-GCM 密文（v1:...），
-- 未配置加密密钥时存明文标记（plain:...）。

CREATE TABLE smtp_account (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    host VARCHAR(255) NOT NULL,
    port INT NOT NULL DEFAULT 465,
    username VARCHAR(255) NOT NULL,
    password_secret VARCHAR(2048) NOT NULL,
    from_address VARCHAR(255) NULL,
    from_name VARCHAR(100) NULL,
    -- SSL / STARTTLS / NONE
    security VARCHAR(16) NOT NULL DEFAULT 'SSL',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL DEFAULT 0,
    last_success_at DATETIME(6) NULL,
    last_failure_at DATETIME(6) NULL,
    last_error VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL
);

CREATE INDEX idx_smtp_account_order ON smtp_account(enabled, sort_order, id);
