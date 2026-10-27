# ZDEDMS 服务器部署文档

本文档描述如何把 ZDEDMS（Teedy 分支）部署到一台自有服务器上，并让 AI agent
（通过 `docs-mcp`）以**用户自己的账号**访问文档。

- 部署对象：`docs-web`（JAX-RS + AngularJS 前端，WAR）
- 数据库：PostgreSQL（生产）/ H2（仅测试）
- 外部依赖：Tesseract（OCR）、ffmpeg（视频缩略图）、mediainfo（视频元数据）
- 运行时：Java 11

> `docs-mcp`（MCP server + `zdedms` CLI）是**客户端**，运行在用户自己的机器上，
> 通过 HTTPS 连到本文档部署的这台服务器。它**不需要**部署到服务器上。
> 见 [第 12 节](#12-ai-agent--mcp-客户端接入)。

---

## 1. 架构总览

```
                    ┌───────────────────────────────┐
   浏览器 ────────► │  Nginx (443)                  │
   AI agent ──────► │  TLS 终止 / 反向代理          │
   (docs-mcp)       └───────────────┬───────────────┘
                                    │ http://127.0.0.1:8080
                    ┌───────────────▼───────────────┐
                    │  Jetty + docs-web.war         │
                    │  (docs.home=/data)            │
                    │  ├─ REST  /api/*              │
                    │  └─ 静态   /  (AngularJS dist)│
                    └──────┬───────────────┬────────┘
                           │               │
              ┌────────────▼──────┐  ┌─────▼──────────────────┐
              │ /data             │  │ PostgreSQL             │
              │  ├─ storage/      │  │  (docs / user / file / │
              │  │   <fileId>      │  │   tag / acl ...)      │
              │  │   <fileId>_web  │  └────────────────────────┘
              │  │   <fileId>_thumb│
              │  ├─ lucene/       │  ┌────────────────────────┐
              │  ├─ log/  (空)    │  │ Casdoor (SSO，可选)    │
              │  ├─ theme/        │  └────────────────────────┘
              │  └─ db/  (H2 only)│
              └───────────────────┘
```

**关键点**：`/data` 是**唯一的文件状态目录**（原文件 + OCR 文本 + 缩略图 + 索引）。
数据库备份和 `/data` 备份缺一不可。

---

## 2. 环境要求与容量规划

### 2.1 基础软件要求

| 项 | 要求 |
|---|---|
| 操作系统 | Linux（Debian/Ubuntu/CentOS 均可），其他平台理论可用但未验证 |
| JDK | 11（`maven.compiler.source/target = 11`） |
| Maven | 3.6+（仅构建时需要） |
| Node.js + Grunt | 仅构建前端时需要（`-Pprod` 会调 `npm install` + `grunt`） |
| PostgreSQL | 13+（生产必选） |
| Nginx | 建议，用于 TLS 终止 |
| Tesseract | OCR 必需，**同时要装对应语言包**（如 `tesseract-ocr-chi-sim`） |
| ffmpeg / mediainfo | 视频缩略图与元数据提取 |

系统依赖安装（Debian/Ubuntu）：

```bash
sudo apt-get update
sudo apt-get install -y openjdk-11-jdk maven nodejs npm tesseract-ocr \
    tesseract-ocr-chi-sim tesseract-ocr-chi-tra tesseract-ocr-deu \
    ffmpeg mediainfo postgresql nginx
```

> 缺少语言包时对应语言的 OCR 会失败，但不影响其它功能。

### 2.2 硬件配置（按并发数选型）

假设：中等文档量（10 万文件级），单文件平均 5–20 MB。

| 场景 | CPU | 内存 | 磁盘 | 说明 |
|---|---|---|---|---|
| 日常 10 并发（起配） | 4 核 | 8 GB | 100 GB SSD | 有余量，可平滑升配 |
| **峰值 100 并发（推荐）** | **8 核** | **16 GB** | **200 GB SSD** | 含 OCR 批处理 |
| 100 并发 + 大量扫描件 OCR | 8 核 | 24 GB | 500 GB SSD | 见 2.4 的 OCR 说明 |
| 100 并发 + 视频处理 | 16 核 | 16 GB | 1 TB SSD | ffmpeg 转码吃 CPU |

**磁盘必须用 SSD。** DMS 的负载特征是文件吞吐而非计算：每个文件在
`storage/` 下存 3 份（原文件 + `_web` + `_thumb`，见 `FileUtil.java:92-94`），
100 并发下载叠加索引写入会打满随机 IO，机械盘或网络盘会先于 CPU 成为瓶颈。

### 2.3 配置依据（来自本仓库代码）

**异步线程池 = CPU 核数，这是并发的主要排队点**

`AppContext.java:233`：

```java
int threadCount = Math.max(Runtime.getRuntime().availableProcessors() / 2, 2);
```

业务与邮件两个 event bus 各建一个池，**合计约等于 CPU 核数**。上传后触发的
OCR、缩略图、Lucene 索引、邮件全在这几个线程里排队。8 核 → 8 个异步线程，
100 个用户同时传文件时 OCR 任务在这 8 个线程上排队。

> 结论：**100 并发对本系统的主要压力不是"Web 请求扛不住"，而是"后台处理排队"。**

**内存尖峰在 OCR，不在 Web 请求**

Web 路径很轻：上传走 `Files.copy` + `CipherInputStream` 流式落盘（`FileUtil.java:192`），
不整块进堆。真正的堆峰值在 OCR：

- `PdfFormatHandler.java:66` — PDF 按 **300 DPI 灰度**逐页渲染，A4 单页约 8.7 MB 位图
- `FileUtil.java:64` — 再 `Scalr.resize` 到 3500px 长边
- `FileUtil.java:70` — 落成 TIFF 临时文件，交给**外部 `tesseract` 子进程**

即每个并发 OCR 任务在堆里持有两张大位图（约 20–40 MB），并 fork 出一个
tesseract 进程（再吃 100–300 MB）。8 核允许 8 个 OCR 并发 → 额外 2–3 GB。

**Lucene 索引不占堆**

`LuceneIndexingHandler.java:128` 虽有 `RAMDirectory` 分支，但初始迁移
`dbupdate-000-0.sql:37` 已写入：

```sql
insert into T_CONFIG(CFG_ID_C, CFG_VALUE_C) values('LUCENE_DIRECTORY_STORAGE', 'FILE');
```

且无后续迁移改动，生产实际走 `NIOFSDirectory`（落盘）。这让内存配置可以比
"典型 Java Web 应用"更保守。

> ⚠️ **不要手工把 `LUCENE_DIRECTORY_STORAGE` 改成 `RAM`**：该配置项没有管理界面
> 入口（只能改 `T_CONFIG` 表），改了索引会全进堆且难以回退。

**Jetty 线程池上限 = 200**

Dockerfile 用 Jetty 11.0.20 默认 `QueuedThreadPool`（最小 8 / **最大 200**）。
100 并发用户（每人 1 个长请求）落在 200 以内够用；但 100 人**同时下载**时，
每个流式响应都占一个线程和文件句柄，**单机不要超过 200 并发**，再高应多实例。

### 2.4 OCR 密集场景的扩容建议

若日常以扫描件入库（每个文件触发 OCR），8 核的 8 个异步线程会持续满载。
**最有效的扩容手段是把 OCR 拆到独立机器**——应用只负责接收与存储，
OCR 走独立 worker。否则只能靠加核数线性提价。

### 2.5 关键参数

**Docker 部署**（`docker-compose.yml`）：

```yaml
services:
  teedy-server:
    image: zdedms:local
    deploy:
      resources:
        limits:
          cpus: "8"
          memory: 16G
        reservations:
          memory: 8G
    environment:
      # 堆不要配太大：堆外 OCR 才是内存大头，堆给大会挤压 PG
      JAVA_OPTIONS: "-Dfile.encoding=UTF-8 -Xmx4g"
      # 默认 10，100 并发时是隐藏瓶颈（连接池排队点）
      DATABASE_POOL_SIZE: "20"
```

> 4 核 8 GB 机型用 `-Xmx2g`。`DATABASE_POOL_SIZE` 默认 10 配 100 并发用户会
> 成为排队点，务必调整。

**PostgreSQL**（与应用同机时）：

```
shared_buffers = 2GB
effective_cache_size = 8GB
work_mem = 8MB
max_connections = 100
```

> 数据库独立部署时，按 PG 机器的内存重新计算 `shared_buffers`。

---

## 3. 开发环境运行

本节是**开发/调试**用的启动方式，与生产部署（第 4、5 节）无关。

### 3.1 一条命令启动（连 PostgreSQL）

仓库自带脚本 `scripts/dev-run.sh`：加载仓库根 `.env` → 注入 `DATABASE_*` 环境变量
→ 设置 `docs.home` → 启动 Jetty。

```bash
# 第一次：准备 .env
cp .env.example .env
vi .env          # 填 DATABASE_URL / DATABASE_USER / DATABASE_PASSWORD

# 启动
./scripts/dev-run.sh
```

脚本实际执行的是：

```bash
cd docs-web
mvn jetty:run -Ddocs.home="$DOCS_HOME"
```

其中 `DOCS_HOME` 默认 `$HOME/.teedy-data`（可用环境变量覆盖）。
用 `$HOME` 而不是 `/var/docs` 是因为后者普通用户无写权限。

**访问地址**：

```
http://localhost:8080/docs-web/src/
```

- 端口 8080（jetty-maven-plugin 默认）
- contextPath `/docs-web`（`docs-web/pom.xml` 的 dev profile）
- 页面路径 `/src/` —— dev 模式**不跑 grunt**，直接提供 AngularJS 原始源码
  （`src/main/webapp/src/index.html`）；prod 则是 grunt 打包后的 `dist/`

> **为什么是 `/docs-web/src/`？** 前端用相对路径 `../api` 作为 API 基址
> （`app.js:459`）。dev 下页面在 `/docs-web/src/`，`../api` → `/docs-web/api`；
> prod 下页面在 `/`，`../api` → `/api`。

### 3.2 不连数据库的纯内存启动

不设 `DATABASE_URL` 时回落到 H2 内存库（`EMF.java:95-115`），**数据重启即丢**，
仅用于快速看界面：

```bash
cd docs-web
mvn jetty:run
# 访问 http://localhost:8080/docs-web/src/
```

### 3.3 dev 与 prod 的差异对照

| 项 | dev（`mvn jetty:run`） | prod（`-Pprod`） |
|---|---|---|
| 前端 | 原始源码 `src/`，路径 `/docs-web/src/` | grunt 打包 `dist/`，路径 `/` |
| 数据库 | H2 内存（默认）或 `.env` 里的 PG | PostgreSQL |
| `application.mode` | `dev` | 无（生产） |
| 打包 | 不产 WAR | 产 `docs-web/target/docs-web-*.war` |
| CORS | **开启**（`CorsFilter` 仅 dev 生效） | 关闭 |
| 端口 | 8080（jetty 插件默认） | 8080（容器内）/ 容器端口映射 |

> CORS 差异来自 `CorsFilter.java:26` —— 只有 `application.mode=dev` 才放行跨域。
> 前端联调时若出现跨域报错，确认跑的是 dev 模式。

### 3.4 后端改动后的重启

Jetty 插件会热加载 `docs-core` / `docs-web-common` 的 class 改动，但
**`docs-core` 改 Java 代码后建议重启**（事件总线、索引器等单例在启动时构建，
热替换可能出现状态不一致）。

### 3.5 常用的构建 / 测试命令

```bash
# 全量构建（跳过测试）
mvn clean -DskipTests install

# 只跑 docs-core 的测试
mvn -pl docs-core test -Dtest=DocumentDaoTest

# 跑单个测试方法
mvn -pl docs-core test -Dtest=DocumentDaoTest#testCreateDocument

# 生产构建（会跑 npm install + grunt，需要 grunt-cli）
mvn -Pprod -DskipTests clean install

# 只构建 docs-mcp
mvn -pl docs-mcp -am -DskipTests package
```

> 测试套件用 H2 内存库，不会连外部 PostgreSQL。Surefire 固定
> `forkCount=1, reuseForks=false`，**不要改这两个值**，否则共享内存状态的测试类
> 会互相污染。

### 3.6 用 dev 服务联调 docs-mcp

`docs-mcp` 指向 dev 服务时，**server URL 要带 contextPath**：

```bash
# 1) 构建 fat-jar
mvn -pl docs-mcp -am -DskipTests package

# 2) 登录 dev 服务（注意是 /docs-web/api，不是 /api）
java -jar docs-mcp/target/docs-mcp-*-all.jar login \
     --server http://localhost:8080/docs-web

# 3) 验证
java -jar docs-mcp/target/docs-mcp-*-all.jar whoami
```

> `docs-mcp` 目前仍在开发中（基础工具层已就绪，CLI 子命令与 MCP server 尚未落地），
> 上面两条命令要等 CLI 实现后才能实际执行。详见 `docs-mcp/README.md` 的路线图。

> dev 服务的 API 基址是 `http://localhost:8080/docs-web/api`（因为 contextPath 是
> `/docs-web`）；prod 是 `http://<host>/api`。传错会 404。
>
> dev 服务的 CORS 是放开的（仅 `application.mode=dev` 生效），
> 所以浏览器直连 dev 后端做调试不会撞跨域；但 `docs-mcp` 是服务端调用，
> 本来也不受 CORS 限制。

---

## 4. 方案 A：Docker Compose（推荐）

### 4.1 构建本仓库镜像

CI 发布的 `sismics/docs` 官方镜像**不包含**本分支的改动（Casdoor SSO、水印、
MCP 相关端点等），必须自己构建。

```bash
cd /path/to/teedy/docs

# 1) 先构建 WAR（prod profile 会跑前端打包，产物在 docs-web/target）
mvn -Pprod -DskipTests clean install

# 2) 构建镜像
docker build -t zdedms:local .
```

> `Dockerfile` 期望 `docs-web/target/docs-web-*.war` 存在，所以**必须先构建再 build**。
> 本地若没有 `grunt` 命令，`-Pprod` 会失败：
> `npm install -g grunt-cli` 后重试（CI 环境同理）。

### 4.2 配置环境变量

```bash
cp .env.example .env
chmod 600 .env
vi .env
```

`.env` 关键项：

```ini
# 对外访问地址（生成邮件链接、SSO 回调都依赖它）
DOCS_BASE_URL=https://docs.example.com

# PostgreSQL
DATABASE_URL=jdbc:postgresql://teedy-db:5432/teedy
DATABASE_USER=teedy
DATABASE_PASSWORD=<强密码>
DATABASE_POOL_SIZE=10

# 初始 admin（仅首次启动生效）
DOCS_ADMIN_EMAIL_INIT=admin@example.com
DOCS_ADMIN_PASSWORD_INIT=$$2a$$05$$<bcrypt-hash>
```

> ⚠️ **主机名必须是 `teedy-db`，不是 `localhost`。**
> `.env.example` 里默认写的是 `jdbc:postgresql://localhost:5432/teedy`，
> 那是给 `scripts/dev-run.sh`（在宿主机上直连 PG）用的。
> 用 `docker compose` 部署时，容器内的 `localhost` 指的是应用容器自己，
> 连不上数据库。照抄 `.env.example` 会出现连不上 PG 的启动失败。
> 另外 `DATABASE_URL` 里的 `teedy` 是**数据库名**，与 `DATABASE_USER`（数据库账号）
> 是两回事，可以不同。
>
> 两种模式下的主机名对照：
>
> | 部署方式 | `DATABASE_URL` 主机名 |
> |---|---|
> | `docker compose`（第 4 节） | `teedy-db`（compose 服务名） |
> | 手工部署（第 5 节）/ `scripts/dev-run.sh`（第 3 节） | `localhost` 或 `127.0.0.1` |

**生成 bcrypt 哈希**（`$` 必须转义成 `$$`，见 `.env.example` 注释）：

```bash
python3 -c "import bcrypt; print(bcrypt.hashpw(b'你的密码', bcrypt.gensalt(5)).decode())"
# 输出 $2a$05$xxxx... → 写入 .env 时把每个 $ 换成 $$
```

> 未设置 `DOCS_ADMIN_PASSWORD_INIT` 时，admin 初始密码是 `admin`。
> **上线前务必改掉。** 详见 [第 13 节 安全清单](#13-安全清单)。

### 4.3 指向本地镜像

仓库自带的 `docker-compose.yml` 默认拉取官方镜像，改为本地构建的镜像：

```yaml
services:
  teedy-server:
    image: zdedms:local          # ← 改这里
    ...
```

### 4.4 启动

```bash
docker compose up -d
docker compose logs -f teedy-server
```

启动后检查：

```bash
curl -s https://docs.example.com/api/app | jq .
```

`/api/app` 是**公开**端点（不需要登录），返回版本、文档数、OCR/Casdoor 开关，
适合做健康检查和探活。

### 4.5 目录与持久化

仓库的 compose 已经配好：

| 容器内 | 宿主机 | 内容 |
|---|---|---|
| `/data` | `./docs/data` | 原文件、缩略图、Lucene 索引、日志 |
| `/var/lib/postgresql/data` | `./docs/db` | PostgreSQL 数据目录 |

`./docs/` 已在 `.gitignore` 中（`docs/*` 排除 `.gitkeep`），不会被误提交。

---

## 5. 方案 B：手工部署（WAR + Servlet 容器）

适合已有 Tomcat / Jetty 运维体系的场景。

### 5.1 构建

```bash
mvn -Pprod -DskipTests clean install
# 产物：docs-web/target/docs-web-<version>.war
```

### 5.2 部署到 Tomcat

```bash
sudo cp docs-web/target/docs-web-*.war /opt/tomcat/webapps/docs.war
sudo systemctl restart tomcat
```

### 5.3 关键 JVM 参数

| 参数 | 值 | 说明 |
|---|---|---|
| `-Ddocs.home` | `/var/docs` | 数据目录，**不设置会落到 `/var/docs`（Linux 默认）** |
| `-Ddocs.jwt_authentication` | `true` | 启用 Casdoor JWT 鉴权（AI agent 走这条路径时**必须**开） |
| `-Dfile.encoding` | `UTF-8` | 中文文件名必需 |
| `-Xmx` | `2g` | 取决于文档量；OCR 批处理时调大 |

Tomcat 示例（`/etc/default/tomcat9` 或 systemd `Environment`）：

```ini
CATALINA_OPTS="-Dfile.encoding=UTF-8 -Ddocs.home=/var/docs -Ddocs.jwt_authentication=true -Xmx2g"
```

### 5.4 环境变量

Tomcat 下用 systemd `Environment=` 或 `setenv.sh` 注入（`CATALINA_ENV` 变量）：

```bash
# /opt/tomcat/bin/setenv.sh
export DATABASE_URL="jdbc:postgresql://127.0.0.1:5432/teedy"
export DATABASE_USER="teedy"
export DATABASE_PASSWORD="<密码>"
export DATABASE_POOL_SIZE="10"
export DOCS_BASE_URL="https://docs.example.com"
```

> **注意**：`docs.home` 是**系统属性**（`-D`），`DATABASE_*` / `DOCS_*` 是**环境变量**，
> 两者渠道不同，不要混。

### 5.5 目录权限

```bash
sudo mkdir -p /var/docs
sudo chown -R tomcat:tomcat /var/docs
```

---

## 6. 反向代理与 HTTPS（Nginx）

### 6.1 配置

```nginx
server {
    listen 80;
    server_name docs.example.com;
    # ACME 质询走明文，其它一律跳 HTTPS
    location /.well-known/acme-challenge/ { root /var/www/certbot; }
    location / { return 301 https://$host$request_uri; }
}

server {
    listen 443 ssl http2;
    server_name docs.example.com;

    ssl_certificate     /etc/letsencrypt/live/docs.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/docs.example.com/privkey.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;

    # 上传大文件必须放开
    client_max_body_size 512m;

    # 需要保留原始 Host / 协议，供 SSO 回调与审计 IP 使用
    proxy_set_header Host              $host;
    proxy_set_header X-Real-IP         $remote_addr;
    proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;

    location / {
        proxy_pass         http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header   Upgrade    $http_upgrade;
        proxy_set_header   Connection "upgrade";
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
    }
}
```

启用并重载：

```bash
sudo nginx -t && sudo systemctl reload nginx
```

### 6.2 关于 `X-Forwarded-Proto`（重要）

Casdoor 回调 URI 由**请求本身的 scheme/host/port** 推导
（`AuthTokenUtil.callbackUri`）。TLS 在 Nginx 终止时，Jetty 看到的 scheme 是 `http`，
推导出的回调地址就会变成 `http://docs.example.com/api/casdoor/callback`，
与 Casdoor 中登记的 `https://...` 不匹配，登录会失败。

两个处理方式：

1. **直连验证**：`curl -sI 'https://docs.example.com/api/casdoor/login?return_to=/' | grep -i location`
   看 `Location` 里的 `redirect_uri` 是 `http` 还是 `https`。
2. **让 Jetty 识别转发头**：在 Jetty 的 `jetty.xml` 里开启 forwarded 支持
   （`org.eclipse.jetty.server.ForwardedRequestCustomizer`），
   或改用方案 B 的 Tomcat（`RemoteIpValve`）。

第 1 步是**必做**的验证项，不要跳过。

### 6.3 上传体积

`client_max_body_size` 必须 ≥ 业务允许的单文件上限，否则大文件上传会得到
`413 Request Entity Too Large`，且客户端只看到一个笼统的失败。

---

## 7. 环境变量完整参考

来源：`docs-core/.../constant/Constants.java`、`ConfigType.java`。

### 通用

| 变量 | 默认 | 说明 |
|---|---|---|
| `DOCS_BASE_URL` | 无 | 对外访问地址。**必填**，否则邮件里的链接、SSO 回调都是错的 |
| `DOCS_DEFAULT_LANGUAGE` | `en` | 默认语言，如 `chi_sim` |
| `DOCS_GLOBAL_QUOTA` | 1 GB | 全局存储配额（字节）；`0` 或不设 = 不限 |
| `DOCS_BCRYPT_WORK` | `10` | bcrypt 代价因子，范围 4–31。调高会显著拖慢登录与建号 |

### 管理员

| 变量 | 说明 |
|---|---|
| `DOCS_ADMIN_EMAIL_INIT` | 初始 admin 邮箱。**仅当 DB 中 admin 仍是默认值时生效** |
| `DOCS_ADMIN_PASSWORD_INIT` | 初始 admin 密码（bcrypt 哈希，`$`→`$$`）。**仅首次启动生效** |

> 这两个是"初始化"语义，不是"每次启动重置"。admin 改过密码后，再改环境变量不会覆盖。

### 数据库

| 变量 | 说明 |
|---|---|
| `DATABASE_URL` | JDBC 连接串。**不设则回落到嵌入式 H2（仅测试用）** |
| `DATABASE_USER` / `DATABASE_PASSWORD` | 数据库账号密码 |
| `DATABASE_POOL_SIZE` | 连接池大小，默认 10 |

> `DATABASE_URL` 一旦设置就**强制走 PostgreSQL**，忽略 classpath 里的 `hibernate.properties`。

### 邮件（SMTP）

| 变量 | 说明 |
|---|---|
| `DOCS_SMTP_HOSTNAME` / `DOCS_SMTP_PORT` | SMTP 主机与端口 |
| `DOCS_SMTP_USERNAME` / `DOCS_SMTP_PASSWORD` | SMTP 凭据 |
| `DOCS_DEFAULT_LANGUAGE` | 邮件语言 |

不配 SMTP 时，密码找回邮件、工作流通知邮件发不出去（功能本身不报错）。

### Casdoor SSO

| 变量 | 说明 |
|---|---|
| `DOCS_CASDOOR_ENABLED` | `true` 启用 SSO |
| `DOCS_CASDOOR_SERVER_URL` | Casdoor 地址，如 `https://casdoor.example.com` |
| `DOCS_CASDOOR_CLIENT_ID` / `DOCS_CASDOOR_CLIENT_SECRET` | 应用凭据 |
| `DOCS_CASDOOR_ORGANIZATION` / `DOCS_CASDOOR_APPLICATION` | 组织名 / 应用名 |

> 这些变量只在 **DB 中尚无对应配置项**时写入（`AppContext.bootstrapEnvConfig`）。
> 管理员在设置页改过之后，DB 值优先，环境变量不再覆盖。**改环境变量后要确认 DB 里
> 没有残留旧值**，否则改不动。

---

## 8. 首次初始化

1. 打开 `https://docs.example.com`，用 admin 账号登录。
2. **立刻改密码**（设置 → 个人资料），并开启 TOTP 双因子。
3. 关闭游客登录（设置 → 应用 → 游客登录）。
4. 按需配置 SMTP、OCR 语言、存储配额。
5. 如需 Casdoor SSO，见下一节。

### 8.1 Casdoor SSO 接入

**Casdoor 侧**：

1. 新建 Organization 和 Application。
2. Application 的 Grant types 勾选 `Device Code`（`docs-mcp` 的设备码流程需要）
   和 `Authorization Code`（浏览器 SSO 需要）。
3. Redirect URL 填 `https://docs.example.com/api/casdoor/callback`。
4. 若 Casdoor 多副本部署，需在 `conf/app.conf` 配 `redisEndpoint`，
   否则设备码状态只存在内存里，轮询会随机失败。

**ZDEDMS 侧**（二选一）：

- 环境变量方式（见第 7 节，适合容器部署、首启即用）；
- 管理界面方式：设置 → Casdoor SSO，填 server_url / client_id / client_secret /
  organization / application，勾选启用。

**验证**：

```bash
curl -s https://docs.example.com/api/app | jq .casdoor_enabled
# 应为 true
```

---

## 9. 数据库与迁移

- Schema 变更通过 `docs-core/src/main/resources/db/update/dbupdate-XXX-N.sql` 增量发布。
- 启动时 `DbOpenHelper` 比对 `T_CONFIG.DB_VERSION` 与 `config.properties` 的 `db.version`，
  **只在落后时执行迁移**，已是最新则直接跳过。**重启不会丢数据，也不会重复跑迁移。**
- 迁移脚本每条语句必须**单行**（`DbOpenHelper.executeScript()` 按行切分执行）。
- 新增 dbupdate 时要同步三处 `db.version`：
  - `docs-core/src/main/resources/config.properties`
  - `docs-web/src/dev/resources/config.properties`
  - `docs-web/src/prod/resources/config.properties`

**禁止**执行 `DROP DATABASE teedy` 或 `rm -rf ~/.teedy-data/db`（后者是 H2 文件库位置）。

---

## 10. 备份与恢复

需要备份**两样东西**：

| 内容 | 位置 | 频率建议 |
|---|---|---|
| PostgreSQL 数据 | `./docs/db`（或 PG 自己的备份） | 每日 |
| `/data/storage` | `./docs/data/storage` | 每日（或用快照） |

```bash
# ---- PG 逻辑备份 ----
docker compose exec -T teedy-db pg_dump -U teedy teedy | gzip > teedy-$(date +%F).sql.gz

# ---- 文件备份（tar 保留权限） ----
tar czf data-$(date +%F).tar.gz docs/data/storage docs/data/theme
```

**不需要**备份的：

- `docs/data/lucene` —— 全文索引，可由 `RebuildIndexAsyncEvent` 重建。
- `docs/data/db` —— 仅在用 H2 时才有内容；生产用 PG 时该目录基本为空。

**恢复**：

```bash
gunzip < teedy-2026-10-27.sql.gz | docker compose exec -T teedy-db psql -U teedy teedy
tar xzf data-2026-10-27.tar.gz -C /path/to/teedy/docs
docker compose restart teedy-server
```

> 恢复顺序：先停应用 → 恢复 PG → 恢复文件 → 起应用。
> 恢复后建议触发一次索引重建。

---

## 11. 升级流程

```bash
git pull
mvn -Pprod -DskipTests clean install
docker build -t zdedms:local .
docker compose up -d        # 容器重建
```

升级前先做一次完整备份（第 10 节）。数据库迁移在启动时自动执行，**不需要**手工跑 SQL。

回滚：把镜像 tag 换回旧版本重建即可；但**数据库迁移通常不可逆**，
所以旧版本镜像未必兼容新 schema —— 回滚前先恢复 PG 备份。

---

## 12. AI agent / MCP 客户端接入

`docs-mcp` 是**客户端**，装在用户机器上（或 AI agent 所在机器），
通过 HTTPS 访问本服务器。服务器侧需要满足：

1. **API 可达**：`https://docs.example.com/api/app` 返回 200。
2. **证书可信**：自签证书需在客户端 JVM 导入，否则 OkHttp 握手失败。
3. **鉴权方式**：
   - 密码登录 → 无需额外配置（走 `auth_token` cookie）。
   - Casdoor → 服务器端**必须**开启 JWT 过滤器
     （`-Ddocs.jwt_authentication=true`），否则 `Authorization: Bearer` 会被忽略。

客户端侧（详见 `docs-mcp/README.md`）：

```bash
# 构建 fat-jar
mvn -pl docs-mcp -am -DskipTests package
# 产物：docs-mcp/target/docs-mcp-<version>-all.jar

# 登录（密码）
java -jar docs-mcp-*-all.jar login --server https://docs.example.com

# 登录（Casdoor 设备码）
java -jar docs-mcp-*-all.jar login --server https://docs.example.com --casdoor
```

注册到 Claude Code：

```bash
claude mcp add --transport stdio zdedms-mcp \
  --command java -jar /abs/path/to/docs-mcp-*-all.jar serve
```

**登录态 24 小时有效**：登录时服务端下发 1 天有效的 session cookie，客户端另外记录
`login_at` 并在满 24h 后拒绝使用，提示重新 `login`。

---

## 13. 安全清单

上线前逐项确认：

- [ ] admin 初始密码已改（不再是 `admin`）
- [ ] `DOCS_ADMIN_PASSWORD_INIT` 用的是 bcrypt 哈希，且 `$` 已转义
- [ ] `.env` 权限 `600`，且在 `.gitignore` 中
- [ ] PostgreSQL 密码足够强，且**不对外暴露 5432**
- [ ] 强制 HTTPS，HTTP 301 跳转
- [ ] 游客登录已关闭
- [ ] 开启 TOTP 双因子（至少 admin）
- [ ] `client_max_body_size` 已按业务调整
- [ ] `/data` 目录权限收敛到服务账号
- [ ] 定期备份 PG + `/data/storage`，且**演练过恢复**
- [ ] 若用 Casdoor：回调 URI 为 `https://`（见 6.2 的验证步骤）
- [ ] 若 AI agent 走 Casdoor：`-Ddocs.jwt_authentication=true` 已生效
- [ ] `docs-mcp` 客户端的 `~/.teedy-mcp/config.json` 权限为 `600`（客户端侧，非服务器）

---

## 14. 排障

| 症状 | 排查 |
|---|---|
| 启动即退出，日志无 DB 信息 | `DATABASE_URL` 没设 → 回落到 H2；确认 PG 可连、账号密码正确 |
| 报 `db.version` 相关错误 | 三处 `config.properties` 的 `db.version` 不一致 |
| 页面空白 / 静态资源 404 | 前端没打包：确认构建时跑了 `-Pprod` 且 `docs-web/target/docs-web-*/dist` 存在 |
| OCR 没结果 | 缺 `tesseract-ocr-<lang>` 语言包；或 `OCR_ENABLED` 被关 |
| 视频缩略图失败 | 缺 `ffmpeg` / `mediainfo` |
| 上传 413 | Nginx `client_max_body_size` 太小 |
| SSO 登录后回跳失败 | `redirect_uri` 是 `http` 而非 `https` → 见 6.2 |
| Casdoor 设备码轮询随机失败 | Casdoor 多副本未配 `redisEndpoint` |
| AI agent 报 401 | Casdoor 模式缺 `-Ddocs.jwt_authentication=true`；或登录态已过 24h |
| AI agent 报 SSL 错误 | 客户端未信任服务器证书 |
| 重启后数据丢了 | 误删了 `./docs/db`（PG 数据目录）。**恢复备份，不要 `DROP DATABASE`** |
| 全文搜索搜不到内容 | 索引未重建 → 调 `POST /api/app/batch/reindex`（admin 权限）触发 `RebuildIndexAsyncEvent` |

查看日志：

```bash
docker compose logs -f teedy-server      # 容器 stdout/stderr
```

> `/data/log` 目录会被创建，但当前 `log4j.properties` 只配了 CONSOLE + MEMORY 两个
> appender，**没有文件 appender**，所以该目录下不会有日志文件。需要落盘时自行加
> `FileAppender`，或用 journald / docker 日志采集。

---

## 15. 相关文件

| 关注点 | 路径 |
|---|---|
| 容器编排 | `docker-compose.yml` / `.env.example` |
| 镜像构建 | `Dockerfile` / `docs.xml`（设置 `docs.home=/data`） |
| 鉴权过滤器 | `docs-web-common/.../util/filter/{Token,Jwt,Header}BasedSecurityFilter.java` |
| 环境变量常量 | `docs-core/.../constant/Constants.java` |
| 全局配置枚举 | `docs-core/.../constant/ConfigType.java` |
| 数据目录结构 | `docs-core/.../util/DirectoryUtil.java` |
| 数据库选择逻辑 | `docs-core/.../util/jpa/EMF.java` |
| 迁移执行 | `docs-core/.../util/jpa/DbOpenHelper.java` + `docs-core/src/main/resources/db/update/` |
| Casdoor 集成 | `docs-core/.../service/CasdoorService.java` + `docs-web/.../rest/resource/CasdoorResource.java` |
| MCP 客户端 | `docs-mcp/README.md` |
