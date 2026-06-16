#!/usr/bin/env bash

set -Eeuo pipefail

APP_DIR="/opt/my-ai-api"
BRANCH="master"
REMOTE="origin"

echo "======================================"
echo "开始更新项目：${APP_DIR}"
echo "分支：${REMOTE}/${BRANCH}"
echo "======================================"

cd "${APP_DIR}"

echo ""
echo "1. 检查 Git 仓库状态..."
if [ ! -d ".git" ]; then
  echo "错误：当前目录不是 Git 仓库：${APP_DIR}"
  exit 1
fi

echo ""
echo "2. 获取远程最新代码..."
git fetch "${REMOTE}" "${BRANCH}"

echo ""
echo "3. 强制同步远程代码..."
git reset --hard "${REMOTE}/${BRANCH}"

# 默认不清理未跟踪文件，避免误删 .env.compose、data/ 等
# 如需删除未跟踪文件，取消下面这行注释
# git clean -fd

echo ""
echo "4. 检测 Docker Compose 命令..."

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
echo "5. 停止旧容器..."
${COMPOSE_CMD} down

echo ""
echo "6. 重新构建镜像..."
${COMPOSE_CMD} build --no-cache

echo ""
echo "7. 启动新容器..."
${COMPOSE_CMD} up -d

echo ""
echo "8. 查看容器状态..."
${COMPOSE_CMD} ps

echo ""
echo "======================================"
echo "更新完成"
echo "======================================"
