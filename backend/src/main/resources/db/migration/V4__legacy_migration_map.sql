CREATE TABLE legacy_migration_item (
    source_type VARCHAR(32) NOT NULL,
    source_id BIGINT NOT NULL,
    target_id BIGINT NOT NULL,
    migrated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (source_type, source_id)
);
