-- 自定义选题的工时可以不挂需求。
--
-- 需求：自定义选题本来就是给「系统里根本没有这个选题」的活动用的，这时也就没有对应的需求。
-- 管理员（持有 WORKLOG_SUBMIT_ANY、不限校区的账号）可以只写自定义选题、从任意校区通讯录选人来填报。
-- 于是一条工时说得出来历的方式多了一种：有需求、有上传链接，或者有自定义选题。
-- 这类工时和链接申报的一样没有校区，校区范围账号看不到（WorklogService.requireVisible）。
--
-- DROP CONSTRAINT：MySQL 8.0.19+ 与 H2 都支持（MySQL 专有的 DROP CHECK H2 不认）。
ALTER TABLE worklog DROP CONSTRAINT chk_worklog_origin;
ALTER TABLE worklog ADD CONSTRAINT chk_worklog_origin
    CHECK (request_id IS NOT NULL OR share_link_id IS NOT NULL OR custom_project_title IS NOT NULL);
