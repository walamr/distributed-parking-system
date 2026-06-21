@echo off
cd %~dp0..
echo Starting MongoDB Node 1 container...
docker compose --env-file network-ips.env -f docker-compose.mongo1.yml up -d
echo MongoDB Node 1 is running.
echo Waiting 40 seconds for MongoDB to start...
timeout /t 40 >nul
call ..\interactive_queries.bat 1
