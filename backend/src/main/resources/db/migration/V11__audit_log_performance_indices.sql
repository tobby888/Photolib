-- Add performance indices for audit log queries
CREATE INDEX idx_audit_log_operator_time ON audit_log(operator_id, created_at);
CREATE INDEX idx_audit_log_resource ON audit_log(resource_type, resource_id);
