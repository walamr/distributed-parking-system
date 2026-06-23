@echo off
setlocal
cd /d "%~dp0.."
for /f "tokens=1,* delims==" %%A in (network-ips.env) do (
    if "%%A"=="MONGO2_IP" set "MONGO2_IP=%%B"
    if "%%A"=="MONGO3_IP" set "MONGO3_IP=%%B"
)
goto START
:CLEAN
call :CHECK_DOCKER
if errorlevel 1 exit /b 1
echo Stopping and removing the old MongoDB Node 3 container...
echo Persistent Docker volume mongo3-data is kept. No database data is deleted.
docker compose --env-file network-ips.env -f docker-compose.mongo3.yml down
exit /b %ERRORLEVEL%

:START
call :CHECK_DOCKER
if errorlevel 1 goto FAILED
if not defined MONGO3_IP (
    echo ERROR: MONGO3_IP is missing from network-ips.env.
    goto FAILED
)
if not defined MONGO2_IP (
    echo ERROR: MONGO2_IP is missing from network-ips.env.
    goto FAILED
)
echo Waiting for MongoDB Node 2 at %MONGO2_IP%:27017 before starting Node 3...
call :WAIT_PREVIOUS_NODE "%MONGO2_IP%" "MongoDB Node 2"
if errorlevel 1 goto FAILED
echo SUCCESS: MongoDB Node 2 is reachable. Starting Node 3 automatically.
echo Safe startup restarts the MongoDB Node 3 container without deleting the persistent volume.
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
    goto FAILED
)
echo Waiting for MongoDB Node 1 initialization... attempt %ATTEMPTS% of 120
timeout /t 5 /nobreak >nul
goto WAIT_RS
:READY
echo MongoDB replica set is healthy.
call ".\interactive_queries.bat" 3
if errorlevel 9 goto SHUTDOWN
goto FAILED

:SHUTDOWN
echo Exit selected. Stopping MongoDB Node 3...
docker compose --env-file network-ips.env -f docker-compose.mongo3.yml down
exit /b %ERRORLEVEL%
:FAILED
echo MongoDB Node 3 operation failed.
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

:SHOW_RS_STATUS
echo Current replica-set status:
docker exec %~1 mongosh --host localhost --port 27017 -u mulligan_db_admin -p db_pwd_rotated_admin --authenticationDatabase admin --tls --tlsAllowInvalidCertificates --tlsAllowInvalidHostnames --tlsCAFile /etc/mongo/certs/ca-cert.pem --tlsCertificateKeyFile /etc/mongo/certs/%~2 --quiet --eval "try{printjson(rs.status().members.map(function(m){return {name:m.name,state:m.stateStr,health:m.health,lastHeartbeatMessage:m.lastHeartbeatMessage};}));}catch(e){print(e.codeName+': '+e.message);quit(2);}" 2>&1
if not errorlevel 1 exit /b 0
echo Authenticated status failed; checking whether the replica set is uninitialized...
docker exec %~1 mongosh --host localhost --port 27017 --tls --tlsAllowInvalidCertificates --tlsAllowInvalidHostnames --tlsCAFile /etc/mongo/certs/ca-cert.pem --tlsCertificateKeyFile /etc/mongo/certs/%~2 --quiet --eval "try{printjson(rs.status());}catch(e){print(e.codeName+': '+e.message);quit(2);}" 2>&1
exit /b 0

:WAIT_PREVIOUS_NODE
set /a PREVIOUS_ATTEMPTS=0
:WAIT_PREVIOUS_NODE_LOOP
powershell -NoProfile -Command "if (Test-NetConnection -ComputerName '%~1' -Port 27017 -InformationLevel Quiet -WarningAction SilentlyContinue) { exit 0 } else { exit 1 }" >nul 2>&1
if not errorlevel 1 exit /b 0
set /a PREVIOUS_ATTEMPTS+=1
if %PREVIOUS_ATTEMPTS% GEQ 120 (
    echo ERROR: %~2 was not reachable at %~1:27017 within 600 seconds.
    exit /b 1
)
echo Waiting for %~2... attempt %PREVIOUS_ATTEMPTS% of 120
timeout /t 5 /nobreak >nul
goto WAIT_PREVIOUS_NODE_LOOP
