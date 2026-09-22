-- 两步验证（2FA）。见 AGENTS.md §2.31。
--
-- 全站开关只有一行：关着的时候整套机制不生效，已绑定的设备原样保留，
-- 重新打开后不必让所有人再绑一遍。
CREATE TABLE mfa_setting (
    id INT PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    updated_by BIGINT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
INSERT INTO mfa_setting(id, enabled) VALUES (1, FALSE);

-- 按权限组决定要不要两步验证：OFF 不使用、SUGGESTED 建议、REQUIRED 强制。
-- 系统管理员组固定为 REQUIRED（代码里也会兜底），其余存量组默认不使用。
ALTER TABLE permission_group ADD COLUMN mfa_policy VARCHAR(16) NOT NULL DEFAULT 'OFF';
UPDATE permission_group SET mfa_policy = 'REQUIRED' WHERE code = 'ADMIN';

-- 验证设备：验证器 App（TOTP）或安全密钥 / 通行密钥（WebAuthn）。
-- TOTP 密钥必须能还原才能算码，所以存的是 AES-GCM 密文而不是哈希；
-- confirmed_at 为空表示刚生成、还没用一次正确的验证码确认过，不算已绑定。
CREATE TABLE mfa_device (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    type VARCHAR(16) NOT NULL,
    name VARCHAR(64) NOT NULL,
    totp_secret_cipher VARCHAR(255) NULL,
    last_totp_step BIGINT NULL,
    credential_id VARCHAR(512) NULL,
    credential_data BLOB NULL,
    sign_count BIGINT NULL,
    transports VARCHAR(255) NULL,
    confirmed_at DATETIME(6) NULL,
    last_used_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_mfa_device_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT uk_mfa_device_credential UNIQUE (credential_id),
    INDEX idx_mfa_device_user (user_id, confirmed_at)
);

-- 信任的浏览器。浏览器里只存一个随机令牌（HttpOnly Cookie），库里只存它的哈希。
-- expires_at 每次凭它登录都顺延，30 天没用过就过期并被定时任务清掉。
CREATE TABLE mfa_trusted_device (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL,
    label VARCHAR(255) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_used_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_mfa_trusted_device_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT uk_mfa_trusted_device_token UNIQUE (token_hash),
    INDEX idx_mfa_trusted_device_user (user_id),
    INDEX idx_mfa_trusted_device_expires (expires_at)
);

-- 一次性的验证票据：LOGIN（密码对了、等第二步）、REGISTER（绑定安全密钥）、
-- STEP_UP（敏感操作前再验一次）。WebAuthn 的挑战值也挂在票据上。
-- 状态迁移一律用带条件的 UPDATE，票据只能被消费一次。
CREATE TABLE mfa_challenge (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    purpose VARCHAR(16) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    session_id BIGINT NULL,
    webauthn_challenge VARCHAR(128) NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_mfa_challenge_token UNIQUE (token_hash),
    INDEX idx_mfa_challenge_expires (expires_at)
);

-- 敏感操作（删除图片 / 选题 / 需求、系统管理面板）验证过一次后的 15 分钟信任期。
-- 挂在会话上：续期换新会话时会把它带过去，登出或会话失效时一并作废。
ALTER TABLE auth_session ADD COLUMN step_up_until DATETIME(6) NULL;

-- 会话是否通过过第二因素：登录第二步、信任的浏览器、在本会话里绑定设备或完成再验证都会置真。
-- 两步验证对账号生效后，没通过过的会话（开关打开前、策略收紧前、绑定前签发的）
-- 在下一次请求和续期时一律作废，必须重新登录；续期换会话时带过去。
ALTER TABLE auth_session ADD COLUMN mfa_verified BOOLEAN NOT NULL DEFAULT FALSE;
