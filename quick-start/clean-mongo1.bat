@echo off
cd %~dp0..
echo Cleaning and resetting MongoDB Node 1 container and volumes...
docker compose --env-file network-ips.env -f docker-compose.mongo1.yml down -v
echo Reset complete.
pause
