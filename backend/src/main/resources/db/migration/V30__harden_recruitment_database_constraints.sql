-- V28 used created_at as a marker for upload targets whose original presigned
-- expiry was not persisted. Keep those exact keys for the full conservative
-- replay window before the startup cleanup job is allowed to forget them.
-- RecruitmentUploadService caps newly issued upload URLs to the same seven-day
-- window, so the migration and runtime policy remain aligned.
UPDATE recruitment_upload_batch
SET upload_url_expires_at = TIMESTAMPADD(DAY, 7, CURRENT_TIMESTAMP(6))
WHERE archive_object_key IS NOT NULL
  AND upload_url_expires_at = created_at;

UPDATE recruitment_upload_item
SET upload_url_expires_at = TIMESTAMPADD(DAY, 7, CURRENT_TIMESTAMP(6))
WHERE temp_object_key IS NOT NULL
  AND upload_url_expires_at = created_at;

-- An application must reference the same task and normalized student identity
-- that own its anonymous draft capability. The individual foreign keys from
-- V27 remain useful, while this composite key closes cross-task/cross-student
-- attachment attribution gaps at the database boundary.
ALTER TABLE recruitment_draft
    ADD CONSTRAINT uk_recruitment_draft_application_binding
        UNIQUE (id, task_id, normalized_student_id);

ALTER TABLE recruitment_application
    ADD CONSTRAINT fk_recruitment_application_draft_binding
        FOREIGN KEY (draft_id, task_id, normalized_student_id)
        REFERENCES recruitment_draft(id, task_id, normalized_student_id);

-- Enumerate the exact ASCII code point at every position. This is deliberately
-- more verbose than string equality: production MySQL commonly uses a
-- case-insensitive collation, while H2 cannot safely retain fixed BINARY
-- constants in a CHECK across Flyway connections. These numeric predicates are
-- strict and portable in both engines.
ALTER TABLE recruitment_task
    ADD CONSTRAINT chk_recruitment_task_status CHECK (
        (
            CHAR_LENGTH(status) = 5
            AND ASCII(SUBSTRING(status, 1, 1)) = 68
            AND ASCII(SUBSTRING(status, 2, 1)) = 82
            AND ASCII(SUBSTRING(status, 3, 1)) = 65
            AND ASCII(SUBSTRING(status, 4, 1)) = 70
            AND ASCII(SUBSTRING(status, 5, 1)) = 84
        )
        OR (
            CHAR_LENGTH(status) = 9
            AND ASCII(SUBSTRING(status, 1, 1)) = 80
            AND ASCII(SUBSTRING(status, 2, 1)) = 85
            AND ASCII(SUBSTRING(status, 3, 1)) = 66
            AND ASCII(SUBSTRING(status, 4, 1)) = 76
            AND ASCII(SUBSTRING(status, 5, 1)) = 73
            AND ASCII(SUBSTRING(status, 6, 1)) = 83
            AND ASCII(SUBSTRING(status, 7, 1)) = 72
            AND ASCII(SUBSTRING(status, 8, 1)) = 69
            AND ASCII(SUBSTRING(status, 9, 1)) = 68
        )
        OR (
            CHAR_LENGTH(status) = 6
            AND ASCII(SUBSTRING(status, 1, 1)) = 67
            AND ASCII(SUBSTRING(status, 2, 1)) = 76
            AND ASCII(SUBSTRING(status, 3, 1)) = 79
            AND ASCII(SUBSTRING(status, 4, 1)) = 83
            AND ASCII(SUBSTRING(status, 5, 1)) = 69
            AND ASCII(SUBSTRING(status, 6, 1)) = 68
        )
    );

