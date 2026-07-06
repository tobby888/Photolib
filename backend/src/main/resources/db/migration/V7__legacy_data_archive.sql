CREATE TABLE legacy_archive_record (
    source_table VARCHAR(128) NOT NULL,
    source_key VARCHAR(512) NOT NULL,
    payload_json JSON NOT NULL,
    archived_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (source_table, source_key)
);
