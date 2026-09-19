-- MCP 客户端的"在浏览器里登录"配对记录。
--
-- MCP 服务跑在成员自己的电脑上（Claude Desktop、Claude Code 之类的宿主里），
-- 那里没有浏览器会话，也不该让成员把账号密码写进宿主的配置文件——那份配置
-- 会被同步、会被截图、会被贴进群里。所以走的是设备码模式：客户端先在这里开一条
-- 待批准的记录，把 `request_id` 拼成站内地址让成员在浏览器里打开，成员用已经
-- 登录的会话批准之后，客户端再拿 `device_code` 把令牌换走。
--
-- 三个值各有分工，不能合并：
--   * `request_id`  公开标识，出现在浏览器地址栏里，谁都可能看到；
--   * `device_code` 客户端私有的凭据，只按 SHA-256 存哈希，换令牌时验它——
--                   光有地址栏里那串东西换不走任何令牌；
--   * `user_code`   终端里打印给人看的配对码，**同样只存哈希**。批准页要求成员
--                   手敲一遍，为的是挡住"把配对链接发给别人、由别人替自己批准"
--                   这条路：攻击者能拿到自己那条链接，但拿不到受害者终端上的码，
--                   而受害者手里没有码就点不动批准按钮。存哈希是因为批准页
--                   自己也不该知道正确答案，否则"手敲一遍"就退化成了走过场。
--
-- `failed_attempts` 兜住对配对码的暴力猜测：码只有 8 位，猜满 5 次这条记录直接
-- 作废（`McpAuthorizationService.MAX_FAILED_ATTEMPTS`），成员重新发起配对即可。
CREATE TABLE mcp_auth_request (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id CHAR(43) NOT NULL,
    device_code_hash CHAR(64) NOT NULL,
    user_code_hash CHAR(64) NOT NULL,
    client_name VARCHAR(100) NOT NULL,
    device_label VARCHAR(100) NULL,
    requested_ip VARCHAR(64) NULL,
    status VARCHAR(16) NOT NULL,
    user_id BIGINT NULL,
    failed_attempts INT NOT NULL DEFAULT 0,
    approved_at DATETIME(6) NULL,
    consumed_at DATETIME(6) NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_mcp_auth_request UNIQUE (request_id),
    CONSTRAINT fk_mcp_auth_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    -- 定时清理按过期时间扫，批准页和轮询按 request_id 查；两条路各一个索引。
    INDEX idx_mcp_auth_expires (expires_at)
);
