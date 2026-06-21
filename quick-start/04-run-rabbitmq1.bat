@echo off
cd %~dp0..
echo Starting RabbitMQ Node 1 container...
docker compose --env-file network-ips.env -f docker-compose.rabbitmq1.yml up -d
echo RabbitMQ Node 1 is running.
echo.
echo Opening RabbitMQ Management Console...
start https://localhost:15671
echo.
pause
