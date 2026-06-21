@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

if "%~1"=="1" (
    set "MONGO_NODE=mongo1"
    goto MENU
)
if "%~1"=="2" (
    set "MONGO_NODE=mongo2"
    goto MENU
)
if "%~1"=="3" (
    set "MONGO_NODE=mongo3"
    goto MENU
)

:CHOOSE_NODE
cls
echo =========================================================
echo [Select the MongoDB Node to execute queries on]
echo =========================================================
echo 1. mongo1 (Typically the Primary Node)
echo 2. mongo2 (Secondary Node)
echo 3. mongo3 (Secondary Node)
echo 9. Exit / Quit
echo =========================================================
set /p node_choice="Enter the node number (1-3, 9 to quit): "

if "%node_choice%"=="9" goto EXIT_SCRIPT
if "%node_choice%"=="1" (
    set "MONGO_NODE=mongo1"
    goto MENU
)
if "%node_choice%"=="2" (
    set "MONGO_NODE=mongo2"
    goto MENU
)
if "%node_choice%"=="3" (
    set "MONGO_NODE=mongo3"
    goto MENU
)

echo Invalid choice, please enter 1, 2, 3, or 9.
goto CHOOSE_NODE

:MENU
echo.
echo =========================================================
echo Mulligan Municipality Parking Queries - Node: %MONGO_NODE%
echo =========================================================
echo 1. Show all parking transactions sorted from oldest to newest
echo 2. Show all issued citations sorted chronologically
echo 3. Search full parking history for a vehicle (prompts for plate number)
echo 4. Get zone spaces and citations for a specific space (prompts for space ID)
echo 5. Get parking recommendations based on a specific space (prompts for space ID)
echo 6. Count citations for a specific zone (prompts for zone name)
echo 7. Show all registered users and vehicles in the system
echo 8. Show available parking zones and their hourly rates
echo 9. Exit / Quit
echo 0. Return to Node selection
echo =========================================================
set /p choice="Enter the query number (0-9): "

set "MONGO_CMD=docker exec -i %MONGO_NODE% mongosh "mongodb://mulligan_db_admin:db_pwd_rotated_admin@mongo1:27017,mongo2:27017,mongo3:27017/parking_db?replicaSet=rs0^&authSource=admin" --tls --tlsAllowInvalidCertificates --tlsAllowInvalidHostnames --tlsCAFile /etc/mongo/certs/ca-cert.pem --tlsCertificateKeyFile /etc/mongo/certs/%MONGO_NODE%.pem --quiet --eval"
set "READ_PREF=db.getMongo().setReadPref('primary'); "

if "%choice%"=="9" goto EXIT_SCRIPT
if "%choice%"=="1" goto Q1
if "%choice%"=="2" goto Q2
if "%choice%"=="3" goto Q3
if "%choice%"=="4" goto Q4
if "%choice%"=="5" goto Q5
if "%choice%"=="6" goto Q6
if "%choice%"=="7" goto Q7
if "%choice%"=="8" goto Q8
if "%choice%"=="0" goto CHOOSE_NODE

echo Invalid number, try again.
goto MENU

:Q1
echo Fetching data from %MONGO_NODE%...
%MONGO_CMD% "%READ_PREF% const events=db.transactions.find().sort({timestamp:1,storedAt:1,_id:1}).toArray(); print('Total parking events: '+events.length); console.table(events.map(function(e,i){const p=e.payload||{};const action=(p.type||(e.type||'').replace('transaction.','')).toUpperCase();return {'#':i+1,'Event':action,'Vehicle':p.vehicleId||e.vehicleId||'','Space':p.spaceId||e.spaceId||'','Area':p.areaName||'','Cost':p.cost||'','Event Time':new Date(Number(e.timestamp)*1000).toISOString(),'Stored At':e.storedAt?new Date(Number(e.storedAt)).toISOString():''};}))"
if errorlevel 1 echo ERROR: MongoDB query failed. Check the message above and verify the replica set, credentials, and TLS files.
goto MENU

:Q2
echo Fetching data from %MONGO_NODE%...
%MONGO_CMD% "%READ_PREF% printjson(db.citations.find().sort({timestamp: 1}).toArray())"
if errorlevel 1 echo ERROR: MongoDB query failed. Check the message above and verify the replica set, credentials, and TLS files.
goto MENU

:Q3
set /p VEHICLE_ID="Enter vehicle plate number (e.g. 682-14-762): "
echo Fetching data for vehicle %VEHICLE_ID% from %MONGO_NODE%...
%MONGO_CMD% "%READ_PREF% printjson(db.transactions.find({ $or: [ { 'payload.vehicleId': '%VEHICLE_ID%' }, { vehicleId: '%VEHICLE_ID%' } ] }).sort({timestamp: 1}).toArray())"
if errorlevel 1 echo ERROR: MongoDB query failed. Check the message above.
goto MENU

