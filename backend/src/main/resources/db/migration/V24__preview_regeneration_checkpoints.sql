CREATE TABLE preview_regeneration_stage (
    photo_id BIGINT PRIMARY KEY,
    profile_fingerprint VARCHAR(192) NOT NULL,
    source_object_key VARCHAR(512) NOT NULL,
    staged_object_key VARCHAR(512) NOT NULL,
    staged_size BIGINT NOT NULL,
    staged_content_type VARCHAR(100) NOT NULL,
    staged_sha256 CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_preview_stage_photo FOREIGN KEY (photo_id) REFERENCES photo(id),
    CONSTRAINT uk_preview_stage_object UNIQUE (staged_object_key),
    CONSTRAINT chk_preview_stage_size CHECK (staged_size > 0),
    INDEX idx_preview_stage_profile (profile_fingerprint)
);
