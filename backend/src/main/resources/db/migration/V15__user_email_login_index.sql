UPDATE app_user
SET email = NULL
WHERE email IS NOT NULL AND TRIM(email) = '';

UPDATE app_user
SET email = LOWER(TRIM(email))
WHERE email IS NOT NULL;

CREATE INDEX idx_user_email ON app_user (email);
