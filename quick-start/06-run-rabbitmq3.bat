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
echo RabbitMQ Node 3
echo =========================================================
echo 1. Restart node, join Node 1, and open console
echo 2. Restart node and join (same safe startup)
echo 3. Stop container only
echo 4. Recover this node - reset and rejoin the cluster
echo 9. Exit
set /p ACTION="Choose an action: "
:PROCESS_ACTION
if "%ACTION%"=="1" goto START
if "%ACTION%"=="2" goto CLEAN_AND_START
if "%ACTION%"=="3" goto CLEAN_ONLY
if "%ACTION%"=="4" goto RECOVER
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
echo Stopping RabbitMQ Node 3 container...
echo Persistent Docker volume rabbitmq3-data is kept. No RabbitMQ data is deleted.
docker compose --env-file network-ips.env -f docker-compose.rabbitmq3.yml down
exit /b %ERRORLEVEL%

:START
call :CHECK_DOCKER
if errorlevel 1 goto FAILED
echo Safe startup restarts the RabbitMQ Node 3 container without deleting the persistent volume.
call :CLEAN
if errorlevel 1 goto FAILED
echo Starting RabbitMQ Node 3 container...
docker compose --env-file network-ips.env -f docker-compose.rabbitmq3.yml up -d
if errorlevel 1 goto FAILED
call :WAIT_READY rabbitmq3
if errorlevel 1 goto FAILED

docker exec rabbitmq3 rabbitmqctl cluster_status 2>nul | findstr /c:"rabbit@rabbitmq1" >nul
if not errorlevel 1 goto ALREADY_JOINED
echo Joining RabbitMQ Node 3 to rabbit@rabbitmq1...
docker exec rabbitmq3 rabbitmqctl stop_app || goto JOIN_FAILED
docker exec rabbitmq3 rabbitmqctl reset || goto JOIN_FAILED
docker exec rabbitmq3 rabbitmqctl join_cluster rabbit@rabbitmq1 || goto JOIN_FAILED
docker exec rabbitmq3 rabbitmqctl start_app || goto JOIN_FAILED
goto JOINED
:ALREADY_JOINED
echo RabbitMQ Node 3 is already joined to Node 1; reset was skipped.
:JOINED
echo RabbitMQ Node 3 is ready.
echo Management console: https://localhost:15671
start "" https://localhost:15671
pause
goto MENU

:JOIN_FAILED
echo.
echo =========================================================
echo ERROR: Could not join Node 1.
echo If you saw "inconsistent_cluster", this node and the cluster
echo disagree about membership (this node likely came back empty).
echo Press 4 (Recover) from the menu to reset and rejoin cleanly.
echo =========================================================
pause
goto MENU

:RECOVER
call :CHECK_DOCKER
if errorlevel 1 goto FAILED
cls
echo =========================================================
echo RECOVER RabbitMQ Node 3
echo =========================================================
echo This RESETS this node and rejoins it to the existing cluster.
echo Use ONLY if this node came back EMPTY/isolated and at least
echo one of Nodes 1 or 2 is running and healthy.
echo.
echo The local data on this node is discarded and re-synced from
echo the cluster. NEVER run this on a healthy node.
echo =========================================================
set /p CONFIRM="Type YES to continue: "
if /I not "%CONFIRM%"=="YES" goto MENU
echo Ensuring container is running...
docker compose --env-file network-ips.env -f docker-compose.rabbitmq3.yml up -d
if errorlevel 1 goto FAILED
call :WAIT_READY rabbitmq3
if errorlevel 1 goto FAILED
echo Stopping local RabbitMQ app so the cluster sees this node as down...
docker exec rabbitmq3 rabbitmqctl stop_app
timeout /t 5 /nobreak >nul
call :TRY_RECOVER rabbitmq1
if not errorlevel 1 goto RECOVER_DONE
call :TRY_RECOVER rabbitmq2
if not errorlevel 1 goto RECOVER_DONE
echo ERROR: Could not rejoin via Node 1 or Node 2.
echo Confirm at least one of them is running and reachable, then try again.
docker exec rabbitmq3 rabbitmqctl start_app >nul 2>&1
goto FAILED
:RECOVER_DONE
echo.
echo SUCCESS: RabbitMQ Node 3 rejoined the cluster.
docker exec rabbitmq3 rabbitmqctl cluster_status
echo Management console: https://localhost:15671
pause
goto MENU

:TRY_RECOVER
REM %~1 = peer short hostname, e.g. rabbitmq1
echo Attempting recovery via rabbit@%~1 ...
set /a FATT=0
:TRY_RECOVER_FORGET
docker exec rabbitmq3 rabbitmqctl -n rabbit@%~1 forget_cluster_node rabbit@rabbitmq3 >nul 2>&1
if not errorlevel 1 goto TRY_RECOVER_JOIN
set /a FATT+=1
if %FATT% GEQ 4 goto TRY_RECOVER_JOIN
timeout /t 3 /nobreak >nul
goto TRY_RECOVER_FORGET
:TRY_RECOVER_JOIN
docker exec rabbitmq3 rabbitmqctl reset
if errorlevel 1 exit /b 1
docker exec rabbitmq3 rabbitmqctl join_cluster rabbit@%~1
if errorlevel 1 exit /b 1
docker exec rabbitmq3 rabbitmqctl start_app
if errorlevel 1 exit /b 1
exit /b 0

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
echo RabbitMQ Node 3 operation failed. Confirm Node 1 is running and reachable at RABBIT1_IP.
pause
goto MENU
