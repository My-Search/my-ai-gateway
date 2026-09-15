@echo off
chcp 65001 >nul
:: My AI Gateway - 后端启动脚本
:: 默认端口 1399，数据库 data/gateway.db

title My AI Gateway Backend

:: 检查 go 是否安装
go version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Go 未安装或不在 PATH 中，请先安装 Go ^(>=1.23^)
    pause
    exit /b 1
)

:: 默认环境变量（可在外部 .env 文件中覆盖）
if "%MAG_PORT%"=="" set MAG_PORT=1399
if "%MAG_DB_PATH%"=="" set MAG_DB_PATH=data\gateway.db
if "%APP_JWT_SECRET%"=="" set APP_JWT_SECRET=my-ai-gateway-jwt-secret-key-2024-change-in-production

echo ==========================================
echo   My AI Gateway - Backend
echo ==========================================
echo   Port    : %MAG_PORT%
echo   DB      : %MAG_DB_PATH%
echo   Mode    : Release
echo ==========================================
echo   Press Ctrl+C to stop
echo.

:: 启动服务
go run main.go

:: 如果异常退出，暂停显示错误
if errorlevel 1 (
    echo.
    echo [ERROR] 服务异常退出，按任意键关闭...
    pause >nul
)
