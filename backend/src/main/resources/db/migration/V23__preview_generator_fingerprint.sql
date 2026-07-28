-- Existing rows span the former ImageIO and native encoders, so they cannot be
-- truthfully certified as the current profile. The first post-upgrade background
-- run performs one safe generation switch and stores the current fingerprint.
ALTER TABLE preview_setting
    ADD COLUMN generator_fingerprint VARCHAR(128) NOT NULL
        DEFAULT 'legacy/unknown' AFTER compression_ratio;
