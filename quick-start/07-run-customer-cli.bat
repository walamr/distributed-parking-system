@echo off
cd %~dp0..
echo Configuring environment for Customer Application...
copy /y env-configs\customer.env .env >nul

echo ==================================================
echo   Customer UI - Startup Mode
echo ==================================================
echo   [1] CLI Mode (Headless Console)
echo   [2] GUI Mode (JavaFX Graphical Window)
echo ==================================================
set /p mode="Select Option [Default 1] > "

set run_task=:customer-ui:runCLI
if "%mode%"=="2" set run_task=:mulligan-app:run

echo.
echo Starting Customer Application...
echo.
call .\gradlew.bat %run_task%
pause
