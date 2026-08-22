-- Keep presigned PUT targets addressable until their URLs expire. A successful
-- early delete is not enough: the client could replay the still-valid PUT and
-- recreate an otherwise untracked object.
ALTER TABLE recruitment_upload_batch
    ADD COLUMN upload_url_expires_at DATETIME(6) NULL AFTER archive_size;

ALTER TABLE recruitment_upload_item
    ADD COLUMN upload_url_expires_at DATETIME(6) NULL AFTER temp_object_key;

-- Existing URLs cannot still be valid when this migration is applied. Mark
-- them immediately eligible for an exact-key cleanup retry.
UPDATE recruitment_upload_batch
SET upload_url_expires_at = created_at
WHERE archive_object_key IS NOT NULL AND upload_url_expires_at IS NULL;

UPDATE recruitment_upload_item
SET upload_url_expires_at = created_at
WHERE temp_object_key IS NOT NULL AND upload_url_expires_at IS NULL;

CREATE INDEX idx_recruitment_batch_upload_expiry
    ON recruitment_upload_batch(upload_url_expires_at, archive_object_key);

CREATE INDEX idx_recruitment_item_upload_expiry
    ON recruitment_upload_item(upload_url_expires_at, temp_object_key);
