@echo off
set node_choice=
set detected_node=0

rem Auto-detect node based on network-ips.env and local IPs
for /f "usebackq tokens=*" %%i in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "$config=@{}; if (Test-Path 'network-ips.env') { Get-Content 'network-ips.env' | Where-Object { $_ -match '^\s*[^#].*=.*' } | ForEach-Object { $parts = $_ -split '=', 2; $config[$parts[0].Trim()] = $parts[1].Trim() } }; $rec1=$config['RECOMMENDER1_IP']; $rec2=$config['RECOMMENDER2_IP']; $rec3=$config['RECOMMENDER3_IP']; $localIps=Get-NetIPAddress -AddressFamily IPv4 | Select-Object -ExpandProperty IPAddress; if ($rec1 -and $rec1 -ne '127.0.0.1' -and $rec1 -ne 'localhost' -and $localIps -contains $rec1) { Write-Output '1' } elseif ($rec2 -and $rec2 -ne '127.0.0.1' -and $rec2 -ne 'localhost' -and $localIps -contains $rec2) { Write-Output '2' } elseif ($rec3 -and $rec3 -ne '127.0.0.1' -and $rec3 -ne 'localhost' -and $localIps -contains $rec3) { Write-Output '3' } else { Write-Output '0' }"`) do set detected_node=%%i

if "%detected_node%"=="1" (
    echo Auto-detected this machine as: recommender1 (Leader)
    set node_choice=1
)
if "%detected_node%"=="2" (
    echo Auto-detected this machine as: recommender2 (Follower)
    set node_choice=2
)
if "%detected_node%"=="3" (
    echo Auto-detected this machine as: recommender3 (Follower)
    set node_choice=3
)

if "%node_choice%"=="" (
    echo ==================================================
    echo   Select Recommender Node:
    echo   [1] recommender1 (Leader, Port 8091)
    echo   [2] recommender2 (Follower, Port 8092)
    echo   [3] recommender3 (Follower, Port 8093)
    echo ==================================================
    set /p node_choice="Select Node [1-3] > "
)

set nodeId=recommender1
set port=8091
set isLeader=true
set env_file=recommender1.env

if "%node_choice%"=="2" (
    set nodeId=recommender2
    set port=8092
    set isLeader=false
    set env_file=recommender2.env
)
if "%node_choice%"=="3" (
    set nodeId=recommender3
    set port=8093
    set isLeader=false
    set env_file=recommender3.env
)

echo.
echo ==================================================
echo   Select Interface Mode:
echo   [1] CLI Mode (Console)
echo   [2] GUI Mode (JavaFX Window)
echo ==================================================
set /p mode_choice="Select Mode [Default 1] > "

set cli_flag=-Dcli=true
if "%mode_choice%"=="2" set cli_flag=-Dcli=false

echo.
echo Configuring environment for %nodeId%...
copy /y env-configs\%env_file% .env >nul 2>&1
if not exist .env (
    copy /y .env .env >nul 2>&1
)

echo.
echo Starting %nodeId%...
echo.
call .\gradlew.bat :recommender-server:runRecommenderServer -Dport=%port% -DnodeId=%nodeId% -DisLeader=%isLeader% %cli_flag%
pause
