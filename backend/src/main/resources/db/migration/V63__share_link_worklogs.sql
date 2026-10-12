-- 通过上传链接申报的工时。
--
-- 需求：上传链接现在创作选题和活动选题都能开，拿着链接传完图的人可以在同一页
-- 申报这次的工时，申报直接落成「待确认」，出现在部长和管理员的工时审核面板里。
--
-- 站内的工时一律挂在一条需求下（`request_id`），校区、所属选题都从需求读。而上传
-- 链接传进来的图片本来就不挂需求——它们直接进选题相册（V48），活动选题甚至可以
-- 一条需求都没有。所以这类工时的归属是「哪条上传链接」，而不是硬找一条需求挂上去：
--   * `share_link_id` 指回那条链接，所属选题由 `project_share_link.project_id` 现读，
--     不在工时上再抄一份 project_id——同一件事两个地方各存一份，迟早会对不上；
--   * `request_id` 因此放开成可空；
--   * 校区如实没有：选题不属于任何校区，访客也报不出，和 V48 对照片的处理一致。
--     校区范围账号按需求校区筛工时，于是天然看不到这类工时，这与「校区范围账号
--     不能创建分享链接」是同一条边界。
--
-- 链接是软删的（撤销保留审计线索），外键不会因为撤销而悬空；统计用原生 SQL 关联，
-- 链接撤销之后已经申报、已经确认的工时照样算数。
ALTER TABLE worklog MODIFY request_id BIGINT NULL;

ALTER TABLE worklog ADD COLUMN share_link_id BIGINT NULL;
ALTER TABLE worklog ADD CONSTRAINT fk_worklog_share_link
    FOREIGN KEY (share_link_id) REFERENCES project_share_link(id);

-- 一条工时必须说得出自己是哪来的：站内填报的有需求，链接申报的有链接。
ALTER TABLE worklog ADD CONSTRAINT chk_worklog_origin
    CHECK (request_id IS NOT NULL OR share_link_id IS NOT NULL);

-- 两个查询都走这个索引：申报时判「这个人这一天在这条链接上报过没有」，
-- 以及访客回看自己在这条链接上申报过的记录。
CREATE INDEX idx_worklog_share_link ON worklog(share_link_id, member_student_id, work_date);
