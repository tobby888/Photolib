-- Bind every anonymous draft capability to the normalized student identifier
-- supplied when the draft was created. Existing submitted drafts can be
-- backfilled from their immutable application. This feature has not yet been
-- released, so any remaining unbound pre-release drafts are safely expired.
ALTER TABLE recruitment_draft
    ADD COLUMN normalized_student_id VARCHAR(64) NULL AFTER task_id;

UPDATE recruitment_draft d
SET normalized_student_id = (
    SELECT a.normalized_student_id
    FROM recruitment_application a
    WHERE a.draft_id = d.id
)
WHERE EXISTS (
    SELECT 1 FROM recruitment_application a WHERE a.draft_id = d.id
);

UPDATE recruitment_draft
SET normalized_student_id = 'LEGACY_UNBOUND'
WHERE normalized_student_id IS NULL;

UPDATE recruitment_draft
SET status = 'EXPIRED', updated_at = CURRENT_TIMESTAMP(6)
WHERE status = 'DRAFT' AND normalized_student_id = 'LEGACY_UNBOUND';

ALTER TABLE recruitment_draft
    MODIFY COLUMN normalized_student_id VARCHAR(64) NOT NULL;
