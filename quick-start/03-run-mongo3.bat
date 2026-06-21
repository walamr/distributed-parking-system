@echo off
cd %~dp0..
echo Starting MongoDB Node 3 container...
docker compose --env-file network-ips.env -f docker-compose.mongo3.yml up -d
echo MongoDB Node 3 is running.
echo Waiting for MongoDB Node 3 to be ready...

:WAIT_LOOP
docker exec mongo3 mongosh --tls --tlsAllowInvalidCertificates --tlsAllowInvalidHostnames --tlsCAFile /etc/mongo/certs/ca-cert.pem --tlsCertificateKeyFile /etc/mongo/certs/mongo3.pem --host localhost --port 27017 --eval "db.runCommand({ping: 1})" --quiet >nul 2>&1
if %ERRORLEVEL% neq 0 (
    timeout /t 1 >nul
    goto WAIT_LOOP
)

echo MongoDB Node 3 is ready!
call .\interactive_queries.bat 3
