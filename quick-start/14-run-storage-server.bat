@echo off
cd %~dp0..
echo Configuring environment for Storage Server...
copy /y env-configs\storage-server.env .env >nul
echo Starting Storage Server...
call .\gradlew.bat :storage-server:runStorageServer
pause
