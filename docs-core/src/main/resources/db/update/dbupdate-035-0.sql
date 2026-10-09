-- 借阅审批工作流：用户对只有 SEARCHABLE 权限的文档可发起借阅申请，
-- 审批通过后授予临时 READ 或 DOWNLOADABLE 权限，到期自动撤销。
--
-- 设计：借阅是事务表（独立 T_BORROW_REQUEST），授权仍复用 T_ACL，
-- 仅在 ACL_TYPE_C 上加 BORROW 标签，便于 BorrowExpiryService 识别和软删。
--
-- IMPORTANT: every statement must be a single line. DbOpenHelper splits the file at
-- .newlines and runs each non-empty, non-comment line as one statement.
--
-- IMPORTANT: H2 用 datetime，PostgreSQL 用 timestamp。DbOpenHelper 会按行首的
-- !H2! / !PGSQL! 标记分派语句，其他数据库忽略该行。

!H2!create table T_BORROW_REQUEST (BOR_ID_C varchar(36) not null, BOR_IDDOC_C varchar(36) not null, BOR_IDREQUSER_C varchar(36) not null, BOR_IDAPPUSER_C varchar(36) not null, BOR_STATUS_C varchar(20) not null, BOR_PERM_C varchar(30) not null, BOR_DURATIONDAYS_N int not null, BOR_NOTE_C varchar(2000), BOR_CREATEDATE_D datetime, BOR_DECISIONDATE_D datetime, BOR_EXPIRYDATE_D datetime, BOR_ACL_ID_C varchar(36), BOR_DELETEDATE_D datetime, primary key (BOR_ID_C));
!PGSQL!create table T_BORROW_REQUEST (BOR_ID_C varchar(36) not null, BOR_IDDOC_C varchar(36) not null, BOR_IDREQUSER_C varchar(36) not null, BOR_IDAPPUSER_C varchar(36) not null, BOR_STATUS_C varchar(20) not null, BOR_PERM_C varchar(30) not null, BOR_DURATIONDAYS_N int not null, BOR_CREATEDATE_D timestamp, BOR_DECISIONDATE_D timestamp, BOR_EXPIRYDATE_D timestamp, BOR_ACL_ID_C varchar(36), BOR_DELETEDATE_D timestamp,BOR_NOTE_C varchar(2000), primary key (BOR_ID_C));

create index IDX_BOR_IDDOC_C      on T_BORROW_REQUEST (BOR_IDDOC_C);
create index IDX_BOR_IDAPPUSER_C  on T_BORROW_REQUEST (BOR_IDAPPUSER_C, BOR_STATUS_C);
create index IDX_BOR_STATUS_C     on T_BORROW_REQUEST (BOR_STATUS_C, BOR_EXPIRYDATE_D);

-- 不在 BOR_ACL_ID_C 上加 FK：到期时 T_ACL 会被软删（设置 ACL_DELETEDATE_D），
-- 但 T_BORROW_REQUEST 仍保留作为历史记录。

update T_CONFIG set CFG_VALUE_C = '35' where CFG_ID_C = 'DB_VERSION';
