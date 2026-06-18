@echo off
cd %~dp0..
echo Configuring environment for Recommender Node 2...
copy /y env-configs\recommender2.env .env >nul 2>&1
if not exist .env (
    copy /y .env .env >nul 2>&1
)

echo ==================================================
echo   Recommender Node 2 - Startup Mode
echo ==================================================
echo   [1] CLI Mode (Headless Console)
echo   [2] GUI Mode (JavaFX Graphical Window)
echo ==================================================
set /p mode="Select Option [Default 1] > "

set cli_flag=-Dcli=true
if "%mode%"=="2" set cli_flag=-Dcli=false

echo.
echo Starting Recommender Node 2...
echo.
call .\gradlew.bat :recommender-server:runRecommenderServer -Dport=8092 -DnodeId=recommender2 -DisLeader=false %cli_flag%
pause
