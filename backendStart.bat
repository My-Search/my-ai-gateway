@echo off
chcp 65001 >nul
:: My AI Gateway - backend start script
:: Default port 1399, database data/gateway.db

title My AI Gateway Backend

:: Default environment variables (may be overridden externally)
if "%MAG_PORT%"=="" set MAG_PORT=1399
if "%MAG_DB_PATH%"=="" set MAG_DB_PATH=data\gateway.db
if "%APP_JWT_SECRET%"=="" set APP_JWT_SECRET=my-ai-gateway-jwt-secret-key-2024-change-in-production

echo ==========================================
echo   My AI Gateway - Backend
echo ==========================================
echo   Port    : %MAG_PORT%
echo   DB      : %MAG_DB_PATH%
echo ==========================================
echo   Press Ctrl+C to stop
echo.

:: Prefer the prebuilt binary; fall back to go run when Go is installed
if exist mag-gateway.exe (
    mag-gateway.exe
) else (
    go run main.go
)

:: If it exits abnormally, pause to show the error
if errorlevel 1 (
    echo.
    echo [ERROR] Backend exited abnormally, press any key to close...
    pause >nul
)
