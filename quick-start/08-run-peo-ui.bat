@echo off
cd %~dp0..
echo Configuring environment for PEO Application...
copy /y env-configs\peo.env .env >nul

echo ==================================================
echo   PEO UI - Startup Mode
echo ==================================================
echo   [1] CLI Mode (Headless Console)
echo   [2] GUI Mode (JavaFX Graphical Window)
echo ==================================================
set /p mode="Select Option [Default 1] > "

set run_task=:peo-ui:runCLI
if "%mode%"=="2" set run_task=:peo-ui:run

echo.
echo Starting PEO Application...
echo.
call .\gradlew.bat %run_task%
pause
