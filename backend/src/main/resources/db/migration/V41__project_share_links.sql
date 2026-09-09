-- 选题项目的对外分享链接：部长/管理员生成"链接 + 密码"，未登录的外部用户
-- 凭这两样看项目相册，并按链接上的开关获得下载和标记被引两项能力。
--
-- 设计上只有链接是新数据，**图片和被引状态一律现算**：
--   图片   = photo_project 的成员关系（和项目详情页同一个来源）
--   被引   = adoption 表里该项目的记录（和站内标记同一张表）
-- 因此同一个项目的多条分享链接之间天然同步，外部用户标的被引也就是项目的被引。
-- 千万不要为分享链接复制一份图片列表或被引快照——那等于给同一件事造第二份真相，
-- 而漂移的方向恰好是"外部用户看到的和站内看到的不一样"。
CREATE TABLE project_share_link (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    -- 对外地址里的那一段。用 26 位随机 public_id 而不是自增 id：链接会被转发出去，
    -- 顺序 id 既能被枚举，也会暴露分享总数。
    token CHAR(26) NOT NULL,
    project_id BIGINT NOT NULL,
    -- 给创建者自己看的备注，例如"校报编辑部"。允许为空。
    name VARCHAR(100) NULL,
    -- BCrypt 哈希。明文只在创建和重置密码时返回一次，数据库里不留明文——
    -- 代价是事后无法"再看一眼密码"，只能重置，这是刻意的取舍。
    password_hash VARCHAR(200) NOT NULL,
    -- 两项授予外部用户的能力，创建时决定，之后可随时改（改完立即生效，
    -- 因为每次请求都会重新读这一行，见 ProjectShareService.resolveGuest）。
    allow_download BOOLEAN NOT NULL DEFAULT FALSE,
    allow_adoption BOOLEAN NOT NULL DEFAULT FALSE,
    -- NULL 表示长期有效。
    expires_at DATETIME(6) NULL,
    view_count BIGINT NOT NULL DEFAULT 0,
    last_viewed_at DATETIME(6) NULL,
    created_by BIGINT NOT NULL,
    version INT NOT NULL DEFAULT 1,
    -- 软删除即"撤销分享"：置位之后所有已发出的会话下一次请求就被拒。
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_project_share_link_token UNIQUE (token),
    CONSTRAINT fk_project_share_link_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_project_share_link_creator FOREIGN KEY (created_by) REFERENCES app_user(id),
    INDEX idx_project_share_link_project (project_id, deleted)
);

-- 通过密码校验后发给浏览器的会话。存哈希而不是明文，理由与登录会话相同：
-- 数据库被读走时它不该等于一把可直接使用的钥匙。
--
-- 会话只证明"这个人过了这条链接的密码"，**不缓存任何权限**：下载/被引开关、
-- 链接是否被删、是否过期，全部在每次请求里重新按 link_id 读一遍。
-- 把开关抄进会话会让"改权限"变成"等会话过期才生效"。
CREATE TABLE project_share_session (
    id CHAR(26) PRIMARY KEY,
    link_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_project_share_session_link FOREIGN KEY (link_id) REFERENCES project_share_link(id)
        ON DELETE CASCADE,
    INDEX idx_project_share_session_expiry (expires_at)
);

-- 外部用户的批量下载复用 export_job（同一套打包、同一套对象存储、同一套过期）。
-- 任务的 created_by 记的是链接创建者（那是唯一可追责的成员），share_link_id 才是
-- "这个任务属于哪条分享链接"的凭据——匿名访客查任务状态时必须靠它判定归属，
-- 否则拿到一个任务 id 就能读到别人的导出包。
ALTER TABLE export_job ADD COLUMN share_link_id BIGINT NULL;
ALTER TABLE export_job ADD CONSTRAINT fk_export_job_share_link
    FOREIGN KEY (share_link_id) REFERENCES project_share_link(id);

INSERT INTO permission_group_permission(group_id, permission_code)
SELECT id, 'PROJECT_SHARE' FROM permission_group
WHERE code IN ('ADMIN', 'MINISTER');
