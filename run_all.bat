@echo off
echo Starting Servers...
call .\gradlew.bat :queue-server:run
start "Storage" cmd /k ".\gradlew.bat :storage-server:run"
start "Mulligan Application Gateway" cmd /k ".\gradlew.bat :mulligan-app:run"
