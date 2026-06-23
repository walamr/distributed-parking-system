@echo off
setlocal
cd /d "%~dp0.."
if not "%~1"=="" (
    set "ACTION=%~1"
    goto PROCESS_ACTION
)

:MENU
cls
echo =========================================================
echo RabbitMQ Node 1
echo =========================================================
echo 1. Restart node, wait for Nodes 2 and 3, then open console
echo 2. Restart node only (same safe startup)
echo 3. Stop container only
echo 9. Exit
set /p ACTION="Choose an action: "
:PROCESS_ACTION
if "%ACTION%"=="1" goto START
if "%ACTION%"=="2" goto CLEAN_AND_START
if "%ACTION%"=="3" goto CLEAN_ONLY
if "%ACTION%"=="9" exit /b 0
goto MENU

:CLEAN_AND_START
goto START
:CLEAN_ONLY
call :CLEAN
pause
goto MENU
:CLEAN
call :CHECK_DOCKER
if errorlevel 1 exit /b 1
echo Stopping RabbitMQ Node 1 container...
echo Persistent Docker volume rabbitmq1-data is kept. No RabbitMQ data is deleted.
docker compose --env-file network-ips.env -f docker-compose.rabbitmq1.yml down
exit /b %ERRORLEVEL%

:START
call :CHECK_DOCKER
if errorlevel 1 goto FAILED
echo Safe startup restarts the RabbitMQ Node 1 container without deleting the persistent volume.
call :CLEAN
if errorlevel 1 goto FAILED
echo Starting RabbitMQ Node 1 container...
docker compose --env-file network-ips.env -f docker-compose.rabbitmq1.yml up -d
if errorlevel 1 goto FAILED
call :WAIT_READY rabbitmq1
if errorlevel 1 goto FAILED
echo RabbitMQ Node 1 is ready.
echo.
echo =========================================================
echo Waiting for RabbitMQ Node 2 to connect to Node 1...
echo =========================================================
docker exec rabbitmq1 rabbitmqctl await_online_nodes 2 --timeout 300
if errorlevel 1 (
    echo ERROR: RabbitMQ Node 2 did not connect within 300 seconds.
    echo Start quick-start\05-run-rabbitmq2.bat on the RabbitMQ Node 2 PC.
    goto FAILED
)
echo SUCCESS: RabbitMQ Node 2 is connected to the cluster.
echo.
echo =========================================================
echo Waiting for RabbitMQ Node 3 to connect...
echo =========================================================
docker exec rabbitmq1 rabbitmqctl await_online_nodes 3 --timeout 300
if errorlevel 1 (
    echo ERROR: RabbitMQ Node 3 did not connect within 300 seconds.
    echo Start quick-start\06-run-rabbitmq3.bat on the RabbitMQ Node 3 PC.
    goto FAILED
)
echo SUCCESS: RabbitMQ Node 3 is connected to the cluster.
echo.
echo =========================================================
echo RabbitMQ cluster is complete. All three nodes are online.
echo =========================================================
docker exec rabbitmq1 rabbitmqctl cluster_status
echo Management console: https://localhost:15671
start "" https://localhost:15671
pause
goto MENU

:WAIT_READY
set /a ATTEMPTS=0
:WAIT_READY_LOOP
docker exec %~1 rabbitmq-diagnostics -q check_running >nul 2>&1
if not errorlevel 1 exit /b 0
set /a ATTEMPTS+=1
if %ATTEMPTS% GEQ 60 (
    echo ERROR: %~1 did not become ready within 120 seconds.
    docker logs --tail 40 %~1
    exit /b 1
)
echo Waiting for %~1... attempt %ATTEMPTS% of 60
timeout /t 2 /nobreak >nul
goto WAIT_READY_LOOP

:CHECK_DOCKER
docker info >nul 2>&1
if errorlevel 1 (
    echo ERROR: Docker Desktop Linux engine is not running or is not reachable.
    echo Start Docker Desktop and wait until it reports that the engine is running.
    exit /b 1
)
exit /b 0
:FAILED
echo RabbitMQ Node 1 operation failed.
pause
goto MENU
