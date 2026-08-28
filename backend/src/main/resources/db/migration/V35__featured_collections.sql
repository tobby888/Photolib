-- 好图精选：部长发布征集要求，被指派的校区负责人按图片填写拍摄思路与地点，
-- 截止后服务端生成一份按校区分章节的 Word 文档。
--
-- 与数据库备份/回滚的关系：这三张表没有出现在 DatabaseDumpService.EXCLUDED_TABLES 里，
-- 因此会被 listTables() 自动发现，随整库一起备份和回滚，不需要改备份代码。
-- 但生成的 .docx 存在对象存储里，回滚不会同步对象：回滚到"文档生成之前"会留下孤儿对象，
-- 回滚到"文档生成之后"则 document_object_key 仍指向真实存在的对象。两者都不会损坏数据，
-- 必要时用 POST /featured-collections/{id}/document 重新生成。
CREATE TABLE featured_collection (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(200) NOT NULL,
    -- 部长用富文本编辑器写的征集要求。入库前已由服务端 jsoup 清洗，
    -- img 只允许 /api/v1/description-images/{26 位 id} 这一种来源。
    requirement_html TEXT NULL,
    -- 同一份要求的纯文本投影，用于列表摘要、关键词检索和 Word 文档正文。
    requirement_text TEXT NULL,
    starts_at DATETIME(6) NOT NULL,
    ends_at DATETIME(6) NOT NULL,
    -- DRAFT / PUBLISHED / CLOSED
    status VARCHAR(32) NOT NULL,
    -- TRUE 表示"所有校区负责人"，此时忽略 featured_collection_assignment 里的行。
    assign_all BOOLEAN NOT NULL DEFAULT TRUE,
    -- 每位负责人最多可提交的图片数，限制 Word 文档体积。
    entry_limit INT NOT NULL DEFAULT 10,
    -- PENDING（尚未截止）/ GENERATING / READY / FAILED
    document_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    document_object_key VARCHAR(512) NULL,
    document_size BIGINT NULL,
    document_generated_at DATETIME(6) NULL,
    document_error VARCHAR(1000) NULL,
    created_by BIGINT NOT NULL,
    published_by BIGINT NULL,
    published_at DATETIME(6) NULL,
    closed_by BIGINT NULL,
    closed_at DATETIME(6) NULL,
    -- MANUAL（部长手动截止）/ DEADLINE（到达截止时间由定时任务关闭）
    closed_reason VARCHAR(16) NULL,
    version INT NOT NULL DEFAULT 1,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_featured_collection_creator FOREIGN KEY (created_by) REFERENCES app_user(id),
    CONSTRAINT fk_featured_collection_publisher FOREIGN KEY (published_by) REFERENCES app_user(id),
    CONSTRAINT fk_featured_collection_closer FOREIGN KEY (closed_by) REFERENCES app_user(id),
    -- 定时任务按 (status, ends_at) 找"已发布且已过截止时间"的精选。
    INDEX idx_featured_collection_deadline (status, ends_at, deleted),
    INDEX idx_featured_collection_created (created_at, deleted)
);

-- 指派对象。每行恰好一个非空目标：campus_id 表示"该校区的全部负责人"，
-- user_id 表示单独点名的某位负责人。两个唯一键各自去重；
-- 校区行的 user_id 恒为 NULL，个人行的 campus_id 恒为 NULL，
-- 因此 MySQL"NULL 互不相等"的语义不会让任何一类出现重复。
CREATE TABLE featured_collection_assignment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    collection_id BIGINT NOT NULL,
    campus_id BIGINT NULL,
    user_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_featured_assignment_campus UNIQUE (collection_id, campus_id),
    CONSTRAINT uk_featured_assignment_user UNIQUE (collection_id, user_id),
    CONSTRAINT ck_featured_assignment_target CHECK (
        (campus_id IS NULL) <> (user_id IS NULL)),
    CONSTRAINT fk_featured_assignment_collection FOREIGN KEY (collection_id)
        REFERENCES featured_collection(id),
    CONSTRAINT fk_featured_assignment_campus FOREIGN KEY (campus_id) REFERENCES campus(id),
    CONSTRAINT fk_featured_assignment_user FOREIGN KEY (user_id) REFERENCES app_user(id)
);

-- 一条精选条目 = 一张图库图片 + 负责人手填的拍摄思路和地点。
-- 拍摄人与拍摄时间不接受输入，提交时从图库记录快照过来（与工时、照片一致的快照策略：
-- 之后图片被改名、被软删或通讯录成员被删除，都不改写已成文的精选内容）。
--
-- 这张表刻意没有 deleted 列：条目在截止前完全由填报人自己增删，没有留痕价值，
-- 物理删除才能让 (collection_id, photo_id) 这个唯一键在删掉后允许重新加入同一张图。
CREATE TABLE featured_entry (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    collection_id BIGINT NOT NULL,
    photo_id BIGINT NOT NULL,
    -- 图片所属校区，决定它进 Word 文档的哪一章；不是提交人的校区，
    -- 因为一位负责人可能被授权多个校区。
    campus_id BIGINT NULL,
    submitted_by BIGINT NOT NULL,
    idea VARCHAR(2000) NOT NULL,
    location VARCHAR(200) NOT NULL,
    photographer_name VARCHAR(100) NULL,
    photographer_student_id VARCHAR(64) NULL,
    taken_at DATETIME(6) NULL,
    photo_title VARCHAR(200) NULL,
    sort_order INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_featured_entry_photo UNIQUE (collection_id, photo_id),
    CONSTRAINT fk_featured_entry_collection FOREIGN KEY (collection_id)
        REFERENCES featured_collection(id),
    CONSTRAINT fk_featured_entry_photo FOREIGN KEY (photo_id) REFERENCES photo(id),
    CONSTRAINT fk_featured_entry_campus FOREIGN KEY (campus_id) REFERENCES campus(id),
    CONSTRAINT fk_featured_entry_submitter FOREIGN KEY (submitted_by) REFERENCES app_user(id),
    INDEX idx_featured_entry_collection (collection_id, campus_id, sort_order, id),
    INDEX idx_featured_entry_submitter (collection_id, submitted_by)
);

-- 好图精选的查看和下载不设限，所以没有 FEATURED_VIEW 之类的权限码。
-- 需要授权的只有"发布、删除、手动截止"这一组部长动作，默认给管理员和部长。
INSERT INTO permission_group_permission(group_id, permission_code)
SELECT id, 'FEATURED_MANAGE' FROM permission_group
WHERE code IN ('ADMIN', 'MINISTER');
