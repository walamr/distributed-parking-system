@echo off
cd %~dp0..
echo Starting MongoDB Node 3 container...
docker compose --env-file network-ips.env -f docker-compose.mongo3.yml up -d
echo MongoDB Node 3 is running.
pause
