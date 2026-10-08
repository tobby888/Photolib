-- 文档中心的细粒度读者 + 文件库 + 拆细的权限码。
--
-- 一、读者范围（resource_access_grant）
--
-- 文档原来只有两档：PUBLIC（未登录也能读）/ MEMBERS（登录即可读）。新增第三档 RESTRICTED：
-- 必须登录，并且「属于其中一个指定权限组」或「是其中一位指定用户」。文件库的下载范围
-- 用的是同一套三档和同一张授权表，判定代码也是同一份（cn.photolib.doc.DocAudience）。
--
-- 授权表刻意不带外键：resource_type 是多态的（DOC / FILE），外键表达不了；而权限组是
-- 物理删除的，挂外键会让"删除权限组"被一条读者授权卡住。删除权限组时由
-- PermissionGroupService.delete 一并删掉指向它的授权——授权消失的方向是"少给人看"，
-- 这正是删组时该有的结果。用户是软删除的，被删用户登录不了，指向它的授权自然失效。
CREATE TABLE resource_access_grant (
    resource_type VARCHAR(16) NOT NULL,
    resource_id BIGINT NOT NULL,
    -- GROUP（权限组 id）/ USER（用户 id）
    grantee_type VARCHAR(8) NOT NULL,
    grantee_id BIGINT NOT NULL,
    PRIMARY KEY (resource_type, resource_id, grantee_type, grantee_id),
    INDEX idx_resource_access_grantee (grantee_type, grantee_id)
);

-- 二、文件库（doc_file）
--
-- 有 FILE_UPLOAD 的人上传任意格式的文件，并指定谁能下载（三档读者范围，同上）。
-- 文件本身放对象存储 doc-files/{public_id}/{随机名}，统一以 application/octet-stream 存、
-- 以附件形式下载：本地存储的签名地址和站点同源，按上传者声明的类型（比如 text/html）
-- 原样回吐就成了一个存储型 XSS。数据库里的 content_type 只用于展示。
--
-- 删除是软删，对象保留（与文档中心一致：软删可撤销，回滚数据库后对象还在）。
-- 每人存储空间按未删除文件的 size 合计计算。
CREATE TABLE doc_file (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    public_id CHAR(26) NOT NULL,
    title VARCHAR(200) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(150) NOT NULL,
    size BIGINT NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    description VARCHAR(1000) NULL,
    -- PUBLIC / MEMBERS / RESTRICTED，默认 MEMBERS：忘记设置的后果应该是少给人看。
    visibility VARCHAR(16) NOT NULL DEFAULT 'MEMBERS',
    download_count BIGINT NOT NULL DEFAULT 0,
    uploaded_by BIGINT NOT NULL,
    updated_by BIGINT NULL,
    version INT NOT NULL DEFAULT 1,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_doc_file_public_id UNIQUE (public_id),
    CONSTRAINT ck_doc_file_size CHECK (size >= 0),
    CONSTRAINT fk_doc_file_uploader FOREIGN KEY (uploaded_by) REFERENCES app_user(id),
    CONSTRAINT fk_doc_file_updater FOREIGN KEY (updated_by) REFERENCES app_user(id),
    INDEX idx_doc_file_list (deleted, created_at, id),
    INDEX idx_doc_file_uploader (uploaded_by, deleted, created_at)
);

-- 下载用量按「业务日（Asia/Shanghai）× 读者类别」累计。匿名下载的每日总流量上限
-- （上传限额里的 FILE_ANONYMOUS_DAILY_BYTES）用条件 UPDATE 在这里扣减：
-- 不依赖客户端地址——反向代理后面所有匿名访客共用一个地址，按地址限流会失效，
-- 而这条全站总量不会。行存在库里而不是内存里，重启和多实例都绕不过去。
CREATE TABLE doc_file_download_usage (
    usage_date DATE NOT NULL,
    -- ANONYMOUS / MEMBER
    reader_scope VARCHAR(16) NOT NULL,
    bytes BIGINT NOT NULL DEFAULT 0,
    downloads BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (usage_date, reader_scope)
);

-- 三、拆细的权限码
--
-- 原则：沿用原来的权限码名字、把含义收窄，被拆出去的那部分是一条新权限码；
-- 凡是原来持有旧码的权限组都补上新码，所以升级后每个组能做的事和升级前完全一样，
-- 管理员之后可以按需把新码单独去掉。
--   PROJECT_CREATE  → 新建、编辑和发布选题      + PROJECT_DELETE  删除选题
--   PHOTO_DELETE    → 删除图库图片              + PHOTO_ARCHIVE   归档和恢复
--   DOC_MANAGE      → 编写文档、整理目录        + DOC_PUBLISH     发布与设置读者范围
--   TEACHING_MANAGE → 上传、替换和编辑教学资料  + TEACHING_DELETE 删除教学资料
-- 写法同 V42：目标表也出现在 SELECT 里，包一层派生表避开 MySQL 1093。
INSERT INTO permission_group_permission(group_id, permission_code)
SELECT holders.group_id, holders.split_code
FROM (
    SELECT DISTINCT p.group_id,
           CASE p.permission_code
               WHEN 'PROJECT_CREATE' THEN 'PROJECT_DELETE'
               WHEN 'PHOTO_DELETE' THEN 'PHOTO_ARCHIVE'
               WHEN 'DOC_MANAGE' THEN 'DOC_PUBLISH'
               WHEN 'TEACHING_MANAGE' THEN 'TEACHING_DELETE'
           END AS split_code
    FROM permission_group_permission p
    WHERE p.permission_code IN ('PROJECT_CREATE', 'PHOTO_DELETE', 'DOC_MANAGE', 'TEACHING_MANAGE')
) holders
WHERE NOT EXISTS (
    SELECT 1 FROM permission_group_permission existing
    WHERE existing.group_id = holders.group_id AND existing.permission_code = holders.split_code
);

-- 文件库两条新权限默认给管理员和部长，和文档中心的编写权限一致：
--   FILE_UPLOAD —— 上传文件，并管理（改读者范围、删除）自己上传的文件；
--   FILE_MANAGE —— 管理所有人上传的文件。
-- 下载不需要权限码：谁能下载由上传者给每个文件指定。
INSERT INTO permission_group_permission(group_id, permission_code)
SELECT g.id, codes.permission_code
FROM permission_group g
CROSS JOIN (
    SELECT 'FILE_UPLOAD' AS permission_code UNION ALL SELECT 'FILE_MANAGE'
) codes
WHERE g.code IN ('ADMIN', 'MINISTER')
  AND NOT EXISTS (
      SELECT 1 FROM permission_group_permission existing
      WHERE existing.group_id = g.id AND existing.permission_code = codes.permission_code
  );
