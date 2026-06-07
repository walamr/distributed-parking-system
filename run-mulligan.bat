@echo off
setlocal

echo ==========================================
echo    Mulligan Parking - Distributed Boot
echo ==========================================

REM 1. Start Docker Infrastructure
echo [1/3] Starting Backend Services in Docker...
docker compose up -d

if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Failed to start Docker containers. Please ensure Docker Desktop is running.
    pause
    exit /b %ERRORLEVEL%
)

REM 2. Wait and Setup Cluster Configurations
echo [2/3] Setting up RabbitMQ and MongoDB Clusters...
powershell -ExecutionPolicy Bypass -File .\scripts\setup-rabbitmq-cluster.ps1
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] RabbitMQ cluster configuration failed.
    pause
    exit /b %ERRORLEVEL%
)

powershell -ExecutionPolicy Bypass -File .\docker\mongodb\init-rs.ps1
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] MongoDB Replica Set initialization failed.
    pause
    exit /b %ERRORLEVEL%
)

REM 3. Launch UI Gateway
echo [3/3] Launching Unified Gateway (Local UI)...
echo [INFO] Connecting to http://localhost:8082
call .\gradlew.bat :mulligan-app:run

if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] UI Application failed to start.
    pause
)

endlocal
