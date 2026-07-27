ALTER TABLE app_user ADD COLUMN avatar_object_key VARCHAR(512) NULL;
ALTER TABLE app_user ADD COLUMN avatar_content_type VARCHAR(32) NULL;
ALTER TABLE app_user ADD COLUMN avatar_size BIGINT NULL;

CREATE UNIQUE INDEX uk_user_avatar_object_key ON app_user (avatar_object_key);

ALTER TABLE app_user ADD CONSTRAINT chk_user_avatar_metadata CHECK (
    (avatar_object_key IS NULL AND avatar_content_type IS NULL AND avatar_size IS NULL)
    OR
    (avatar_object_key LIKE 'avatars/%'
        AND avatar_content_type IN ('image/jpeg', 'image/png')
        AND avatar_size BETWEEN 1 AND 1048576)
);
