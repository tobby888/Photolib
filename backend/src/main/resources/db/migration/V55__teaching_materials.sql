-- 教学资料：为图库成员提供课程文件（首版仅 PDF）。
--
-- 与「文档中心」(doc_node / doc_asset) 的区别见 docs/adr/0001：文档中心是撰写型 Wiki
-- （Markdown 正文 + PDF，MEMBERS/PUBLIC 两态可见），教学资料是下载型文件库，
-- 受众是持有 PHOTO_VIEW 的图库成员，管理权限是 TEACHING_MANAGE。
--
-- 文件本体不进数据库，和图片一样放对象存储：
--   teaching/{public_id}/document.pdf
-- 数据库只留元数据与 object_key。
--
-- format 首版只会写 'PDF'；'WORD'/'PPT' 是预留值，让以后开放 Word/PPT 不用改表结构。
-- author_id 是「作者」（内容原作者，从图库成员中选，可选），created_by 是「上传人」
-- （把文件录入系统的管理者，服务端自动记），两者是不同概念。
CREATE TABLE teaching_material (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    -- 对外地址用 public_id 而不是自增 id：下载链接会被转发出去，不希望暴露资料总数。
    public_id CHAR(26) NOT NULL,
    title VARCHAR(200) NOT NULL,
    description VARCHAR(1000) NULL,
    category VARCHAR(100) NOT NULL,
    author_id BIGINT NULL,
    format VARCHAR(16) NOT NULL DEFAULT 'PDF',
    object_key VARCHAR(512) NOT NULL,
    content_size BIGINT NOT NULL,
    download_count BIGINT NOT NULL DEFAULT 0,
    created_by BIGINT NOT NULL,
    updated_by BIGINT NULL,
    version INT NOT NULL DEFAULT 1,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_teaching_material_public_id UNIQUE (public_id),
    CONSTRAINT fk_teaching_material_author FOREIGN KEY (author_id) REFERENCES app_user(id),
    CONSTRAINT fk_teaching_material_creator FOREIGN KEY (created_by) REFERENCES app_user(id),
    CONSTRAINT fk_teaching_material_updater FOREIGN KEY (updated_by) REFERENCES app_user(id),
    INDEX idx_teaching_material_category (deleted, category),
    INDEX idx_teaching_material_created (deleted, created_at)
);

-- 上传、替换、编辑、删除教学资料需要 TEACHING_MANAGE，默认给管理员和部长；
-- 校区负责人默认不给——教学资料是部门级资源，不是校区级资源。
INSERT INTO permission_group_permission(group_id, permission_code)
SELECT id, 'TEACHING_MANAGE' FROM permission_group
WHERE code IN ('ADMIN', 'MINISTER');
