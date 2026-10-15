# docs-mcp

MCP server（stdio，JSON-RPC 2.0）+ `zdedms` CLI for ZDEDMS.

让 AI agent（Claude Code / Claude Desktop / Cursor / 其他 MCP 客户端）能以
**用户自己的账号**访问 ZDEDMS：搜索、读取、下载、创建、上传文档。

- 一个 fat-jar（`docs-mcp-*-all.jar`），既是 CLI 也是 MCP server
- 鉴权：Casdoor OIDC Device Flow（推荐）或 username/password 兜底
- 配置文件：`~/.teedy-mcp/config.json`（权限 600）

> **当前状态**：**PR1（仅 Maven 骨架）**。CLI/MCP/鉴权在 PR2-PR6 落地，
> 文档先于代码撰写，方便早期 review 设计。

## 架构

```
┌──────────────────────┐      ┌──────────────────────────────────┐
│ Claude Code / Cursor │      │ 用户终端                          │
│ (MCP stdio client)   │      │                                  │
└──────────┬───────────┘      └──────────┬───────────────────────┘
           │ JSON-RPC over stdio         │ zdedms <subcommand>
           ▼                             ▼
┌──────────────────────────────────────────────────────────────┐
│  docs-mcp-*-all.jar  (single fat-jar)                         │
│                                                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐ │
│  │ JsonRpcServer│  │  CliMain     │  │  ConfigStore         │ │
│  │ (stdio loop) │  │  (Picocli)   │  │  ~/.teedy-mcp/...    │ │
│  └──────┬───────┘  └──────┬───────┘  └──────────┬───────────┘ │
│         │                 │                     │             │
│         └────────┬────────┴─────────────────────┘             │
│                  ▼                                            │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │  AuthProvider (Cookie / CasdoorJwt)                       │ │
│  └────────────────────────┬─────────────────────────────────┘ │
│                           ▼                                   │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │  ZdedmsApiClient (OkHttp 4.x)                            │ │
│  └────────────────────────┬─────────────────────────────────┘ │
└───────────────────────────┼───────────────────────────────────┘
                            │ HTTPS + Cookie / Bearer JWT
                            ▼
                   ┌────────────────────┐
                   │  ZDEDMS REST API   │
                   │  (docs-web)        │
                   └────────────────────┘
```

## CLI 命令树

通过 `java -jar docs-mcp-*-all.jar <subcommand>` 调用，名字为 `zdedms`（可通过
`alias` 设置）。所有命令支持 `--json` 全局开关切换 JSON 输出。

```
zdedms
├── serve                              启动 MCP server（stdio），等价于 McpServerMain
│
├── login [--server URL] [--casdoor]   首次登录，存 token 到 ~/.teedy-mcp/config.json
│   ├─ 默认：探测 /api/app，Casdoor 可用则走 Device Flow
│   └─ --casdoor：强制走 Device Flow
│
├── logout                             删除 ~/.teedy-mcp/config.json
│
├── whoami                             调 /api/user 输出当前登录用户
│
├── search <keyword>                   全文搜索文档
│   ├── --full                         搜索文件内容（OCR 文本），即 search[full]=1
│   ├── --limit N                      默认 20
│   ├── --offset N                     默认 0
│   ├── --tag TAG                      可重复，search[tag]=x
│   └── --shared                       search[shared]=1
│
├── doc
│   ├── list [--limit N] [--offset N]  列表（无 keyword 走简单搜索）
│   │   ├── --tag TAG
│   │   ├── --shared
│   │   └── --json
│   │
│   ├── get <id>                       文档详情（含文件列表）
│   │   └── --json
│   │
│   └── create --title TITLE           新建空文档
│       ├── --description DESC
│       ├── --tag TAG (可重复)
│       ├── --language LANG            ISO 639-3（eng/chi_sim/...）
│       └── --json
│
└── file
    ├── upload <doc-id> <file-path>    上传新文件到文档
    │   ├── --reminder-date MS         提醒日期（epoch ms）
    │   └── --reviewer USERNAME        提醒接收人
    │
    └── download <file-id> <out-path>  下载到本地
```

### 示例

```bash
# 首次登录（密码模式）
zdedms login --server https://docs.example.com
# → 交互输入 username / password（可选 TOTP code）

# 首次登录（Casdoor 模式）
zdedms login --server https://docs.example.com --casdoor
# → 打印 URL + code，浏览器打开登录

# 列前 10 篇
zdedms doc list --limit 10

# 全文搜索（含 OCR 文本）
zdedms search "发票" --full --limit 5

# 文档详情（JSON）
zdedms doc get 8a3b1c2d --json

# 创建文档
zdedms doc create --title "2026 年度审计报告" --tag 财务 --tag 审计

# 上传文件
zdedms file upload 8a3b1c2d ./report.pdf

# 下载
zdedms file download 9f8e7d6c ./report-copy.pdf
```