ALTER TABLE recruitment_draft
    ADD CONSTRAINT chk_recruitment_draft_status CHECK (
        (
            CHAR_LENGTH(status) = 5
            AND ASCII(SUBSTRING(status, 1, 1)) = 68
            AND ASCII(SUBSTRING(status, 2, 1)) = 82
            AND ASCII(SUBSTRING(status, 3, 1)) = 65
            AND ASCII(SUBSTRING(status, 4, 1)) = 70
            AND ASCII(SUBSTRING(status, 5, 1)) = 84
        )
        OR (
            CHAR_LENGTH(status) = 9
            AND ASCII(SUBSTRING(status, 1, 1)) = 83
            AND ASCII(SUBSTRING(status, 2, 1)) = 85
            AND ASCII(SUBSTRING(status, 3, 1)) = 66
            AND ASCII(SUBSTRING(status, 4, 1)) = 77
            AND ASCII(SUBSTRING(status, 5, 1)) = 73
            AND ASCII(SUBSTRING(status, 6, 1)) = 84
            AND ASCII(SUBSTRING(status, 7, 1)) = 84
            AND ASCII(SUBSTRING(status, 8, 1)) = 69
            AND ASCII(SUBSTRING(status, 9, 1)) = 68
        )
        OR (
            CHAR_LENGTH(status) = 15
            AND ASCII(SUBSTRING(status, 1, 1)) = 67
            AND ASCII(SUBSTRING(status, 2, 1)) = 76
            AND ASCII(SUBSTRING(status, 3, 1)) = 69
            AND ASCII(SUBSTRING(status, 4, 1)) = 65
            AND ASCII(SUBSTRING(status, 5, 1)) = 78
            AND ASCII(SUBSTRING(status, 6, 1)) = 85
            AND ASCII(SUBSTRING(status, 7, 1)) = 80
            AND ASCII(SUBSTRING(status, 8, 1)) = 95
            AND ASCII(SUBSTRING(status, 9, 1)) = 80
            AND ASCII(SUBSTRING(status, 10, 1)) = 69
            AND ASCII(SUBSTRING(status, 11, 1)) = 78
            AND ASCII(SUBSTRING(status, 12, 1)) = 68
            AND ASCII(SUBSTRING(status, 13, 1)) = 73
            AND ASCII(SUBSTRING(status, 14, 1)) = 78
            AND ASCII(SUBSTRING(status, 15, 1)) = 71
        )
        OR (
            CHAR_LENGTH(status) = 7
            AND ASCII(SUBSTRING(status, 1, 1)) = 69
            AND ASCII(SUBSTRING(status, 2, 1)) = 88
            AND ASCII(SUBSTRING(status, 3, 1)) = 80
            AND ASCII(SUBSTRING(status, 4, 1)) = 73
            AND ASCII(SUBSTRING(status, 5, 1)) = 82
            AND ASCII(SUBSTRING(status, 6, 1)) = 69
            AND ASCII(SUBSTRING(status, 7, 1)) = 68
        )
    );

ALTER TABLE recruitment_upload_batch
    ADD CONSTRAINT chk_recruitment_upload_batch_mode CHECK (
        (
            CHAR_LENGTH(mode) = 5
            AND ASCII(SUBSTRING(mode, 1, 1)) = 70
            AND ASCII(SUBSTRING(mode, 2, 1)) = 73
            AND ASCII(SUBSTRING(mode, 3, 1)) = 76
            AND ASCII(SUBSTRING(mode, 4, 1)) = 69
            AND ASCII(SUBSTRING(mode, 5, 1)) = 83
        )
        OR (
            CHAR_LENGTH(mode) = 3
            AND ASCII(SUBSTRING(mode, 1, 1)) = 90
            AND ASCII(SUBSTRING(mode, 2, 1)) = 73
            AND ASCII(SUBSTRING(mode, 3, 1)) = 80
        )
    );

ALTER TABLE recruitment_upload_batch
    ADD CONSTRAINT chk_recruitment_upload_batch_status CHECK (
        (
            CHAR_LENGTH(status) = 9
            AND ASCII(SUBSTRING(status, 1, 1)) = 85
            AND ASCII(SUBSTRING(status, 2, 1)) = 80
            AND ASCII(SUBSTRING(status, 3, 1)) = 76
            AND ASCII(SUBSTRING(status, 4, 1)) = 79
            AND ASCII(SUBSTRING(status, 5, 1)) = 65
            AND ASCII(SUBSTRING(status, 6, 1)) = 68
            AND ASCII(SUBSTRING(status, 7, 1)) = 73
            AND ASCII(SUBSTRING(status, 8, 1)) = 78
            AND ASCII(SUBSTRING(status, 9, 1)) = 71
        )
        OR (
            CHAR_LENGTH(status) = 10
            AND ASCII(SUBSTRING(status, 1, 1)) = 80
            AND ASCII(SUBSTRING(status, 2, 1)) = 82
            AND ASCII(SUBSTRING(status, 3, 1)) = 79
            AND ASCII(SUBSTRING(status, 4, 1)) = 67
            AND ASCII(SUBSTRING(status, 5, 1)) = 69
            AND ASCII(SUBSTRING(status, 6, 1)) = 83
            AND ASCII(SUBSTRING(status, 7, 1)) = 83
            AND ASCII(SUBSTRING(status, 8, 1)) = 73
            AND ASCII(SUBSTRING(status, 9, 1)) = 78
            AND ASCII(SUBSTRING(status, 10, 1)) = 71
        )
        OR (
            CHAR_LENGTH(status) = 19
            AND ASCII(SUBSTRING(status, 1, 1)) = 80
            AND ASCII(SUBSTRING(status, 2, 1)) = 65
            AND ASCII(SUBSTRING(status, 3, 1)) = 82
            AND ASCII(SUBSTRING(status, 4, 1)) = 84
            AND ASCII(SUBSTRING(status, 5, 1)) = 73
            AND ASCII(SUBSTRING(status, 6, 1)) = 65
            AND ASCII(SUBSTRING(status, 7, 1)) = 76
            AND ASCII(SUBSTRING(status, 8, 1)) = 76
            AND ASCII(SUBSTRING(status, 9, 1)) = 89
            AND ASCII(SUBSTRING(status, 10, 1)) = 95
            AND ASCII(SUBSTRING(status, 11, 1)) = 83
            AND ASCII(SUBSTRING(status, 12, 1)) = 85
            AND ASCII(SUBSTRING(status, 13, 1)) = 67
            AND ASCII(SUBSTRING(status, 14, 1)) = 67
            AND ASCII(SUBSTRING(status, 15, 1)) = 69
            AND ASCII(SUBSTRING(status, 16, 1)) = 69
            AND ASCII(SUBSTRING(status, 17, 1)) = 68
            AND ASCII(SUBSTRING(status, 18, 1)) = 69
            AND ASCII(SUBSTRING(status, 19, 1)) = 68
        )
        OR (
            CHAR_LENGTH(status) = 9
            AND ASCII(SUBSTRING(status, 1, 1)) = 83
            AND ASCII(SUBSTRING(status, 2, 1)) = 85
            AND ASCII(SUBSTRING(status, 3, 1)) = 67
            AND ASCII(SUBSTRING(status, 4, 1)) = 67
            AND ASCII(SUBSTRING(status, 5, 1)) = 69
            AND ASCII(SUBSTRING(status, 6, 1)) = 69
            AND ASCII(SUBSTRING(status, 7, 1)) = 68
            AND ASCII(SUBSTRING(status, 8, 1)) = 69
            AND ASCII(SUBSTRING(status, 9, 1)) = 68
        )
        OR (
            CHAR_LENGTH(status) = 6
            AND ASCII(SUBSTRING(status, 1, 1)) = 70
            AND ASCII(SUBSTRING(status, 2, 1)) = 65
            AND ASCII(SUBSTRING(status, 3, 1)) = 73
            AND ASCII(SUBSTRING(status, 4, 1)) = 76
            AND ASCII(SUBSTRING(status, 5, 1)) = 69
            AND ASCII(SUBSTRING(status, 6, 1)) = 68
        )
    );

