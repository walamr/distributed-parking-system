@echo off
setlocal
cd /d "%~dp0.."
for /f "tokens=1,* delims==" %%A in (network-ips.env) do (
    if "%%A"=="MONGO1_IP" set "MONGO1_IP=%%B"
    if "%%A"=="MONGO2_IP" set "MONGO2_IP=%%B"
    if "%%A"=="MONGO3_IP" set "MONGO3_IP=%%B"
)
goto START

:CLEAN
call :CHECK_DOCKER
if errorlevel 1 exit /b 1
echo Stopping and removing the old MongoDB Node 1 container...
echo Persistent Docker volume mongo1-data is kept. No database data is deleted.
docker compose --env-file network-ips.env -f docker-compose.mongo1.yml down
exit /b %ERRORLEVEL%

:START
call :CHECK_DOCKER
if errorlevel 1 goto FAILED
call :CHECK_NETWORK_CONFIG
if errorlevel 1 goto FAILED
echo Safe startup restarts the MongoDB Node 1 container without deleting the persistent volume.
call :CLEAN
if errorlevel 1 goto FAILED
echo Starting MongoDB Node 1 container...
docker compose --env-file network-ips.env -f docker-compose.mongo1.yml up -d
if errorlevel 1 goto FAILED

set /a ATTEMPTS=0
:WAIT_START
docker exec mongo1 mongosh --tls --tlsAllowInvalidCertificates --tlsAllowInvalidHostnames --tlsCAFile /etc/mongo/certs/ca-cert.pem --tlsCertificateKeyFile /etc/mongo/certs/mongo1.pem --host localhost --port 27017 --eval "db.runCommand({ping: 1})" --quiet >nul 2>&1
if not errorlevel 1 goto LOCAL_READY
set /a ATTEMPTS+=1
if %ATTEMPTS% GEQ 60 (
    echo ERROR: mongo1 did not become reachable within 60 seconds.
    docker logs --tail 30 mongo1
    goto FAILED
)
timeout /t 1 >nul
goto WAIT_START

:LOCAL_READY
echo MongoDB Node 1 is reachable.
echo Waiting for MongoDB Node 2 at %MONGO2_IP%:27017...
call :WAIT_REMOTE_NODE "%MONGO2_IP%" "MongoDB Node 2"
if errorlevel 1 goto FAILED
echo SUCCESS: MongoDB Node 2 is reachable.
echo Waiting for MongoDB Node 3 at %MONGO3_IP%:27017...
call :WAIT_REMOTE_NODE "%MONGO3_IP%" "MongoDB Node 3"
if errorlevel 1 goto FAILED
echo SUCCESS: MongoDB Node 3 is reachable.
echo All MongoDB containers are reachable.
echo Generating environment files from network-ips.env on MongoDB Node 1...
powershell -NoProfile -ExecutionPolicy Bypass -File ".\generate-env.ps1"
if errorlevel 1 goto FAILED
echo Environment files generated successfully.
echo Initializing the fresh replica set and sample data...
powershell -NoProfile -ExecutionPolicy Bypass -File ".\init-mongodb-network.ps1"
if errorlevel 1 goto FAILED
echo Checking replica-set health...
set /a ATTEMPTS=0
:WAIT_RS
docker exec mongo1 mongosh --host localhost --port 27017 -u mulligan_db_admin -p db_pwd_rotated_admin --authenticationDatabase admin --tls --tlsAllowInvalidCertificates --tlsAllowInvalidHostnames --tlsCAFile /etc/mongo/certs/ca-cert.pem --tlsCertificateKeyFile /etc/mongo/certs/mongo1.pem --quiet --eval "const s=rs.status(); const bad=s.members.filter(function(m){return [1,2].indexOf(m.state)===-1;}); if(s.ok===1){if(s.members.length===3){if(bad.length===0){quit(0);}}} quit(2);" >nul 2>&1
if not errorlevel 1 goto READY
set /a ATTEMPTS+=1
if %ATTEMPTS% GEQ 20 (
    echo.
    echo ERROR: mongo1 is running, but the three-node replica set is not healthy.
    call :SHOW_RS_STATUS mongo1 mongo1.pem
    echo Start mongo2 and mongo3 and verify network-ips.env and Windows Firewall.
    goto FAILED
)
echo Waiting for the replica set... attempt %ATTEMPTS% of 20
timeout /t 3 >nul
goto WAIT_RS

:READY
echo MongoDB replica set is healthy.
call ".\interactive_queries.bat" 1
if errorlevel 9 goto SHUTDOWN
goto FAILED

:SHUTDOWN
echo Exit selected. Stopping MongoDB Node 1...
docker compose --env-file network-ips.env -f docker-compose.mongo1.yml down
exit /b %ERRORLEVEL%

:FAILED
echo MongoDB Node 1 operation failed.
echo The MongoDB container was not stopped. Only query-menu option 9 stops it.
pause
exit /b 1

:CHECK_DOCKER
docker info >nul 2>&1
if errorlevel 1 (
    echo ERROR: Docker Desktop Linux engine is not running or is not reachable.
    echo Start Docker Desktop and wait until it reports that the engine is running.
    exit /b 1
)
exit /b 0

:CHECK_NETWORK_CONFIG
if not defined MONGO1_IP (
    echo ERROR: MONGO1_IP is missing from network-ips.env.
    exit /b 1
)
if not defined MONGO2_IP (
    echo ERROR: MONGO2_IP is missing from network-ips.env.
    exit /b 1
)
if not defined MONGO3_IP (
    echo ERROR: MONGO3_IP is missing from network-ips.env.
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

:WAIT_REMOTE_NODE
set /a REMOTE_ATTEMPTS=0
:WAIT_REMOTE_NODE_LOOP
powershell -NoProfile -Command "if (Test-NetConnection -ComputerName '%~1' -Port 27017 -InformationLevel Quiet -WarningAction SilentlyContinue) { exit 0 } else { exit 1 }" >nul 2>&1
if not errorlevel 1 exit /b 0
set /a REMOTE_ATTEMPTS+=1
if %REMOTE_ATTEMPTS% GEQ 120 (
    echo ERROR: %~2 did not become reachable at %~1:27017 within 600 seconds.
    echo Start its quick-start Mongo script and verify network-ips.env and Windows Firewall.
    exit /b 1
)
echo Waiting for %~2... attempt %REMOTE_ATTEMPTS% of 120
timeout /t 5 /nobreak >nul
goto WAIT_REMOTE_NODE_LOOP
