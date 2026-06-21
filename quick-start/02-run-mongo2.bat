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
echo MongoDB Node 2
echo =========================================================
echo 1. Start node and open query menu
echo 2. Clean node data, then start
echo 3. Clean node data only
echo 9. Exit
set /p ACTION="Choose an action: "
:PROCESS_ACTION
if "%ACTION%"=="1" goto START
if "%ACTION%"=="2" goto CLEAN_AND_START
if "%ACTION%"=="3" goto CLEAN_ONLY
if "%ACTION%"=="9" exit /b 0
goto MENU

:CLEAN_AND_START
call :CLEAN
if errorlevel 1 goto FAILED
goto START
:CLEAN_ONLY
call :CLEAN
pause
exit /b %ERRORLEVEL%
:CLEAN
call :CHECK_DOCKER
if errorlevel 1 exit /b 1
echo Cleaning MongoDB Node 2 container and volume...
docker compose --env-file network-ips.env -f docker-compose.mongo2.yml down -v
exit /b %ERRORLEVEL%

:START
call :CHECK_DOCKER
if errorlevel 1 goto FAILED
echo Starting MongoDB Node 2 container...
docker compose --env-file network-ips.env -f docker-compose.mongo2.yml up -d
if errorlevel 1 goto FAILED
set /a ATTEMPTS=0
:WAIT_START
docker exec mongo2 mongosh --tls --tlsAllowInvalidCertificates --tlsAllowInvalidHostnames --tlsCAFile /etc/mongo/certs/ca-cert.pem --tlsCertificateKeyFile /etc/mongo/certs/mongo2.pem --host localhost --port 27017 --eval "db.runCommand({ping: 1})" --quiet >nul 2>&1
if not errorlevel 1 goto LOCAL_READY
set /a ATTEMPTS+=1
if %ATTEMPTS% GEQ 60 (
    echo ERROR: mongo2 did not become reachable within 60 seconds.
    docker logs --tail 30 mongo2
    goto FAILED
)
timeout /t 1 >nul
goto WAIT_START
:LOCAL_READY
echo MongoDB Node 2 is reachable. Checking replica-set health...
set /a ATTEMPTS=0
:WAIT_RS
docker exec mongo2 mongosh --tls --tlsAllowInvalidCertificates --tlsAllowInvalidHostnames --tlsCAFile /etc/mongo/certs/ca-cert.pem --tlsCertificateKeyFile /etc/mongo/certs/mongo2.pem --host localhost --port 27017 --eval "const s=rs.status(); if (!s.ok || s.members.length ^< 3 || s.members.some(m =^> m.state !== 1 ^&^& m.state !== 2)) { quit(2); }" --quiet >nul 2>&1
if not errorlevel 1 goto READY
set /a ATTEMPTS+=1
if %ATTEMPTS% GEQ 20 (
    echo ERROR: mongo2 is running, but the replica set is not healthy.
    echo Start all MongoDB PCs and initialize the cluster once from MongoDB Node 1.
    pause
    goto MENU
)
echo Waiting for the replica set... attempt %ATTEMPTS% of 20
timeout /t 3 >nul
goto WAIT_RS
:READY
echo MongoDB replica set is healthy.
call ".\interactive_queries.bat" 2
goto MENU
:FAILED
echo MongoDB Node 2 operation failed.
pause
goto MENU

:CHECK_DOCKER
docker info >nul 2>&1
if errorlevel 1 (
    echo ERROR: Docker Desktop Linux engine is not running or is not reachable.
    echo Start Docker Desktop and wait until it reports that the engine is running.
    exit /b 1
)
exit /b 0
