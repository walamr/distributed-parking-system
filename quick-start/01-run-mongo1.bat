@echo off
cd %~dp0..
echo Starting MongoDB Node 1 container...
docker compose -f docker-compose.mongo1.yml up -d
echo MongoDB Node 1 is running.
pause
