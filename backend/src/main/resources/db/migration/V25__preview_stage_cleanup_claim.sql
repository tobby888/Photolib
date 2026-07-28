ALTER TABLE preview_regeneration_stage
    ADD COLUMN cleanup_token VARCHAR(36) NULL;

ALTER TABLE preview_regeneration_stage
    ADD COLUMN cleanup_claimed_at DATETIME(6) NULL;

CREATE INDEX idx_preview_stage_cleanup_claim
    ON preview_regeneration_stage (cleanup_claimed_at);
