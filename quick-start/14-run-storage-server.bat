@echo off
chcp 65001 >nul
setlocal
cd %~dp0..
echo Configuring environment for Storage Server...
copy /y env-configs\storage-server.env .env >nul
echo Starting Storage Server...
call .\gradlew.bat :storage-server:runStorageServer
if errorlevel 1 echo Storage Server stopped because of an error. Review the messages above.
pause
endlocal