## MCP Tools（暴露给 AI agent）

MCP server（`zdedms serve`）以 stdio JSON-RPC 提供 6 个 tool。AI agent
（Claude Code 等）通过 `tools/list` 发现、`tools/call` 调用。

| Tool | 用途 | 对应 ZDEDMS API |
|---|---|---|
| `list_documents` | 列表/搜索文档 | `GET /api/document/list` |
| `get_document` | 文档详情 + 文件列表 | `GET /api/document/{id}` |
| `download_file` | 下载文件二进制（base64 编码返回） | `GET /api/file/{id}/data` |
| `search_by_keyword` | 全文搜索（含 OCR 文本） | `GET /api/document/list?search[full]=...` |
| `create_document` | 创建新文档 | `PUT /api/document` |
| `upload_file` | 上传文件到文档 | `PUT /api/file` |

### Tool input schemas（JSON Schema 风格）

```jsonc
// 1. list_documents
{
  "type": "object",
  "properties": {
    "limit":       { "type": "integer", "minimum": 1, "maximum": 200, "default": 20 },
    "offset":      { "type": "integer", "minimum": 0, "default": 0 },
    "tag":         { "type": "string" },
    "shared":      { "type": "boolean" },
    "files":       { "type": "boolean", "description": "include file summaries" }
  }
}

// 2. get_document
{ "type": "object", "properties": { "id": { "type": "string" } }, "required": ["id"] }

// 3. download_file  →  返回 content[0].data 是 base64 编码的文件二进制
{ "type": "object", "properties": { "file_id": { "type": "string" } }, "required": ["file_id"] }

// 4. search_by_keyword  (默认 search[full]=1，命中标题/描述/标签/OCR 文本)
{
  "type": "object",
  "properties": {
    "keyword": { "type": "string" },
    "limit":   { "type": "integer", "default": 20 },
    "offset":  { "type": "integer", "default": 0 }
  },
  "required": ["keyword"]
}

// 5. create_document
{
  "type": "object",
  "properties": {
    "title":       { "type": "string" },
    "description": { "type": "string" },
    "language":    { "type": "string" },
    "tags":        { "type": "array", "items": { "type": "string" } }
  },
  "required": ["title"]
}

// 6. upload_file  →  接收本地绝对路径，server 端读取后 multipart 上传
{
  "type": "object",
  "properties": {
    "document_id":    { "type": "string" },
    "file_path":      { "type": "string" },
    "reminder_date":  { "type": "integer", "description": "epoch ms; optional" },
    "reviewer":       { "type": "string", "description": "username; optional" }
  },
  "required": ["document_id", "file_path"]
}
```

## 鉴权流程

```
                          ┌─ casdoor_enabled=true
                          │   (POST /api/app 探测)
启动 docs-mcp              ▼
  │         ┌──────────────────────────────────────┐
  │         │ Casdoor OIDC Device Authorization     │
  │         │ Grant (RFC 8628)                      │
  │         │                                       │
  │         │ 1. POST /api/device-auth               │
  │         │    → { device_code, user_code,        │
  │         │       verification_uri, interval }     │
  │         │ 2. stderr 打印 URL + code             │
  │         │    自动 xdg-open / open / start        │
  │         │ 3. 轮询 /api/login/oauth/access_token  │
  │         │    grant_type=urn:ietf:params:oauth:   │
  │         │           grant-type:device_code       │
  │         │ 4. 拿到 id_token → 写 config.json     │
  │         └───────────────┬───────────────────────┘
  │                         ▼
  ▼              ┌─────────────────────┐
读 config.json   │ 后续请求            │
  │              │ Authorization:      │
  │              │   Bearer <id_token> │
  │              └──────────┬──────────┘
  ▼                         ▼
casdoor_jwt ─────────► 要求 ZDEDMS 启动时带
                       -Ddocs.jwt_authentication=true
                       （启用 docs-web-common/.../JwtBasedSecurityFilter）

                          └─ casdoor_enabled=false
                              ▼
                  ┌──────────────────────────────────────┐
                  │ username/password 兜底              │
                  │                                      │
                  │ POST /api/user/login                 │
                  │   remember=false  (⚠️ 强制不勾)    │
                  │   → 1 天 session cookie             │
                  │ docs-mcp 客户端叠加 24h 强过期      │
                  │ 存到 config.json + 写入登录时间戳   │
                  └──────────────────────────────────────┘
```

**关键约束**：

- ZDEDMS 默认鉴权是 Cookie（`TokenBasedSecurityFilter`）。Casdoor JWT 路径要求
  **服务端启动时带 `-Ddocs.jwt_authentication=true`**（系统属性启用
  `JwtBasedSecurityFilter`）。**零服务端代码改动**。
