-- 管理员给工时填写的「自定义选题」。
--
-- 需求：管理员更正工时时，除了改挂到任意一条需求，还能直接手写一个选题名——活动没在系统里建选题、
-- 或者需求挂错了又找不到对的那条时用。手写的选题要和真实选题一样进成员核算、工时导出、清算单、
-- 工资计算和工时分析，同名的合并成一组。
--
-- 规则（见 WorklogProjectSql）：
--   * 填了 custom_project_title，这条工时就算在这个自定义选题下，不再算进需求 / 链接所属的真实选题；
--   * 自定义选题没有结束时间，清算期按这条工时的 work_date 落区间判断；
--   * 需求 / 链接照旧保留：校区、可见范围、通知都还按它们走。
-- 写入时去首尾空白，空串存 NULL，查询里只判 IS NULL。
ALTER TABLE worklog ADD COLUMN custom_project_title VARCHAR(200) NULL;

-- 工资计算的一行可以属于自定义选题：没有选题 id，也没有结束时间。
ALTER TABLE salary_calculation_line MODIFY project_id BIGINT NULL;
ALTER TABLE salary_calculation_line MODIFY project_completed_at DATETIME(6) NULL;
