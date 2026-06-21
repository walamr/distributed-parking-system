@echo off
cd %~dp0..
echo Starting MongoDB Node 2 container...
docker compose --env-file network-ips.env -f docker-compose.mongo2.yml up -d
echo MongoDB Node 2 is running.
echo Waiting 40 seconds for MongoDB to start...
timeout /t 40 >nul
call .\interactive_queries.bat 2
