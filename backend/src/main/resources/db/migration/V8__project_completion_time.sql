ALTER TABLE project ADD COLUMN completed_at DATETIME(6) NULL AFTER created_by;

UPDATE project
SET completed_at = updated_at
WHERE status = 'COMPLETED' AND completed_at IS NULL;

CREATE INDEX idx_project_completed_at ON project(status, completed_at);
