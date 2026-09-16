-- 「向任意需求申报工时」：不要求是需求参与人，所属选题已完成或已取消也能申报，
-- 用于管理员在项目结束后补录工时。校区约束不放开——需求须在授权校区内，工作人员
-- 须取自需求所属校区的通讯录。
--
-- 默认只给管理员组。管理员组的权限明细在编辑时会被固定为全集，但存量数据库里
-- 的行不会自己长出来，所以这里显式补上。
INSERT INTO permission_group_permission(group_id, permission_code)
SELECT id, 'WORKLOG_SUBMIT_ANY' FROM permission_group
WHERE code = 'ADMIN';
