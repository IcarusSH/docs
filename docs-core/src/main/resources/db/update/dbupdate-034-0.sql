-- DBUPDATE-034-0.SQL

-- Default the watermark toggle on so first-time deployments get it
-- without admin intervention (matches the user-facing requirement).
insert into T_CONFIG (CFG_ID_C, CFG_VALUE_C) values ('WATERMARK_ENABLED', 'true');

-- Update the database version
update T_CONFIG set CFG_VALUE_C = '34' where CFG_ID_C = 'DB_VERSION';