-- The shipped identity must not carry the internal project name; every screen
-- renders whatever the administrator configured. Deployments where branding was
-- already customised keep their own title and slogan.
UPDATE branding_setting
SET title = '摄影工作站', slogan = '影像协作平台'
WHERE id = 1 AND title = 'PhotoLib' AND slogan = '摄影工作站';
