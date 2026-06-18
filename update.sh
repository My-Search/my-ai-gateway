#!/usr/bin/env bash

set -Eeuo pipefail

BRANCH="master"
REMOTE="origin"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "${SCRIPT_DIR}"

echo "======================================"
echo "开始更新项目：${SCRIPT_DIR}"
echo "分支：${REMOTE}/${BRANCH}"
echo "======================================"

echo ""
echo "1. 静默清理 Docker 无用资源..."
docker system prune -af 2>/dev/null || echo "  (清理完成或 Docker 暂不可用)"

echo ""
echo "2. 检查 Git 仓库状态..."
if [ ! -d ".git" ]; then
  echo "错误：当前目录不是 Git 仓库：${SCRIPT_DIR}"
  exit 1
fi

echo ""
echo "3. 获取远程最新代码..."
git fetch "${REMOTE}" "${BRANCH}"

echo ""
echo "4. 强制同步远程代码..."
git reset --hard "${REMOTE}/${BRANCH}"

# 默认不清理未跟踪文件，避免误删 .env.compose、data/ 等
# 如需删除未跟踪文件，取消下面这行注释
# git clean -fd

echo ""
echo "5. 检测 Docker Compose 命令..."

if docker compose version >/dev/null 2>&1; then
  COMPOSE_CMD="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE_CMD="docker-compose"
else
  echo "错误：未找到 docker compose 或 docker-compose"
  exit 1
fi

echo "使用命令：${COMPOSE_CMD}"

echo ""
echo "6. 重新构建镜像..."
${COMPOSE_CMD} build --no-cache

echo ""
echo "7. 停止旧容器..."
${COMPOSE_CMD} down

echo ""
echo "8. 启动新容器..."
${COMPOSE_CMD} up -d

echo ""
echo "9. 查看容器状态..."
${COMPOSE_CMD} ps

echo ""
echo "======================================"
echo "更新完成"
echo "======================================"
