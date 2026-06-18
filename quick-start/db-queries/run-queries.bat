@echo off
:init
:: Automatically detect which mongo container is active on this machine
set container=mongo1
set cert=mongo1.pem

docker ps --format "{{.Names}}" | findstr "mongo1" >nul
if errorlevel 1 (
    docker ps --format "{{.Names}}" | findstr "mongo2" >nul
    if errorlevel 1 (
        docker ps --format "{{.Names}}" | findstr "mongo3" >nul
        if errorlevel 1 (
            echo [ERROR] No active MongoDB docker container found on this machine!
            echo Please make sure you started the DB containers using quick-start scripts.
            pause
            exit
        ) else (
            set container=mongo3
            set cert=mongo3.pem
        )
    ) else (
        set container=mongo2
        set cert=mongo2.pem
    )
)

:menu
cls
echo ==================================================
echo   MONGODB QUERY DASHBOARD - MULLIGAN PARKING
echo   Active Container: %container%
echo ==================================================
echo   [1] View All Parking Transactions (Oldest to Newest)
echo   [2] View All Citations (Oldest to Newest)
echo   [3] View Full History for Vehicle '682-14-762'
echo   [4] View Registered Vehicles and Users
echo   [5] View Available Zones and Hourly Rates
echo   [6] View Details for Space 10
echo   [7] Run Recommender Simulation for Space 1
echo   [8] Exit Dashboard
echo ==================================================
set /p opt="Select Question [1-8] > "

set query=
if "%opt%"=="1" set query=q1_transactions.js
if "%opt%"=="2" set query=q2_citations.js
if "%opt%"=="3" set query=q3_vehicle_history.js
if "%opt%"=="4" set query=q4_registered_users.js
if "%opt%"=="5" set query=q5_zones.js
if "%opt%"=="6" set query=q6_space10.js
if "%opt%"=="7" set query=recommend_space.js
if "%opt%"=="8" exit

if "%query%"=="" goto menu

echo.
echo Executing query on %container%...
echo.

docker exec -i %container% mongosh --host %container% -u mulligan_db_admin -p db_pwd_rotated_admin --authenticationDatabase admin --tls --tlsCertificateKeyFile /etc/mongo/certs/%cert% --tlsCAFile /etc/mongo/certs/ca-cert.pem parking_db < %~dp0%query%

echo.
pause
goto menu
