@echo off
cd %~dp0..
echo Configuring environment for Queue Server...
copy /y env-configs\queue-server.env .env >nul
echo Starting Queue Server...
call .\gradlew.bat :queue-server:runQueueServer
pause
