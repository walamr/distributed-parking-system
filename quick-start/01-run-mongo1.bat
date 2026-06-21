@echo off
cd %~dp0..
echo Starting MongoDB Node 1 container...
docker compose --env-file network-ips.env -f docker-compose.mongo1.yml up -d
echo MongoDB Node 1 is running.
echo Waiting for MongoDB Node 1 to be ready...

:WAIT_LOOP
docker exec mongo1 mongosh --tls --tlsAllowInvalidCertificates --tlsAllowInvalidHostnames --tlsCAFile /etc/mongo/certs/ca-cert.pem --tlsCertificateKeyFile /etc/mongo/certs/mongo1.pem --host localhost --port 27017 --eval "db.runCommand({ping: 1})" --quiet >nul 2>&1
if %ERRORLEVEL% neq 0 (
    timeout /t 1 >nul
    goto WAIT_LOOP
)

echo MongoDB Node 1 is ready!
call .\interactive_queries.bat 1