- 密码登录**不传 `remember=true`**（拿 1 天 session token），客户端再叠
  **24h 主动过期**：登录时写入 `login_at` 时间戳，调用前若 `now - login_at >= 24h`
  → 抛 `AuthExpiredException` 让用户重新 `zdedms login`。比 ZDEDMS 默认 1 天更严，
  防止 token 泄露后被长时间滥用。
- TOTP/2FA：登录遇 `ValidationCodeRequired` 错误码，CLI 提示重输 6 位 code。

## 安装

### 1. 构建

```bash
cd /path/to/teedy/docs
mvn -pl docs-mcp -am clean package -DskipTests
ls docs-mcp/target/docs-mcp-1.12-SNAPSHOT-all.jar
```

### 2. 准备配置

```bash
# 首次登录（密码模式，ZDEDMS 未配 Casdoor）
java -jar docs-mcp/target/docs-mcp-1.12-SNAPSHOT-all.jar login \
     --server https://docs.example.com

# 或 Casdoor Device Flow 模式
java -jar docs-mcp/target/docs-mcp-1.12-SNAPSHOT-all.jar login \
     --server https://docs.example.com --casdoor
```

配置文件 `~/.teedy-mcp/config.json`：

```json
{
  "server_url": "https://docs.example.com",
  "casdoor_server_url": "https://casdoor.example.com",
  "casdoor_client_id": "teedy-mcp",
  "auth_type": "casdoor_jwt",
  "token": "<id_token>",
  "token_expires_at": 1893456000,
  "refresh_token": null,
  "cookie_auth_token": null,
  "username": "alice"
}
```

### 3. 接入 Claude Code

```bash
# 替换路径为实际 jar 位置
claude mcp add --transport stdio teedy-mcp \
    --command java -jar /abs/path/to/docs-mcp-1.12-SNAPSHOT-all.jar serve
```

之后在 Claude Code 里说"列出最近 10 篇文档"，agent 会自动调
`list_documents` tool。

### 4. 接入 Claude Desktop

`~/Library/Application Support/Claude/claude_desktop_config.json` (macOS)
或 `%APPDATA%\Claude\claude_desktop_config.json` (Windows)：

```json
{
  "mcpServers": {
    "teedy-mcp": {
      "command": "java",
      "args": ["-jar", "/abs/path/to/docs-mcp-1.12-SNAPSHOT-all.jar", "serve"]
    }
  }
}
```

## stdio 协议边界

**这是最容易踩坑的地方**。MCP stdio 模式下：

- `System.out` **只**允许写 JSON-RPC 响应（**一行一个 JSON，无前缀**）
- 所有 log/进度必须走 `System.err`（slf4j-simple 1.7.36 已配 `<target>System.err</target>`）
- 任何 `System.out.println("debug...")` 都会破坏 JSON-RPC 流

## 兼容性

| ZDEDMS 版本 | docs-mcp 兼容 | 备注 |
|---|---|---|
| 1.12-SNAPSHOT+ | ✅ | 完整功能 |
| 1.11 (stable) | ⚠️ 部分 | 缺文件提醒 endpoints（`/api/file/reminders/...`），upload 工具的 reminder 参数会被忽略 |

需要 ZDEDMS 端启用 Casdoor JWT 时，启动命令加：
```bash
-Ddocs.jwt_authentication=true
```

## 不在范围

- 不动 ZDEDMS 服务端代码（用 `-Ddocs.jwt_authentication=true` 启用现有 JWT 过滤器）
- 不做 OS keychain 集成（明文 + `chmod 600`）
- 不做 MCP `resources` / `prompts`（仅实现 `tools`）
- 不做 Casdoor authorization-code 流（无浏览器场景不可用）
- 不做 SSE/HTTP transport（仅 stdio）
- 不进 Docker 镜像（独立 CLI 二进制）
- 不升级 ZDEDMS 到 Java 17

## Roadmap

| PR | 范围 | 状态 |
|---|---|---|
| 1 | Maven 骨架 + shade-plugin + 本 README 设计稿 | ✅ done |
| 2 | `McpConfig` / `ConfigStore` / `ZdedmsApiClient` (OkHttp) / 6 DTOs / `ApiException` | ⏳ |
| 3 | `CookieAuthProvider` + username/password 登录（含 TOTP） | ⏳ |
| 4 | `DeviceFlowClient` + `CasdoorJwtAuthProvider`（Device Flow） | ⏳ |
| 5 | MCP server（手写 JSON-RPC over stdio）+ 6 个 `McpTool` | ⏳ |
| 6 | 完整 Picocli CLI（9 个子命令） | ⏳ |
| 7 | 集成测试 + 发布脚本 | ⏳ |

设计文档：`/home/zd/.claude/plans/clever-sprouting-lemur.md`

## License

GPL v2 — 同 ZDEDMS 主项目。
