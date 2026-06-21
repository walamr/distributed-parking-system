@echo off
cd %~dp0..
echo Configuring environment for MO Application...
copy /y env-configs\mo.env .env >nul

echo ==================================================
echo   MO UI - Startup Mode
echo ==================================================
echo   [1] CLI Mode (Headless Console)
echo   [2] GUI Mode (JavaFX Graphical Window)
echo ==================================================
set /p mode="Select Option [Default 1] > "

set run_task=:mo-ui:runCLI
if "%mode%"=="2" set run_task=:mulligan-app:run

echo.
echo Starting MO Application...
echo.
call .\gradlew.bat %run_task%
pause
