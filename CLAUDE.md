# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概览

Teedy（前身 Sismics Docs）是一个轻量级的开源文档管理系统（DMS），支持 OCR、LDAP、全文搜索、文档版本控制、Webhook、用户配额、2FA、RESTful API 等特性。后端为 Java 11 + Maven 多模块项目，前端为 AngularJS，桌面端为 Electron（`docs-importer/`），另有独立 Android 客户端（`docs-android/`，Gradle 项目）。

## 常用命令

所有 Maven 命令在仓库根目录执行。模块均聚合在根 `pom.xml` 的 `<modules>` 中（仅 `docs-core`、`docs-web-common`、`docs-web` 三个；`docs-importer` 与 `docs-android` 独立维护，不参与 Maven 构建）。

### 一次性构建

```bash
# 安装依赖（按操作系统执行）
scripts/dev_setup.sh   # macOS / Debian-Ubuntu / Fedora

# 编译并安装到本地仓库（跳过测试，用于本地启动 Jetty）
mvn clean -DskipTests install
```

### 本地开发

```bash
# 启动 Jetty 开发服务器（监听 8080）
cd docs-web
mvn jetty:run
# 浏览器访问 http://localhost:8080/docs-web/src/
# Dev profile 会把 src/dev/resources 的 config.properties 复制进 classpath
```

### 生产构建

```bash
# 生成可部署 WAR（前端会跑 npm install + grunt）
cd docs-web
mvn -Pprod -DskipTests clean install
# 产物：docs-web/target/docs-web-*.war
```

CI 使用相同命令：`mvn --batch-mode -Pprod clean install`（见 `.github/workflows/build-deploy.yml`）。

### 测试

```bash
# 运行所有模块的测试（默认 forkCount=1, reuseForks=false，与 docs-core 重置 H2 schema 一致）
mvn test

# 单个测试类
mvn -pl docs-core test -Dtest=DocumentDaoTest
mvn -pl docs-web test -Dtest=UserResourceTest

# 单个测试方法
mvn -pl docs-core test -Dtest=DocumentDaoTest#testCreateDocument
```

测试套件使用 H2 内存数据库（`com.h2database:h2`，test scope），不会连接外部 PostgreSQL。
注意：surefire 配置了 `reuseForks=false`，每次测试类都跑在新 JVM 里，避免 Lucene 索引/线程状态残留。

### Docker

```bash
docker build -t teedy .   # 依赖 docs-web/target/docs-web-*.war，需先 prod 构建
docker compose up          # 单容器版（含 H2，仅供测试）
```

数据目录挂载 `/data`，环境变量见 `README.md`（`DOCS_BASE_URL`、`DATABASE_URL` 等）。

## 模块结构与架构

```
docs/
├── docs-core/        # 业务核心（jar）：DAO、Service、模型、异步监听器
├── docs-web-common/  # 跨服务通用层（jar）：com.sismics.security、rest、util
├── docs-web/         # Web 应用（war）：JAX-RS 资源 + AngularJS 前端
├── docs-importer/    # Electron 桌面批量导入工具（独立 npm 工程）
├── docs-android/     # Android 客户端（独立 Gradle 工程）
└── scripts/          # dev_setup.sh：跨平台依赖安装
```

`docs-web-common` 故意不放在 `com.sismics.docs.*` 命名空间下——它是 `com.sismics.{security,rest,util}`，便于以后被其他项目复用。

### 关键分层（自下而上）

| 层 | 包 / 路径 | 职责 |
| --- | --- | --- |
| 模型 | `docs-core/.../model/` | JPA 实体（`@Entity`），无业务逻辑 |
| DAO | `docs-core/.../dao/` | 基于 `BaseDao<T>` + 原生 SQL；分页对象 `PaginatedList` 来自 `util/jpa/` |
| Service | `docs-core/.../service/` | 业务规则、事务边界（`@Transactional`）、抛 `ClientException` |
| 事件 | `docs-core/.../event/` 与 `listener/async/` | Guava `AsyncEventBus` 解耦副作用：索引重建、缩略图/OCR 处理、Webhook 派发、密码丢失邮件等 |
| REST | `docs-web/.../rest/resource/` | JAX-RS 端点（`@Path`），继承 `BaseResource` 提供鉴权注入与 ApiDoc 注解 |
| 过滤器 | `docs-web-common/.../util/filter/` | `SecurityFilter` 三件套：`TokenBased` / `JwtBased` / `HeaderBased`，通过 `@Priority` 链式生效 |
| 前端 | `docs-web/src/main/webapp/src/app/docs/` | AngularJS 1.x，按 `controller / service / directive / filter` 分目录，状态机定义在 `app.js` |

### 数据库

- 生产：PostgreSQL（驱动仅 `docs-core` 引入，`org.postgresql:postgresql`）。
- 测试：H2 内存库（`com.h2database:h2`，test scope）。
- Schema 通过 `src/main/resources/db/update/dbupdate-XXX-N.sql` 增量更新；启动时由 `Startup` 监听器扫描并执行。**新增表/列只能追加新 SQL 文件**，禁止修改已发布的 dbupdate。
- 配置文件：`docs-core/src/main/resources/config.properties`（生产）+ `src/dev/resources/config.properties`（开发，被 Maven dev profile 覆盖）。

### 数据库部署约束

**DbOpenHelper 是幂等的**：`open()` 读 `T_CONFIG.DB_VERSION`，只有落后于 `config.properties` 的 `db.version` 时才执行迁移。已经是最新版本时直接跳过，**重启 jetty 不会丢数据**。