:Q4
set /p SPACE_ID="Enter space ID (e.g. 1): "
echo Fetching zone spaces for space %SPACE_ID% from %MONGO_NODE%...
set "AGG_Q=%READ_PREF% (function(searchId) { const spaceIdStr = searchId.toString(); const space = db.spaces.findOne({ spaceId: spaceIdStr }); if (!space) { print('Error: Parking space not found'); return; } print('Space ' + spaceIdStr + ' is in zone: ' + space.zoneName); const results = db.spaces.aggregate([{ $match: { zoneName: space.zoneName } }, { $lookup: { from: 'citations', let: { sid: '$spaceId' }, pipeline: [{ $match: { $expr: { $or: [{ $eq: ['$payload.spaceId', '$$sid'] }, { $eq: ['$spaceId', '$$sid'] }] } } }], as: 'citationsList' } }, { $project: { _id: 0, 'Space Number': '$spaceId', 'Citations Count': { $size: '$citationsList' } } }]).toArray(); console.table(results); })('%SPACE_ID%')"
%MONGO_CMD% "%AGG_Q%"
if errorlevel 1 echo ERROR: MongoDB query failed. Check the message above.
goto MENU

:Q5
set /p SPACE_ID="Enter space ID for recommendation (e.g. 10): "
echo Fetching recommendations for space %SPACE_ID% from %MONGO_NODE%...
set "REC_CMD=%READ_PREF% function recommendSpace(searchId) { const spaceIdStr = searchId.toString(); const space = db.spaces.findOne({ spaceId: spaceIdStr }); if (!space) { print('Error: Parking space not found'); return; } const zoneName = space.zoneName; const spaces = db.spaces.find({ zoneName: zoneName }).toArray(); const spaceDetails = spaces.map(sp => { const sid = sp.spaceId; const citationCount = db.citations.countDocuments({ $or: [ { 'payload.spaceId': sid }, { 'spaceId': sid } ] }); const latestTx = db.transactions.find({ $or: [ { 'payload.spaceId': sid }, { 'spaceId': sid } ] }).sort({ timestamp: -1, storedAt: -1 }).limit(1).toArray()[0]; const isOccupied = latestTx && ((latestTx.payload && latestTx.payload.type === 'start') || latestTx.type === 'transaction.start'); return { spaceId: sid, citationCount: citationCount, isOccupied: !!isOccupied }; }); const freeSpaces = spaceDetails.filter(sd => !sd.isOccupied); let recommendedIds = []; if (freeSpaces.length > 0) { const minCitations = Math.min(...freeSpaces.map(fs => fs.citationCount)); const searchedSpaceDetail = spaceDetails.find(sd => sd.spaceId === spaceIdStr); if (searchedSpaceDetail && !searchedSpaceDetail.isOccupied && searchedSpaceDetail.citationCount === minCitations) { recommendedIds.push(spaceIdStr); } else { const bestCandidates = freeSpaces.filter(fs => fs.citationCount === minCitations); const targetNum = parseInt(spaceIdStr, 10); let minDistance = Infinity; bestCandidates.forEach(cand => { const candNum = parseInt(cand.spaceId, 10); const dist = Math.abs(candNum - targetNum); if (dist < minDistance) minDistance = dist; }); bestCandidates.forEach(cand => { const candNum = parseInt(cand.spaceId, 10); const dist = Math.abs(candNum - targetNum); if (dist === minDistance) recommendedIds.push(cand.spaceId); }); } } const tableRows = spaceDetails.map(sd => { let recMark = ''; if (recommendedIds.includes(sd.spaceId)) { recMark = 'RECOMMENDED'; } else if (sd.spaceId === spaceIdStr) { recMark = '(Your Choice)'; } return { 'Space Number': sd.spaceId, 'Status': sd.isOccupied ? 'Occupied' : 'Free', 'Citations Count': sd.citationCount, 'Highlight': recMark }; }); tableRows.sort((a, b) => parseInt(a['Space Number']) - parseInt(b['Space Number'])); console.table(tableRows); } recommendSpace('%SPACE_ID%');"
%MONGO_CMD% "%REC_CMD%"
if errorlevel 1 echo ERROR: MongoDB query failed. Check the message above.
goto MENU

:Q6
set /p ZONE_NAME="Enter zone name (e.g. Magnolia Way): "
echo Fetching citations for zone '%ZONE_NAME%' from %MONGO_NODE%...
set "AGG_Q=%READ_PREF% db.spaces.aggregate([{ $match: { zoneName: '%ZONE_NAME%' } }, { $lookup: { from: 'citations', let: { sid: '$spaceId' }, pipeline: [{ $match: { $expr: { $or: [{ $eq: ['$payload.spaceId', '$$sid'] }, { $eq: ['$spaceId', '$$sid'] }] } } }], as: 'citationsList' } }, { $project: { _id: 0, 'Space Number': '$spaceId', 'Citations Count': { $size: '$citationsList' } } }]).toArray()"
%MONGO_CMD% "%AGG_Q%"
if errorlevel 1 echo ERROR: MongoDB query failed. Check the message above.
goto MENU

:Q7
echo Fetching users and vehicles from %MONGO_NODE%...
%MONGO_CMD% "%READ_PREF% print('--- USERS ---'); printjson(db.users.find().pretty().toArray()); print('--- VEHICLES ---'); printjson(db.vehicles.find().pretty().toArray());"
if errorlevel 1 echo ERROR: MongoDB query failed. Check the message above.
goto MENU

:Q8
echo Fetching available zones and rates from %MONGO_NODE%...
%MONGO_CMD% "%READ_PREF% printjson(db.spaces.aggregate([{ $group: { _id: '$zoneName', hourlyRates: { $addToSet: '$hourlyRate' } } }, { $sort: { _id: 1 } }]).toArray())"
if errorlevel 1 echo ERROR: MongoDB query failed. Check the message above.
goto MENU

:EXIT_SCRIPT
endlocal
exit /b 9