ALTER TABLE recruitment_upload_item
    ADD CONSTRAINT chk_recruitment_upload_item_status CHECK (
        (
            CHAR_LENGTH(status) = 9
            AND ASCII(SUBSTRING(status, 1, 1)) = 85
            AND ASCII(SUBSTRING(status, 2, 1)) = 80
            AND ASCII(SUBSTRING(status, 3, 1)) = 76
            AND ASCII(SUBSTRING(status, 4, 1)) = 79
            AND ASCII(SUBSTRING(status, 5, 1)) = 65
            AND ASCII(SUBSTRING(status, 6, 1)) = 68
            AND ASCII(SUBSTRING(status, 7, 1)) = 73
            AND ASCII(SUBSTRING(status, 8, 1)) = 78
            AND ASCII(SUBSTRING(status, 9, 1)) = 71
        )
        OR (
            CHAR_LENGTH(status) = 10
            AND ASCII(SUBSTRING(status, 1, 1)) = 80
            AND ASCII(SUBSTRING(status, 2, 1)) = 82
            AND ASCII(SUBSTRING(status, 3, 1)) = 79
            AND ASCII(SUBSTRING(status, 4, 1)) = 67
            AND ASCII(SUBSTRING(status, 5, 1)) = 69
            AND ASCII(SUBSTRING(status, 6, 1)) = 83
            AND ASCII(SUBSTRING(status, 7, 1)) = 83
            AND ASCII(SUBSTRING(status, 8, 1)) = 73
            AND ASCII(SUBSTRING(status, 9, 1)) = 78
            AND ASCII(SUBSTRING(status, 10, 1)) = 71
        )
        OR (
            CHAR_LENGTH(status) = 9
            AND ASCII(SUBSTRING(status, 1, 1)) = 83
            AND ASCII(SUBSTRING(status, 2, 1)) = 85
            AND ASCII(SUBSTRING(status, 3, 1)) = 67
            AND ASCII(SUBSTRING(status, 4, 1)) = 67
            AND ASCII(SUBSTRING(status, 5, 1)) = 69
            AND ASCII(SUBSTRING(status, 6, 1)) = 69
            AND ASCII(SUBSTRING(status, 7, 1)) = 68
            AND ASCII(SUBSTRING(status, 8, 1)) = 69
            AND ASCII(SUBSTRING(status, 9, 1)) = 68
        )
        OR (
            CHAR_LENGTH(status) = 6
            AND ASCII(SUBSTRING(status, 1, 1)) = 70
            AND ASCII(SUBSTRING(status, 2, 1)) = 65
            AND ASCII(SUBSTRING(status, 3, 1)) = 73
            AND ASCII(SUBSTRING(status, 4, 1)) = 76
            AND ASCII(SUBSTRING(status, 5, 1)) = 69
            AND ASCII(SUBSTRING(status, 6, 1)) = 68
        )
    );
