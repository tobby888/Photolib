ALTER TABLE photo_request ADD COLUMN batch_id CHAR(26) NULL;

CREATE INDEX idx_request_batch ON photo_request (batch_id);
