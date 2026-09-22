@echo off
chcp 65001 >nul
setlocal
:: My AI Gateway - backend start script
:: Always compiles the latest source before starting.
:: If the build fails, the startup aborts and the compiler errors are printed.
:: Default port 1399, database data/gateway.db

cd /d "%~dp0"

title My AI Gateway Backend

:: Default environment variables (may be overridden externally)
if "%MAG_PORT%"=="" set MAG_PORT=1399
if "%MAG_DB_PATH%"=="" set MAG_DB_PATH=data\gateway.db
if "%APP_JWT_SECRET%"=="" set APP_JWT_SECRET=my-ai-gateway-jwt-secret-key-2024-change-in-production

echo ====================================
echo   My AI Gateway - Backend
echo ====================================
echo   Port    : %MAG_PORT%
echo   DB      : %MAG_DB_PATH%
echo ====================================
echo.

:: ---- step 1: locate the Go toolchain ----
set "GO_CMD=go"
where go >nul 2>nul
if not errorlevel 1 goto :checkOld
for %%G in ("D:\tmp\go\go\bin\go.exe" "D:\tmp\gotool\go\bin\go.exe" "C:\Program Files\Go\bin\go.exe" "C:\Go\bin\go.exe" "%LOCALAPPDATA%\Programs\Go\bin\go.exe") do (
    if exist "%%~G" (
        set "GO_CMD=%%~G"
        goto :checkOld
    )
)
echo [ERROR] Go toolchain not found (checked PATH and common install dirs).
echo         Cannot compile the latest source, startup aborted.
goto :failed

:checkOld
:: ---- step 2: a running instance locks the build output ----
tasklist /FI "IMAGENAME eq mag-gateway.exe" 2>nul | find /I "mag-gateway.exe" >nul
if not errorlevel 1 (
    echo [ERROR] mag-gateway.exe is already running and locks the build output.
    echo         Stop the old instance first, then start again.
    goto :failed
)

:: ---- step 3: always build the latest source ----
echo [BUILD] Compiling latest source...
"%GO_CMD%" build -o mag-gateway.exe .
if errorlevel 1 (
    echo.
    echo [ERROR] Build failed, startup aborted. Reason: see the compiler output above.
    goto :failed
)
echo [BUILD] OK: mag-gateway.exe is up to date
echo [BUILD] Press Ctrl+C to stop the service
echo.

mag-gateway.exe
if errorlevel 1 (
    echo.
    echo [ERROR] Backend exited abnormally, press any key to close...
    pause >nul
    exit /b 1
)
exit /b 0

:failed
echo.
echo Press any key to close...
pause >nul
exit /b 1
