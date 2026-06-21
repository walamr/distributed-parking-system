@echo off
cd %~dp0..
echo Cleaning and resetting RabbitMQ Node 2 container and volumes...
docker compose --env-file network-ips.env -f docker-compose.rabbitmq2.yml down -v
echo Reset complete.
pause
