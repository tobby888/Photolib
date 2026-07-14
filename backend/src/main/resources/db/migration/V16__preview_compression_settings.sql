ALTER TABLE photo
    ADD COLUMN thumbnail_size BIGINT NULL AFTER thumbnail_object_key;

CREATE TABLE preview_setting (
    id TINYINT PRIMARY KEY,
    compression_ratio DECIMAL(5,4) NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_preview_compression_ratio
        CHECK (compression_ratio > 0 AND compression_ratio <= 1)
);
