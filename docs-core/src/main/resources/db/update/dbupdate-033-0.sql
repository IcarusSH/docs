-- Ensure every active document has WRITE permission for the seeded admin user
-- (USE_ID_C = "admin") and the seeded administrators group (GRP_ID_C = "administrators").
-- This makes the admin's "all permissions open" guarantee visible in the data and the
-- permissions panel; combined with SecurityUtil.skipAclCheck it also covers admin role
-- users created via the UI (USE_ID_C != "admin"), since those are also members of the
-- administrators group and inherit this ACL.
--
-- The not-exists clause makes the migration idempotent.
--
-- IMPORTANT: every statement below must be a single line. DbOpenHelper splits the
-- file at .newlines and runs each non-empty, non-!H2! line as one statement. Multi-line
-- INSERT ... SELECT will be truncated at line 7 and the partial pieces fail.

insert into T_ACL (ACL_ID_C, ACL_PERM_C, ACL_TYPE_C, ACL_SOURCEID_C, ACL_TARGETID_C) select 'acl-admin-doc-' || DOC_ID_C, 'WRITE', 'USER', DOC_ID_C, 'admin' from T_DOCUMENT where DOC_DELETEDATE_D is null and not exists (select 1 from T_ACL a where a.ACL_SOURCEID_C = DOC_ID_C and a.ACL_TARGETID_C = 'admin' and a.ACL_DELETEDATE_D is null);

insert into T_ACL (ACL_ID_C, ACL_PERM_C, ACL_TYPE_C, ACL_SOURCEID_C, ACL_TARGETID_C) select 'acl-admins-doc-' || DOC_ID_C, 'WRITE', 'GROUP', DOC_ID_C, 'administrators' from T_DOCUMENT where DOC_DELETEDATE_D is null and not exists (select 1 from T_ACL a where a.ACL_SOURCEID_C = DOC_ID_C and a.ACL_TARGETID_C = 'administrators' and a.ACL_DELETEDATE_D is null);

insert into T_ACL (ACL_ID_C, ACL_PERM_C, ACL_TYPE_C, ACL_SOURCEID_C, ACL_TARGETID_C) select 'acl-admin-tag-' || TAG_ID_C, 'WRITE', 'USER', TAG_ID_C, 'admin' from T_TAG where TAG_DELETEDATE_D is null and not exists (select 1 from T_ACL a where a.ACL_SOURCEID_C = TAG_ID_C and a.ACL_TARGETID_C = 'admin' and a.ACL_DELETEDATE_D is null);

insert into T_ACL (ACL_ID_C, ACL_PERM_C, ACL_TYPE_C, ACL_SOURCEID_C, ACL_TARGETID_C) select 'acl-admins-tag-' || TAG_ID_C, 'WRITE', 'GROUP', TAG_ID_C, 'administrators' from T_TAG where TAG_DELETEDATE_D is null and not exists (select 1 from T_ACL a where a.ACL_SOURCEID_C = TAG_ID_C and a.ACL_TARGETID_C = 'administrators' and a.ACL_DELETEDATE_D is null);

update T_CONFIG set CFG_VALUE_C = '33' where CFG_ID_C = 'DB_VERSION';