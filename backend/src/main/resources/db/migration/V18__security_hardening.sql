-- The application-level availability check remains useful for friendly errors,
-- but the database constraint is the final guard against concurrent writers.
UPDATE app_user SET email = NULL WHERE deleted = TRUE;
DROP INDEX idx_user_email ON app_user;
CREATE UNIQUE INDEX uk_user_email ON app_user (email);
