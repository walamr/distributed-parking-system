@echo off
setlocal
cd /d "%~dp0.."
for /f "tokens=1,* delims==" %%A in (network-ips.env) do if "%%A"=="MONGO3_IP" set "MONGO3_IP=%%B"
if not "%~1"=="" (
    set "ACTION=%~1"
    goto PROCESS_ACTION
)

:MENU
cls
echo =========================================================
echo MongoDB Node 3
echo =========================================================
echo 1. Clean node data, start, and open query menu
echo 2. Clean node data, then start (same safe startup)
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
goto START
:CLEAN_ONLY
call :CLEAN
pause
exit /b %ERRORLEVEL%
:CLEAN
call :CHECK_DOCKER
if errorlevel 1 exit /b 1
echo Cleaning MongoDB Node 3 container and volume...
docker compose --env-file network-ips.env -f docker-compose.mongo3.yml down -v
exit /b %ERRORLEVEL%

:START
call :CHECK_DOCKER
if errorlevel 1 goto FAILED
if not defined MONGO3_IP (
    echo ERROR: MONGO3_IP is missing from network-ips.env.
    goto FAILED
)
echo Safe startup always removes the old MongoDB Node 3 container and volume first.
call :CLEAN
if errorlevel 1 goto FAILED
echo Starting MongoDB Node 3 container...
docker compose --env-file network-ips.env -f docker-compose.mongo3.yml up -d
if errorlevel 1 goto FAILED
set /a ATTEMPTS=0
:WAIT_START
docker exec mongo3 mongosh --tls --tlsAllowInvalidCertificates --tlsAllowInvalidHostnames --tlsCAFile /etc/mongo/certs/ca-cert.pem --tlsCertificateKeyFile /etc/mongo/certs/mongo3.pem --host localhost --port 27017 --eval "db.runCommand({ping: 1})" --quiet >nul 2>&1
if not errorlevel 1 goto LOCAL_READY
set /a ATTEMPTS+=1
if %ATTEMPTS% GEQ 60 (
    echo ERROR: mongo3 did not become reachable within 60 seconds.
    docker logs --tail 30 mongo3
    goto FAILED
)
timeout /t 1 >nul
goto WAIT_START
:LOCAL_READY
echo MongoDB Node 3 is reachable.
echo Configuring MongoDB Node 3 with its network address %MONGO3_IP%...
powershell -NoProfile -ExecutionPolicy Bypass -File ".\setup-mongo-node.ps1" -ContainerName mongo3 -IpAddress "%MONGO3_IP%"
if errorlevel 1 goto FAILED
echo Waiting for MongoDB Node 1 to initialize the fresh replica set...
set /a ATTEMPTS=0
:WAIT_RS
docker exec mongo3 mongosh --host localhost --port 27017 -u mulligan_db_admin -p db_pwd_rotated_admin --authenticationDatabase admin --tls --tlsAllowInvalidCertificates --tlsAllowInvalidHostnames --tlsCAFile /etc/mongo/certs/ca-cert.pem --tlsCertificateKeyFile /etc/mongo/certs/mongo3.pem --quiet --eval "const s=rs.status(); const bad=s.members.filter(function(m){return [1,2].indexOf(m.state)===-1;}); if(s.ok===1){if(s.members.length===3){if(bad.length===0){quit(0);}}} quit(2);" >nul 2>&1
if not errorlevel 1 goto READY
set /a ATTEMPTS+=1
if %ATTEMPTS% GEQ 120 (
    echo ERROR: mongo3 is running, but the replica set is not healthy.
    call :SHOW_RS_STATUS mongo3 mongo3.pem
    echo Start all MongoDB PCs and initialize the cluster once from MongoDB Node 1.
    pause
    goto MENU
)
echo Waiting for MongoDB Node 1 initialization... attempt %ATTEMPTS% of 120
timeout /t 5 /nobreak >nul
goto WAIT_RS
:READY
echo MongoDB replica set is healthy.
call ".\interactive_queries.bat" 3
goto MENU
:FAILED
echo MongoDB Node 3 operation failed.
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

:SHOW_RS_STATUS
echo Current replica-set status:
docker exec %~1 mongosh --host localhost --port 27017 -u mulligan_db_admin -p db_pwd_rotated_admin --authenticationDatabase admin --tls --tlsAllowInvalidCertificates --tlsAllowInvalidHostnames --tlsCAFile /etc/mongo/certs/ca-cert.pem --tlsCertificateKeyFile /etc/mongo/certs/%~2 --quiet --eval "try{printjson(rs.status().members.map(function(m){return {name:m.name,state:m.stateStr,health:m.health,lastHeartbeatMessage:m.lastHeartbeatMessage};}));}catch(e){print(e.codeName+': '+e.message);quit(2);}" 2>&1
if not errorlevel 1 exit /b 0
echo Authenticated status failed; checking whether the replica set is uninitialized...
docker exec %~1 mongosh --host localhost --port 27017 --tls --tlsAllowInvalidCertificates --tlsAllowInvalidHostnames --tlsCAFile /etc/mongo/certs/ca-cert.pem --tlsCertificateKeyFile /etc/mongo/certs/%~2 --quiet --eval "try{printjson(rs.status());}catch(e){print(e.codeName+': '+e.message);quit(2);}" 2>&1
exit /b 0
