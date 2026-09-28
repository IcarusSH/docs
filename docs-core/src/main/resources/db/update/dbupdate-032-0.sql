-- DBUPDATE-032-0.SQL

-- Casdoor SSO: only seed default storage quota. Other CASDOOR_* values are
-- set by AppContext.startUp() from DOCS_CASDOOR_* env vars, or by the admin
-- via /app/config_casdoor. We deliberately do NOT seed CASDOOR_ENABLED=true
-- here, so that the feature is opt-in.
insert into T_CONFIG (CFG_ID_C, CFG_VALUE_C) values ('CASDOOR_DEFAULT_STORAGE', '1073741824');

-- Update the database version
update T_CONFIG set CFG_VALUE_C = '32' where CFG_ID_C = 'DB_VERSION';