ALTER TABLE app_user ADD COLUMN idp_sub VARCHAR(64) NULL;
CREATE UNIQUE INDEX uk_user_idp_sub ON app_user (idp_sub);
