@echo off
cd %~dp0..
echo Starting RabbitMQ Node 3 container...
docker compose --env-file network-ips.env -f docker-compose.rabbitmq3.yml up -d
echo Waiting for RabbitMQ service to initialize...

:wait_loop
docker exec rabbitmq3 rabbitmq-diagnostics -q check_port_connectivity >nul 2>&1
if errorlevel 1 (
    timeout /t 2 /nobreak >nul
    goto wait_loop
)

echo.
echo RabbitMQ is ready! Joining cluster with Node 1...
echo.

docker exec rabbitmq3 rabbitmqctl stop_app
docker exec rabbitmq3 rabbitmqctl reset
docker exec rabbitmq3 rabbitmqctl join_cluster rabbit@rabbitmq1
docker exec rabbitmq3 rabbitmqctl start_app

echo.
echo RabbitMQ Node 3 clustered successfully!
echo Opening RabbitMQ Management Console...
start https://localhost:15674
echo.
pause
