ALTER TABLE photo_upload_item
    ADD COLUMN temp_local_path VARCHAR(1024) NULL AFTER temp_object_key;
