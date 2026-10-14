# docs-mcp

MCP server (stdio, JSON-RPC 2.0) and `zdedms` CLI for ZDEDMS.

Let your AI agent (Claude Code / Claude Desktop / Cursor / any MCP client) talk to
your ZDEDMS instance on behalf of your user account.

## Status

**PR1 (skeleton only).** Maven module wired up, fat-jar builds, CLI prints version
and exits. Real authentication, MCP tools, and subcommands arrive in PR2–PR6.

## Build

```bash
mvn -pl docs-mcp -am clean package -DskipTests
ls docs-mcp/target/docs-mcp-1.12-SNAPSHOT-all.jar
```

## Run (PR1 placeholder)

```bash
java -jar docs-mcp/target/docs-mcp-1.12-SNAPSHOT-all.jar --version
```

## Roadmap

| PR | 范围 |
|---|---|
| 1 | Maven skeleton + shade-plugin (this PR) |
| 2 | `McpConfig`, `ConfigStore`, `ZdedmsApiClient` (OkHttp), 6 DTOs |
| 3 | `CookieAuthProvider` + username/password login (fallback) |
| 4 | `DeviceFlowClient` + `CasdoorJwtAuthProvider` (Device Flow via Casdoor) |
| 5 | MCP server (handwritten JSON-RPC over stdio) + 6 tools |
| 6 | Full Picocli CLI (`login` / `serve` / `search` / `doc` / `file` / `logout` / `whoami`) |
| 7 | README + `claude mcp add` install instructions |

See the design plan: `/home/zd/.claude/plans/clever-sprouting-lemur.md`.

## License

GPL v2 — same as the rest of ZDEDMS.
