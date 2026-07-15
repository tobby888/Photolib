ALTER TABLE photo_request ADD COLUMN return_reason VARCHAR(500) NULL;
ALTER TABLE photo_request ADD COLUMN returned_by BIGINT NULL;
ALTER TABLE photo_request ADD COLUMN returned_at DATETIME(6) NULL;
ALTER TABLE photo_request ADD CONSTRAINT fk_request_returned_by
    FOREIGN KEY (returned_by) REFERENCES app_user(id);
