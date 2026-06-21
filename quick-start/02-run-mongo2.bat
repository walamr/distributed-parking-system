@echo off
cd %~dp0..
echo Starting MongoDB Node 2 container...
docker compose --env-file network-ips.env -f docker-compose.mongo2.yml up -d
echo MongoDB Node 2 is running.
echo Waiting for MongoDB Node 2 to start...

:WAIT_START
docker exec mongo2 mongosh --tls --tlsAllowInvalidCertificates --tlsAllowInvalidHostnames --tlsCAFile /etc/mongo/certs/ca-cert.pem --tlsCertificateKeyFile /etc/mongo/certs/mongo2.pem --host localhost --port 27017 --eval "db.runCommand({ping: 1})" --quiet >nul 2>&1
if %ERRORLEVEL% neq 0 (
    timeout /t 1 >nul
    goto WAIT_START
)

:WAIT_RS
cls
echo =========================================================
echo Waiting for MongoDB Replica Set to be fully healthy...
echo =========================================================
docker exec mongo2 mongosh --tls --tlsAllowInvalidCertificates --tlsAllowInvalidHostnames --tlsCAFile /etc/mongo/certs/ca-cert.pem --tlsCertificateKeyFile /etc/mongo/certs/mongo2.pem --host localhost --port 27017 --eval "try { rs.status().members.forEach(m => print('  - ' + m.name + ': ' + m.stateStr)) } catch(e) { print('  - Replica set not initialized yet') }" --quiet
echo =========================================================
docker exec mongo2 mongosh --tls --tlsAllowInvalidCertificates --tlsAllowInvalidHostnames --tlsCAFile /etc/mongo/certs/ca-cert.pem --tlsCertificateKeyFile /etc/mongo/certs/mongo2.pem --host localhost --port 27017 --eval "const s = rs.status(); if (!s.ok || s.members.length < 3 || s.members.some(m => m.state !== 1 && m.state !== 2)) { throw new Error('Not ready'); }" --quiet >nul 2>&1
if %ERRORLEVEL% neq 0 (
    timeout /t 3 >nul
    goto WAIT_RS
)

echo.
echo All MongoDB nodes are connected and healthy!
timeout /t 2 >nul
call .\interactive_queries.bat 2