**禁止 `rm -rf ~/.teedy-data/db`**：该目录是 H2 文件库的位置，与 PostgreSQL 部署无关。切到 PG 后此目录几乎为空，误删不会影响 PG 数据，但**养成"清库"习惯后容易误操作真正想要保留的数据**。

**禁止 `DROP DATABASE teedy` / `redact`**：会清掉所有 PG 数据。仅在明确要"从空白重启"时执行。

**dbupdate 编写纪律**：
1. 每个语句必须单行。`DbOpenHelper.executeScript()` 用 `CharStreams.readLines` 按换行切分、每行独立执行；多行的 `CREATE TABLE`、`INSERT-SELECT-WHERE-NOT-EXISTS` 会被截断成部分语句触发 PG 报"语法错误"并 abort 事务。
2. 新增 dbupdate 时同步更新三处 `config.properties` 的 `db.version=N`：`docs-core/src/main/resources/config.properties`、`docs-web/src/dev/resources/config.properties`、`docs-web/src/prod/resources/config.properties`。

### 异步事件总线

`docs-core/.../listener/Startup` 在应用启动时构建全局 `AsyncEventBus`，并注册所有 `AsyncListener`。典型流程：

1. REST 资源调用 `Service`，Service 在事务里写 DB 后 `eventBus.post(new DocumentCreatedAsyncEvent(...))`。
2. `DocumentCreatedAsyncListener` 异步触发 Lucene 索引写入、文件指纹计算、缩略图/OCR 处理。
3. `WebhookAsyncListener` 异步派发 Webhook。
4. `RouteStepValidateAsyncListener` 处理工作流流转。

调试某个副作用时，先在 `event/` 找到事件类型，再 grep 对应的 `*AsyncListener.java`。

### 全文检索

`util/indexing/LuceneIndexingHandler` 封装 Lucene 8.7 索引的读/写/重建；`RebuildIndexAsyncEvent` 触发全量重建。索引目录路径由 `ConfigType.LUCENE_DIRECTORY_STORAGE` 配置（DB 里的 `CONFIG` 表）。

### 加密

- 密码：`at.favre.lib:bcrypt`，`ConfigType` 中可调 `DOCS_BCRYPT_WORK`（4-31）。
- 存储文件：`util/EncryptionUtil`，256-bit AES，密钥派生见同包代码。写文件前需先读取 `ConfigType` 检查是否启用。

### 国际化

`docs-core/src/main/resources/messages*.properties`（含 `messages_zh_CN.properties`）；前端 AngularJS 通过 `pascalprecht.translate` 加载 `src/main/webapp/src/locale/`。新增语言需同时补两侧。

## 约定与注意事项

- **依赖版本集中在根 `pom.xml` 的 `<properties>`**，所有模块继承 `dependencyManagement`；新增依赖先在父 POM 写版本。
- **Java 11** (`maven.compiler.source/target = 11`)，不要使用 Java 12+ 语法。
- **Surefire** 已固定 `forkCount=1, reuseForks=false`，改这两个值会导致共享内存状态类的测试相互污染。
- **Dev vs Prod profile**：`docs-core` 与 `docs-web` 均定义 `dev` profile（默认激活）与 `prod` profile。生产资源在 `src/prod/resources/`，本地在 `src/dev/resources/`。构建 WAR 必须加 `-Pprod`。
- **CI**（`.github/workflows/build-deploy.yml`）在 Ubuntu runner 上额外 `apt-get install ffmpeg mediainfo tesseract-ocr tesseract-ocr-deu` 用于跑测试——本地缺失这些工具时，缩略图/OCR 相关测试会失败。
- **依赖注入**：DAO 通过构造器注入 `DataSource` / `LuceneIndexingHandler`，没有用 Spring/CDI 容器；新 Service 也按此风格手写。
- **事务**：`@Transactional` 注解在 Service 上，DAO 方法不带事务；嵌套调用通过 `TransactionUtil` 工具处理。
- **前端构建链**：prod profile 触发 `npm install` + `grunt --apiurl=api`，结果落在 `src/main/webapp/dist`，WAR 的 `warSourceDirectory` 指向 `dist`。本地开发 Jetty 不需要 grunt，dev profile 直接读 `src/main/webapp` 原始文件。
- **REST 鉴权**：`docs-web-common/.../util/filter/SecurityFilter` 是一个抽象类，三个实现分别处理 Token、JWT、HTTP Header 头。`@Priority` 决定顺序，新增加一种鉴权方式请实现 `SecurityFilter` 并在 `web.xml` 注册。
- **安全相关修改**（认证、文件加密、Webhook 签名）：README 中"Security Considerations"类问题请参考 `CODE_OF_CONDUCT.md` 与近一年的 commit 历史，避免引入 CVE 风格的回归。

## 重要文件速查

| 关注点 | 路径 |
| --- | --- |
| 启动入口（Servlet 监听器） | `docs-web/src/main/webapp/WEB-INF/web.xml` |
| 全局配置枚举 | `docs-core/src/main/java/com/sismics/docs/core/constant/ConfigType.java` |
| 所有权限常量 | `docs-web/src/main/java/com/sismics/docs/rest/constant/BaseFunction.java` |
| 数据库迁移 | `docs-core/src/main/resources/db/update/` |
| 异步事件 | `docs-core/src/main/java/com/sismics/docs/core/event/` + `listener/async/` |
| REST 端点 | `docs-web/src/main/java/com/sismics/docs/rest/resource/` |
| 安全过滤器 | `docs-web-common/src/main/java/com/sismics/util/filter/` |
| 前端入口 | `docs-web/src/main/webapp/src/app/docs/app.js` |
| API 文档元数据 | `docs-web/src/main/webapp/package.json` (`apidoc` 字段) |