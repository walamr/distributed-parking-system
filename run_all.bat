@echo off
echo Starting Servers...
start "Storage" cmd /k ".\gradlew.bat :storage-server:run"
start "Queue" cmd /k ".\gradlew.bat :queue-server:run"
start "Mulligan Application Gateway" cmd /k ".\gradlew.bat :mulligan-app:run"
