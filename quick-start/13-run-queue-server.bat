@echo off
chcp 65001 >nul
setlocal
cd %~dp0..
echo Configuring environment for Queue Server...
copy /y env-configs\queue-server.env .env >nul
echo Starting Queue Server...
call .\gradlew.bat :queue-server:runQueueServer
if errorlevel 1 echo Queue Server stopped because of an error. Review the messages above.
pause
endlocal
