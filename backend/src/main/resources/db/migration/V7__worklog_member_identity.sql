ALTER TABLE worklog ADD COLUMN member_name VARCHAR(100) NULL;
ALTER TABLE worklog ADD COLUMN member_student_id VARCHAR(64) NULL;

CREATE INDEX idx_worklog_member ON worklog(member_student_id, member_name);
