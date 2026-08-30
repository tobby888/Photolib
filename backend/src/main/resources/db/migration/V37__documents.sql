-- 文档中心：管理员和部长写文档，读者在登录页前面就能看到目录。
--
-- 结构上是一棵 Obsidian 式的树：FOLDER 只是容器，DOCUMENT 才有正文。
-- 正文不进数据库，和图片一起放对象存储：
--   docs/{public_id}/content.md   —— Markdown 正文，UTF-8
--   docs/assets/{asset_id}.{ext}  —— 正文里引用的图片
-- 数据库只留元数据和 object_key。这样做的代价必须记住：**数据库备份/回滚不同步对象**。
-- 回滚到"写正文之前"会留下孤儿对象；回滚到"写正文之后"则 object_key 仍指向真实对象。
-- 两者都不会让页面报错——DocService 读不到对象时按空正文渲染，不抛 500。
--
-- 可见性是两个正交的开关，都只对 DOCUMENT 有意义，判定时必须同时满足：
--   published   —— 草稿还是已发布。未发布的文档只有 DOC_MANAGE 能看到。
--   visibility  —— PUBLIC（未登录访客也能读）还是 MEMBERS（必须登录）。
-- 文件夹两个开关都没有：一个文件夹是否出现在某个读者的目录里，
-- 取决于它的子树里有没有对这个读者可见的文档（见 DocService.buildReader）。
-- 让文件夹也带开关的话，"文档发布了但祖先文件夹忘了发布"会变成一类无声故障，
-- 作者在页面上找不到自己刚发布的文档，却看不出原因。
CREATE TABLE doc_node (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    -- 对外地址用 public_id 而不是自增 id：公开页面的链接会被分享出去，
    -- 不希望顺序 id 暴露文档总数，也不希望删掉再建的文档复用旧链接。
    public_id CHAR(26) NOT NULL,
    parent_id BIGINT NULL,
    -- FOLDER / DOCUMENT
    node_type VARCHAR(16) NOT NULL,
    title VARCHAR(200) NOT NULL,
    -- 同级排序。拖拽后由服务端把整组兄弟重排成 0..n-1，不做稀疏间隔，
    -- 树规模很小（上限见 DocService.MAX_NODES），整组重写比维护间隔更不容易出错。
    sort_order INT NOT NULL DEFAULT 0,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    -- PUBLIC（登录页前面就能读）/ MEMBERS（必须登录）。默认值刻意是 MEMBERS：
    -- 新建的文档要经过一次显式的"设为公开"才会对匿名访客可见，
    -- 忘记设置的后果是少给人看，而不是把内部资料放到公网上。
    visibility VARCHAR(16) NOT NULL DEFAULT 'MEMBERS',
    object_key VARCHAR(512) NULL,
    content_size BIGINT NULL,
    -- 正文纯文本投影的前若干字，用于公开树的摘要，避免为了列表去对象存储拉全文。
    summary VARCHAR(500) NULL,
    created_by BIGINT NOT NULL,
    updated_by BIGINT NULL,
    published_at DATETIME(6) NULL,
    version INT NOT NULL DEFAULT 1,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_doc_node_public_id UNIQUE (public_id),
    CONSTRAINT ck_doc_node_content CHECK (node_type <> 'FOLDER' OR object_key IS NULL),
    CONSTRAINT fk_doc_node_parent FOREIGN KEY (parent_id) REFERENCES doc_node(id),
    CONSTRAINT fk_doc_node_creator FOREIGN KEY (created_by) REFERENCES app_user(id),
    CONSTRAINT fk_doc_node_updater FOREIGN KEY (updated_by) REFERENCES app_user(id),
    INDEX idx_doc_node_parent (parent_id, sort_order, id),
    INDEX idx_doc_node_visible (deleted, published, visibility)
);

-- 正文里插入的图片。刻意不复用 description_image：那张表的读接口一律要求登录，
-- 而文档图片的可见范围要跟着所属文档走（可能对匿名访客开放），授权模型不同。
--
-- node_id 非空是有意的：图片的可见性完全跟随所属文档的 published + visibility，
-- 没有归属的图片就没有判定依据，只能一律拒绝，那等于上传了个死链。
-- 因此上传接口是 POST /docs/{id}/assets——先有文档，再有图片。
-- 这条不变量是安全边界：MEMBERS 文档里的插图必须和它的正文一样需要登录，
-- 否则把图片直链发出去就绕过了登录要求。
CREATE TABLE doc_asset (
    id CHAR(26) PRIMARY KEY,
    node_id BIGINT NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size BIGINT NOT NULL,
    uploaded_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_doc_asset_node FOREIGN KEY (node_id) REFERENCES doc_node(id),
    CONSTRAINT fk_doc_asset_uploader FOREIGN KEY (uploaded_by) REFERENCES app_user(id),
    INDEX idx_doc_asset_node (node_id)
);

-- 读文档不需要任何权限码，所以只有"写"这一组动作需要授权：新建、改名、写正文、
-- 拖拽移动、发布、改可见性、删除。默认给管理员和部长，
-- 与需求里"由部长和管理员负责编写文档、并指定哪些文档需要登录"一致。
INSERT INTO permission_group_permission(group_id, permission_code)
SELECT id, 'DOC_MANAGE' FROM permission_group
WHERE code IN ('ADMIN', 'MINISTER');
