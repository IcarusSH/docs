#!/usr/bin/env bash
#
# 加载仓库根 .env 后启动 Teedy 开发服务器（prod profile，对接 PostgreSQL）
# 用法：./scripts/dev-run.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

ENV_FILE="$ROOT_DIR/.env"
if [[ ! -f "$ENV_FILE" ]]; then
  echo "[dev-run] 找不到 $ENV_FILE"
  echo "[dev-run] 第一次使用请先：cp .env.example .env 并填实际值"
  exit 1
fi

# set -a 让 source 后所有变量自动 export，set +a 关闭
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

echo "[dev-run] 已加载 $(grep -cE '^[A-Z_]+=' "$ENV_FILE" 2>/dev/null || echo 0) 个环境变量"
echo "[dev-run] DATABASE_URL=${DATABASE_URL:-<未设置，将使用 H2>}"

# docs.home 决定数据目录（Lucene 索引、上传文件、日志等）。
# 默认 /var/docs，普通用户没权限写，所以改成 $HOME 下的目录。
DOCS_HOME="${DOCS_HOME:-$HOME/.teedy-data}"
mkdir -p "$DOCS_HOME"
echo "[dev-run] 数据目录: $DOCS_HOME"
echo "[dev-run] 启动 jetty（dev profile，跳过 grunt 打包）..."

cd "$ROOT_DIR/docs-web"
# -Ddocs.home 会被 DirectoryUtil 优先读；dev profile 不跑 grunt，
# 直接用 src/main/webapp 原始前端文件。
exec mvn jetty:run -Ddocs.home="$DOCS_HOME" "$@"
