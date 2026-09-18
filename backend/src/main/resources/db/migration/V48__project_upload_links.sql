-- 活动选题的上传链接。
--
-- 需求：活动选题除了「把相册分享出去」，还要能「把上传口子开出去」——拿到
-- 链接和密码的人（多半是活动当天帮忙拍的同学，站内没有账号）直接把图片传进
-- 这个选题的相册，随后由选片人按既有流程逐张打标签。
--
-- 实现方式是给已有的分享链接加一列「用途」，而不是另起一张表：链接的
-- token、密码哈希、有效期、访问会话、限速和撤销语义与浏览链接一模一样，
-- 复制一份只会让「改了密码规则却漏改另一边」成为迟早会发生的事。
-- 两种用途的能力是互斥的（见 ProjectShareService.requireBrowseLink /
-- requireUploadLink）：上传链接看不到相册，浏览链接传不了图。
ALTER TABLE project_share_link ADD COLUMN purpose VARCHAR(20) NOT NULL DEFAULT 'BROWSE';

-- 传进来了多少张。与 view_count 同一个用途：让创建者在不打开相册的情况下
-- 知道这条链接有没有人在用。
ALTER TABLE project_share_link ADD COLUMN upload_count BIGINT NOT NULL DEFAULT 0;

-- 上传者身份绑在**会话**上，而不是每次上传请求各报一次。
--
-- 照片的 photographer_student_id / photographer_name 是 NOT NULL，站内由通讯录
-- 解析后快照写入（AGENTS §2.11）；而上传链接的使用者按定义在通讯录之外，把
-- 整个通讯录摆给站外的人挑更是直接泄露姓名和学号。所以改为进门时自报姓名和
-- 学号，与密码一起构成这次会话，之后该会话上传的每一张都用同一份身份快照——
-- 逐次请求带身份的话，同一个人分两批传的图会落成两个不同的拍摄者，而统计是
-- 按学号归并的。
--
-- 浏览链接的会话这两列为 NULL。
ALTER TABLE project_share_session ADD COLUMN uploader_name VARCHAR(100) NULL;
ALTER TABLE project_share_session ADD COLUMN uploader_student_id VARCHAR(64) NULL;

-- 这张图是从哪条上传链接进来的。两个用处：
--   1. 访客完成上传（complete）时的凭据——只有「本链接创建的、仍在 UPLOADING 的」
--      那一张才轮得到它写元数据，否则拿到一个 id 就能改别人的图片；
--   2. 事后追责：站外上传的图片必须能指回是谁开的口子。
-- 链接是软删的（撤销保留审计线索），所以外键不会因为撤销而悬空。
ALTER TABLE photo ADD COLUMN share_link_id BIGINT NULL;
ALTER TABLE photo ADD CONSTRAINT fk_photo_share_link
    FOREIGN KEY (share_link_id) REFERENCES project_share_link(id);
CREATE INDEX idx_photo_share_link ON photo(share_link_id);
