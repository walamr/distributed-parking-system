@echo off
chcp 65001 >nul
setlocal
cd %~dp0..
echo Starting Storage Server automatically because it owns MongoDB persistence...
start "Storage Server" cmd /k call "%~dp014-run-storage-server.bat"
echo Storage Server launch requested.
echo.
echo Configuring environment for Queue Server...
copy /y env-configs\queue-server.env .env >nul
echo Starting Queue Server...
call .\gradlew.bat :queue-server:runQueueServer
if errorlevel 1 echo Queue Server stopped because of an error. Review the messages above.
pause
endlocal
